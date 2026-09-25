// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.model.*
import app.keyrook.core.transfer.PlaintextConsent
import app.keyrook.core.transfer.VaultTransfer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** New IDs for imported records, with every reference following them. */
class RecordIdsTest {
    @Test fun `every record gets a new ID and every reference follows it`() {
        sampleVault().use { source ->
            val server = source.entries[4]
            val web = source.entries[0]
            // History may keep references to current and to removed entries.
            val removed = id()
            val ssh = source.entries[5].let { entry ->
                entry.copy(history = listOf(HistoryItem(DATE, (entry.data as EntryData.Ssh).copy(serverIds = listOf(server.id, removed)))))
            }
            val domain = source.entries[6].let { entry ->
                entry.copy(history = listOf(HistoryItem(DATE, (entry.data as EntryData.Domain).copy(registrarLoginId = web.id))))
            }
            val vault = source.copy(entries = source.entries.map { if (it.id == ssh.id) ssh else if (it.id == domain.id) domain else it })
            val renamed = vault.withNewRecordIds()
            assertEquals(vault.id, renamed.id)
            assertEquals(vault.revision, renamed.revision)
            val before = (vault.customers.map { it.id } + vault.projects.map { it.id } + vault.entries.map { it.id } +
                vault.templates.map { it.id }).toSet()
            val after = renamed.customers.map { it.id } + renamed.projects.map { it.id } + renamed.entries.map { it.id } +
                renamed.templates.map { it.id }
            assertEquals(after.size, after.toSet().size)
            assertTrue(after.none { it in before })
            renamed.validate()

            val customer = renamed.customers.single().id
            val project = renamed.projects.single()
            assertEquals(customer, project.customerId)
            assertTrue(renamed.entries.all { it.customerId == customer && it.projectId == project.id })
            assertEquals(customer, renamed.templates.single().customerId)
            assertEquals(project.id, renamed.templates.single().projectId)
            val newServer = renamed.entries[4].id
            val newWeb = renamed.entries[0].id
            val newSsh = renamed.entries[5]
            assertEquals(listOf(newServer), (newSsh.data as EntryData.Ssh).serverIds)
            assertEquals(listOf(newServer, removed), (newSsh.history.single().data as EntryData.Ssh).serverIds)
            assertEquals(newWeb, (renamed.entries[6].data as EntryData.Domain).registrarLoginId)
            assertEquals(newWeb, (renamed.entries[6].history.single().data as EntryData.Domain).registrarLoginId)
            // Everything else is unchanged, and secrets are shared rather than copied.
            assertEquals(vault.entries.map { it.title }, renamed.entries.map { it.title })
            assertSame(vault.entries[0].notes, renamed.entries[0].notes)
            assertEquals(vault.entries.map { it.pinned }, renamed.entries.map { it.pinned })
        }
    }

    @Test fun `a vault can import its own export twice`() {
        val transfer = VaultTransfer()
        sampleVault().use { vault ->
            val export = transfer.exportJson(vault, PlaintextConsent(true, true))
            var merged = vault
            repeat(2) {
                val imported = transfer.importJson(export).withNewRecordIds()
                merged = merged.copy(customers = merged.customers + imported.customers,
                    projects = merged.projects + imported.projects, entries = merged.entries + imported.entries,
                    templates = merged.templates + imported.templates)
                merged.validate()
            }
            export.fill(0)
            assertEquals(3 * vault.entries.size, merged.entries.size)
            assertEquals(3, merged.customers.size)
            assertEquals(3 * vault.templates.size, merged.templates.size)
        }
    }

    @Test fun `duplicate new IDs are refused`() {
        sampleVault().use { vault ->
            assertThrows(IllegalArgumentException::class.java) { vault.withNewRecordIds { "11111111-2222-4333-8444-555555555555" } }
        }
    }
}
