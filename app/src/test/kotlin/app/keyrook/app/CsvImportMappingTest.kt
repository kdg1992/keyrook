// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.EntryData
import app.keyrook.core.model.Vault
import app.keyrook.core.transfer.CsvMapping
import app.keyrook.core.transfer.InvalidImportException
import app.keyrook.core.transfer.PlaintextConsent
import app.keyrook.core.transfer.VaultTransfer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CsvImportMappingTest {
    @Test fun `mapping receives only exact headers and imported secrets outlive erased input`() {
        val bytes = "\uFEFF\"Account, name\",\"Secret \"\"column\"\"\"\r\nExample,test-password".toByteArray()
        importMappedCsv(bytes, guard = {}, selectMapping = {
            assertEquals(listOf("Account, name", "Secret \"column\""), it)
            CsvMapping(it[0], password = it[1])
        })!!.use { vault ->
            assertTrue(bytes.all { it == 0.toByte() })
            assertEquals("Example", vault.entries.single().title)
            (vault.entries.single().data as EntryData.Web).password.value.useChars {
                assertEquals("test-password", String(it))
            }
        }
    }

    @Test fun `cancel does not parse invalid data rows and wipes input`() {
        val bytes = "Title,Password\n\"unterminated-private-row".toByteArray()
        assertNull(importMappedCsv(bytes, guard = {}, selectMapping = { null }))
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun `failed parse and failed dialog erase owned input`() {
        val malformed = "Title,Password\n\"unterminated-private-row".toByteArray()
        assertThrows(InvalidImportException::class.java) {
            importMappedCsv(malformed, guard = {}, selectMapping = { CsvMapping("Title") })
        }
        assertTrue(malformed.all { it == 0.toByte() })
        val bytes = "Title\nExample".toByteArray()
        assertThrows(IllegalStateException::class.java) {
            importMappedCsv(bytes, guard = {}, selectMapping = { error("Dialog disposed") })
        }
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun `expired session cannot import a completed mapping`() {
        val bytes = "Title\nExample".toByteArray()
        var active = true
        assertThrows(IllegalStateException::class.java) {
            importMappedCsv(bytes, guard = { check(active) }, selectMapping = {
                active = false
                CsvMapping("Title")
            })
        }
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun `invalid headers fail without exposing names or opening dialog`() {
        for (text in listOf("private-name,private-name\na,b", "Title,\na,b", " ,Password\na,b")) {
            val bytes = text.toByteArray()
            val failure = assertThrows(CsvHeaderException::class.java) {
                importMappedCsv(bytes, guard = {}, selectMapping = { fail("Dialog must not open") })
            }
            assertFalse(failure.message!!.contains("private-name"))
            assertNull(failure.cause)
            assertTrue(bytes.all { it == 0.toByte() })
        }
    }

    @Test fun `Keyrook CSV is detected without mapping and retains original vault identity`() {
        Vault().use { source ->
            val bytes = VaultTransfer().exportCsv(source, PlaintextConsent(true, true))
            importMappedCsv(bytes, guard = {}, selectMapping = { fail("No mapping for Keyrook CSV") })!!.use {
                assertEquals(source.id, it.id)
                assertEquals(source.revision, it.revision)
            }
            assertTrue(bytes.all { it == 0.toByte() })
        }
    }

    @Test fun `suggestions retain exact source spelling and leave absent optional fields empty`() {
        assertEquals(CsvMapping("titel", username = "LOGIN", password = "Passwort"),
            suggestedCsvMapping(listOf("titel", "LOGIN", "Passwort")))
        assertEquals(CsvMapping("custom, title"), suggestedCsvMapping(listOf("custom, title")))
    }
}
