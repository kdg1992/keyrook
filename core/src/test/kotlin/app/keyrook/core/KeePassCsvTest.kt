// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.model.EntryData
import app.keyrook.core.model.Vault
import app.keyrook.core.transfer.InvalidImportException
import app.keyrook.core.transfer.KeePassCsvHeaderException
import app.keyrook.core.transfer.KeePassCsvLayout
import app.keyrook.core.transfer.VaultTransfer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KeePassCsvTest {
    private val transfer = VaultTransfer()

    private data class Row(val title: String, val url: String, val user: String, val password: String, val notes: String)
    private val rows = listOf(
        Row("Mail, private", "https://mail.example.invalid", "User-SENTINEL-1", "Pass\"word\\SENTINEL,2", "line one\nline two"),
        Row("Router", "", "admin", "\\\\trailing\\", ""),
    )

    private fun assertImported(vault: Vault) {
        assertEquals(rows.size, vault.entries.size)
        rows.zip(vault.entries).forEach { (expected, entry) ->
            assertEquals(expected.title, entry.title)
            val data = entry.data as EntryData.Web
            assertEquals(expected.url, data.url.value.useChars { String(it) })
            assertEquals(expected.user, data.username.value.useChars { String(it) })
            assertEquals(expected.password, data.password.value.useChars { String(it) })
            assertEquals(expected.notes, entry.notes.useChars { String(it) })
            assertFalse(data.url.hidden)
            assertTrue(data.username.hidden)
            assertTrue(data.password.hidden)
        }
    }

    @Test fun `KeePass 1x CSV export with backslash escapes imports all fields`() {
        fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val text = "﻿\"Account\",\"Login Name\",\"Password\",\"Web Site\",\"Comments\"\r\n" +
            rows.joinToString("") { listOf(it.title, it.user, it.password, it.url, it.notes).joinToString(",", transform = ::quote) + "\r\n" }
        val bytes = text.toByteArray()
        transfer.importKeePassCsv(bytes).use(::assertImported)
    }

    @Test fun `KeePass 2x field names with standard CSV quoting import all fields`() {
        fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
        val text = "Title,UserName,Password,URL,Notes\n" +
            rows.joinToString("") { listOf(it.title, it.user, it.password, it.url, it.notes).joinToString(",", transform = ::quote) + "\n" }
        transfer.importKeePassCsv(text.toByteArray()).use(::assertImported)
    }

    @Test fun `column order may differ but the column set is fixed`() {
        val text = "\"Password\",\"Comments\",\"Account\",\"Web Site\",\"Login Name\"\n\"secret\",\"\",\"Title\",\"\",\"user\"\n"
        transfer.importKeePassCsv(text.toByteArray()).use { vault ->
            val data = vault.entries.single().data as EntryData.Web
            assertEquals("Title", vault.entries.single().title)
            assertEquals("secret", data.password.value.useChars { String(it) })
        }
        assertEquals(setOf("Account", "Login Name", "Password", "Web Site", "Comments"), KeePassCsvLayout.KEEPASS_1X.columns)
    }

    @Test fun `mismatched headers are rejected before rows are read`() {
        val mismatched = listOf(
            "Title,Username,Password,URL,Notes\nx,y,z,,",
            "Account,Login Name,Password,Web Site\nx,y,z,",
            "Account,Login Name,Password,Web Site,Comments,TOTP\nx,y,z,,,",
            "Group,Title,Username,Password,URL,Notes\n\"unterminated",
            "Title,UserName,Password,URL,Comments\nx,y,z,,",
            "keyrook-json\n\"{}\"",
        )
        for (text in mismatched) {
            assertThrows(KeePassCsvHeaderException::class.java) { transfer.importKeePassCsv(text.toByteArray()) }
        }
        assertThrows(InvalidImportException::class.java) { transfer.importKeePassCsv("Title,Title".toByteArray()) }
    }

    @Test fun `malformed KeePass 1x escapes and rows are rejected without data in errors`() {
        val header = "\"Account\",\"Login Name\",\"Password\",\"Web Site\",\"Comments\"\n"
        for (row in listOf("\"a\",\"b\",\"c\\x\",\"\",\"\"", "\"a\",\"b\",\"c\",\"\"", "\"a\",\"b\",\"c\",\"\",\"\\")) {
            val failure = assertThrows(InvalidImportException::class.java) { transfer.importKeePassCsv((header + row).toByteArray()) }
            assertFalse(failure.message.orEmpty().contains("a\""))
            assertNull(failure.cause)
        }
    }
}
