// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class PurgeTest {
    private val now = Instant.parse("2026-09-24T08:00:00Z")

    private fun trashed(vault: Vault, vararg indices: Int) =
        vault.copy(entries = vault.entries.mapIndexed { index, entry -> if (index in indices) entry.copy(deletedAt = DATE) else entry })

    private fun secrets(entry: Entry): List<Secret> = listOf(entry.notes) + entry.data.fields().map { it.value } +
        entry.history.flatMap { item -> item.data.fields().map { it.value } }

    private fun assertErased(secret: Secret) {
        assertThrows(IllegalStateException::class.java) { secret.useChars { } }
    }

    @Test fun `purging removes entry and history, erases its secrets and clears references`() {
        trashed(sampleVault(), 0).use { vault ->
            val web = vault.entries[0]
            val others = vault.entries.drop(1)
            val result = vault.purgeEntries(setOf(web.id), now)
            result.validate()
            assertEquals(others.map { it.id }, result.entries.map { it.id })
            assertTrue(result.entries.none { it.id == web.id })
            secrets(web).forEach(::assertErased)
            val domain = result.entries.single { it.data is EntryData.Domain }
            assertNull((domain.data as EntryData.Domain).registrarLoginId)
            assertEquals(now.toString(), domain.modifiedAt)
            result.entries.filter { it.data !is EntryData.Domain }.forEach { entry ->
                assertSame(others.single { it.id == entry.id }, entry)
            }
            result.entries.forEach { entry -> secrets(entry).forEach { secret -> secret.useChars { } } }
        }
    }

    @Test fun `emptying trash removes every trashed entry and dangling server references`() {
        trashed(sampleVault(), 4).use { vault ->
            val ids = vault.entries.filter { it.deletedAt != null }.map { it.id }.toSet()
            assertEquals(2, ids.size)
            val removed = vault.entries.filter { it.id in ids }
            val result = vault.purgeEntries(ids, now)
            result.validate()
            assertTrue(result.entries.none { it.deletedAt != null })
            assertEquals(vault.entries.size - 2, result.entries.size)
            assertEquals(emptyList<String>(), (result.entries.single { it.data is EntryData.Ssh }.data as EntryData.Ssh).serverIds)
            removed.flatMap(::secrets).forEach(::assertErased)
            assertEquals(vault.customers, result.customers)
            assertEquals(vault.projects, result.projects)
        }
    }

    @Test fun `active or unknown entries are refused without erasing anything`() {
        sampleVault().use { vault ->
            assertThrows(IllegalArgumentException::class.java) { vault.purgeEntries(setOf(vault.entries[0].id), now) }
            assertThrows(IllegalArgumentException::class.java) { vault.purgeEntries(setOf(id()), now) }
            assertThrows(IllegalArgumentException::class.java) { vault.purgeEntries(emptySet(), now) }
            vault.entries.flatMap(::secrets).forEach { secret -> secret.useChars { } }
        }
    }

    @Test fun `secret instances shared with remaining entries stay intact`() {
        sampleVault().use { sample ->
            val source = sample.entries[1]
            val copy = source.copy(id = id(), deletedAt = DATE)
            val vault = sample.copy(entries = sample.entries + copy)
            val result = vault.purgeEntries(setOf(copy.id), now)
            assertEquals(sample.entries.map { it.id }, result.entries.map { it.id })
            secrets(source).forEach { secret -> secret.useChars { } }
        }
    }
}
