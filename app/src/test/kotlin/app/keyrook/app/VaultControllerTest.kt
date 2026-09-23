// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class VaultControllerTest {
    @TempDir lateinit var directory: Path

    @Test fun `save and lock preserve independently owned snapshots and password arrays are erased`() {
        val file = directory.resolve("test.keyrook")
        VaultController().use { controller ->
            val password = "synthetic-master-passphrase".toCharArray()
            controller.unlock(file, password, null, true).close()
            assertTrue(password.all { it == '\u0000' })
            val data = blankData(EntryType.WEB)
            val entry = editedEntry(null, data, "Synthetic login", "test", "", "",
                listOf("https://example.invalid", "sample", "synthetic-secret", ""), listOf(false, false, true, true))
            data.fields().forEach { it.value.close() }
            Vault(entries = listOf(entry)).use {
                controller.save(entry).use { snapshot ->
                    controller.lock()
                    assertEquals("Synthetic login", snapshot.entries.single().title)
                    assertEquals("synthetic-secret", snapshot.entries.single().data.fields()[2].value.useChars { chars -> String(chars) })
                }
            }
            controller.unlock(file, "synthetic-master-passphrase".toCharArray(), null, false).use {
                assertEquals(1, it.entries.size)
            }
            controller.trash(entry.id, false).use { assertNotNull(it.entries.single().deletedAt) }
            controller.trash(entry.id, true).use { assertNull(it.entries.single().deletedAt) }
            controller.duplicate(entry.id).use {
                assertEquals(2, it.entries.size)
                assertEquals(2, it.entries.map { candidate -> candidate.id }.toSet().size)
                assertEquals("Synthetic login (Kopie)", it.entries.last().title)
            }
        }
    }

    @Test fun `editor duplicates history so closing draft never erases original`() {
        val data = blankData(EntryType.WEB)
        val first = editedEntry(null, data, "Login", "", "", "", listOf("", "", "first", ""), listOf(false, false, true, true))
        data.fields().forEach { it.value.close() }
        Vault(entries = listOf(first)).use {
            val second = editedEntry(first, first.data, "Login", "", "", "", listOf("", "", "second", ""), listOf(false, false, true, true))
            Vault(entries = listOf(second)).use { candidate ->
                candidate.validate()
                assertEquals("first", second.history.single().data.fields()[2].value.useChars { String(it) })
            }
            assertEquals("first", first.data.fields()[2].value.useChars { String(it) })
        }
    }

    @Test fun `all typed editor fields round trip independently`() {
        EntryType.entries.forEach { type ->
            val data = blankData(type)
            val values = data.fields().indices.map { "synthetic-$it" }
            val entry = editedEntry(null, data, type.label, "", "", "", values, data.fields().map { it.hidden })
            data.fields().forEach { it.value.close() }
            Vault(entries = listOf(entry)).use { candidate ->
                candidate.validate()
                assertEquals(values, entry.data.fields().map { field -> field.value.useChars { String(it) } })
            }
        }
    }

    @Test fun `failed authentication also erases submitted password`() {
        val password = "synthetic-password".toCharArray()
        VaultController().use { controller ->
            assertThrows(Exception::class.java) { controller.unlock(directory.resolve("absent.keyrook"), password, null, false) }
            assertTrue(password.all { it == '\u0000' })
        }
    }

    @Test fun `email endpoint and custom field metadata survive edits`() {
        fun field(value: String, hidden: Boolean = false, kind: FieldKind = FieldKind.TEXT) =
            Field(app.keyrook.core.crypto.Secret(value.toCharArray()), hidden, kind)
        val email = EntryData.Email(field("mail@example.invalid"), field("user"), field("secret", true),
            imap = MailEndpoint(field("imap.example.invalid"), 1993, MailEncryption.TLS),
            smtp = MailEndpoint(field("smtp.example.invalid"), 1587, MailEncryption.STARTTLS))
        val custom = EntryData.Custom(linkedMapOf("Website" to field("https://example.invalid", false, FieldKind.URL), "Token" to field("private", true)))
        listOf(email, custom).forEach { data ->
            val values = data.fields().map { it.value.useChars { chars -> String(chars) } }
            val edited = editedEntry(null, data, "Synthetic", "", "", "", values, data.fields().map { it.hidden })
            data.fields().forEach { it.value.close() }
            Vault(entries = listOf(edited)).use {
                it.validate()
                when (val result = edited.data) {
                    is EntryData.Email -> {
                        assertEquals(1993, result.imap!!.port)
                        assertEquals(MailEncryption.STARTTLS, result.smtp!!.encryption)
                    }
                    is EntryData.Custom -> {
                        assertEquals(FieldKind.URL, result.values.getValue("Website").kind)
                        assertTrue(result.values.getValue("Token").hidden)
                    }
                    else -> fail("Unexpected type")
                }
            }
        }
    }
}
