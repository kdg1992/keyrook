// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.transfer.CsvMapping
import app.keyrook.core.transfer.InvalidImportException
import app.keyrook.core.transfer.VaultTransfer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CsvColumnsTest {
    private val transfer = VaultTransfer()

    @Test fun `header inspection follows CSV escaping and leaves caller buffer intact`() {
        val bytes = "\uFEFF\"title, full\",\"quoted \"\"name\"\"\",\"two\nlines\"\r\nsecret,row,values".toByteArray()
        val original = bytes.copyOf()
        assertEquals(listOf("title, full", "quoted \"name\"", "two\nlines"), transfer.csvColumns(bytes))
        assertArrayEquals(original, bytes)
        bytes.fill(0)
    }

    @Test fun `empty duplicate malformed and excessive headers are rejected in inspection and import`() {
        val invalid = listOf("", "Title,", "Title,Title", "Title,   ", "\"unterminated",
            "\"Title\"bad", (1..101).joinToString(",") { "column$it" }, "x".repeat(513))
        for (header in invalid) {
            val bytes = header.toByteArray()
            assertThrows(InvalidImportException::class.java) { transfer.csvColumns(bytes) }
            assertThrows(InvalidImportException::class.java) { transfer.importCsv(bytes, CsvMapping("Title")) }
        }
        assertThrows(InvalidImportException::class.java) { transfer.csvColumns(byteArrayOf(0xc0.toByte())) }
    }

    @Test fun `header inspection stops before malformed records but full import rejects them`() {
        val bytes = "Title\n\"not closed".toByteArray()
        assertEquals(listOf("Title"), transfer.csvColumns(bytes))
        assertThrows(InvalidImportException::class.java) { transfer.importCsv(bytes, CsvMapping("Title")) }
    }
}
