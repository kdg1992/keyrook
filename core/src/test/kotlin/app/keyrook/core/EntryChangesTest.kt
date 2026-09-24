// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.model.*
import app.keyrook.core.transfer.PlaintextConsent
import app.keyrook.core.transfer.VaultTransfer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class EntryChangesTest {
    private val now = Instant.parse("2026-09-24T08:00:00Z")
    private val future = "2999-01-01T00:00:00Z"

    /** Entries 0 to 6 are active, entry 7 is in the trash; entry 1 was changed after [now]. */
    private fun vault() = sampleVault().let { vault ->
        vault.copy(entries = vault.entries.mapIndexed { index, entry -> if (index == 1) entry.copy(modifiedAt = future) else entry })
    }

    @Test fun `trashing several entries stamps each with now or its later change time and leaves others untouched`() {
        vault().use { vault ->
            val ids = vault.entries.take(3).map { it.id }.toSet()
            val result = vault.trashEntries(ids, restore = false, now)
            result.validate()
            assertEquals(vault.id, result.id)
            assertEquals(vault.revision, result.revision)
            assertEquals(vault.entries.map { it.id }, result.entries.map { it.id })
            result.entries.take(3).forEachIndexed { index, entry ->
                val expected = if (index == 1) future else now.toString()
                assertEquals(expected, entry.deletedAt)
                assertEquals(expected, entry.modifiedAt)
                assertSame(vault.entries[index].data, entry.data)
                assertSame(vault.entries[index].notes, entry.notes)
            }
            result.entries.drop(3).forEachIndexed { index, entry -> assertSame(vault.entries[index + 3], entry) }
        }
    }

    @Test fun `restoring clears the deletion and never moves the change time backwards`() {
        vault().use { vault ->
            val trashed = vault.trashEntries(setOf(vault.entries[1].id, vault.entries[2].id), restore = false, now)
            val restored = trashed.trashEntries(setOf(vault.entries[1].id, vault.entries[2].id, vault.entries[7].id),
                restore = true, now.plusSeconds(60))
            restored.validate()
            assertTrue(restored.entries.all { it.deletedAt == null })
            assertEquals(future, restored.entries[1].modifiedAt)
            assertEquals(now.plusSeconds(60).toString(), restored.entries[2].modifiedAt)
        }
    }

    @Test fun `a bulk trash change fails as a whole for unknown, empty or mixed selections`() {
        vault().use { vault ->
            val active = vault.entries[0].id
            val trashed = vault.entries[7].id
            assertThrows(IllegalArgumentException::class.java) { vault.trashEntries(emptySet(), restore = false, now) }
            assertThrows(IllegalArgumentException::class.java) { vault.trashEntries(setOf(active, id()), restore = false, now) }
            assertThrows(IllegalArgumentException::class.java) { vault.trashEntries(setOf(active, trashed), restore = false, now) }
            assertThrows(IllegalArgumentException::class.java) { vault.trashEntries(setOf(active, trashed), restore = true, now) }
            assertTrue(vault.entries.take(7).all { it.deletedAt == null })
        }
    }

    @Test fun `adding a tag changes only entries without it and keeps stored tag order`() {
        vault().use { vault ->
            val ids = vault.entries.take(3).map { it.id }.toSet()
            val tagged = vault.tagEntries(ids, "ops", add = true, now)
            tagged.validate()
            tagged.entries.take(3).forEach { assertEquals(listOf("Tag-SENTINEL-9891", "ops"), it.tags) }
            assertEquals(future, tagged.entries[1].modifiedAt)
            assertEquals(now.toString(), tagged.entries[0].modifiedAt)
            tagged.entries.drop(3).forEachIndexed { index, entry -> assertSame(vault.entries[index + 3], entry) }
            val again = tagged.tagEntries(setOf(vault.entries[0].id, vault.entries[3].id), "ops", add = true, now.plusSeconds(5))
            assertSame(tagged.entries[0], again.entries[0])
            assertEquals(listOf("Tag-SENTINEL-9891", "ops"), again.entries[3].tags)
        }
    }

    @Test fun `removing a tag returns the same vault when no entry has it`() {
        vault().use { vault ->
            val ids = vault.entries.map { it.id }.toSet()
            assertSame(vault, vault.tagEntries(ids, "absent", add = false, now))
            assertSame(vault, vault.tagEntries(ids, "Tag-SENTINEL-9891", add = true, now))
            val removed = vault.tagEntries(setOf(vault.entries[7].id), "Tag-SENTINEL-9891", add = false, now)
            removed.validate()
            assertEquals(emptyList<String>(), removed.entries[7].tags)
            assertEquals(now.toString(), removed.entries[7].modifiedAt)
            assertEquals(DATE, removed.entries[7].deletedAt)
        }
    }

    @Test fun `invalid tags and unknown entries are refused and a tag over the limit fails validation`() {
        vault().use { vault ->
            val one = setOf(vault.entries[0].id)
            listOf("", " ", " padded", "a,b", "x".repeat(257)).forEach { tag ->
                assertThrows(IllegalArgumentException::class.java) { vault.tagEntries(one, tag, add = true, now) }
            }
            assertThrows(IllegalArgumentException::class.java) { vault.tagEntries(setOf(id()), "ops", add = true, now) }
            val full = vault.copy(entries = vault.entries.mapIndexed { index, entry ->
                if (index == 0) entry.copy(tags = (1..100).map { "t$it" }) else entry
            })
            assertThrows(IllegalArgumentException::class.java) { full.tagEntries(one, "ops", add = true, now).validate() }
        }
    }

    @Test fun `the favorite tag is reserved hidden from visible tags and set like any other tag`() {
        assertTrue(ReservedTags.isReserved(ReservedTags.FAVORITE))
        assertFalse(ReservedTags.isReserved("favorite"))
        assertFalse(ReservedTags.isReserved("Keyrook:Favorite"))
        assertEquals(listOf("a", "b"), ReservedTags.visible(listOf("a", ReservedTags.FAVORITE, "b")))
        vault().use { vault ->
            val ids = setOf(vault.entries[0].id, vault.entries[7].id)
            val favorites = vault.tagEntries(ids, ReservedTags.FAVORITE, add = true, now)
            favorites.validate()
            assertEquals(listOf(true, false, false, false, false, false, false, true), favorites.entries.map { it.favorite })
            assertEquals(listOf("Tag-SENTINEL-9891"), ReservedTags.visible(favorites.entries[0].tags))
            val cleared = favorites.tagEntries(setOf(vault.entries[0].id), ReservedTags.FAVORITE, add = false, now)
            assertFalse(cleared.entries[0].favorite)
            assertEquals(listOf("Tag-SENTINEL-9891"), cleared.entries[0].tags)
        }
    }

    @Test fun `the favorite tag survives JSON and Keyrook CSV export and import`() {
        val transfer = VaultTransfer()
        val consent = PlaintextConsent(true, true)
        vault().use { vault ->
            val favorites = vault.tagEntries(setOf(vault.entries[0].id), ReservedTags.FAVORITE, add = true, now)
            val json = transfer.exportJson(favorites, consent)
            val csv = transfer.exportCsv(favorites, consent)
            try {
                listOf(transfer.importJson(json), transfer.importCsv(csv)).forEach { imported ->
                    imported.use {
                        assertEquals(listOf("Tag-SENTINEL-9891", ReservedTags.FAVORITE), it.entries[0].tags)
                        assertEquals(1, it.entries.count { entry -> entry.favorite })
                    }
                }
            } finally { json.fill(0); csv.fill(0) }
        }
    }
}
