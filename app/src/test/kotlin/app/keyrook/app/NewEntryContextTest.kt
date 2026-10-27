// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class NewEntryContextTest {
    private fun entry(type: EntryType, values: List<String>, hidden: List<Boolean>): Entry {
        val data = blankData(type)
        return try { editedEntry(null, data, "Synthetic", "", "", "", values, hidden) }
        finally { data.fields().forEach { it.value.close() } }
    }

    @Test fun `a duplicate keeps visible values and starts every hidden value empty`() {
        val server = entry(EntryType.SERVER, listOf("web01.example.invalid", "deploy", "synthetic-secret", "Linux", "web"),
            listOf(false, false, true, false, false))
        val web = entry(EntryType.WEB, listOf("https://example.invalid", "sample", "synthetic-secret", "JBSWY3DPEHPK3PXP"),
            listOf(false, false, true, true))
        Vault(entries = listOf(server, web)).use {
            assertEquals(listOf("web01.example.invalid", "deploy", "", "Linux", "web"), duplicateValues(server))
            assertEquals(listOf("https://example.invalid", "sample", "", ""), duplicateValues(web))
            // The source entry keeps its secrets.
            assertEquals("synthetic-secret", server.data.fields()[2].value.useChars { String(it) })
        }
    }

    @Test fun `only SSH key material and DNS notes take several lines`() {
        EntryType.entries.forEach { type ->
            val data = blankData(type)
            try {
                val multi = data.fields().indices.filter { data.multiLineField(it) }
                when (data) {
                    is EntryData.Ssh -> assertEquals(listOf(data.fields().indexOf(data.privateKey), data.fields().indexOf(data.publicKey)), multi)
                    is EntryData.Domain -> assertEquals(listOf(data.fields().indexOf(data.dnsNotes)), multi)
                    else -> assertEquals(emptyList<Int>(), multi, type.name)
                }
            } finally { data.fields().forEach { it.value.close() } }
        }
    }
}
