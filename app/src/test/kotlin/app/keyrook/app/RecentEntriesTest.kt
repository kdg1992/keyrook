// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.Vault
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecentEntriesTest {
    @Test fun `uses are ordered newest first without duplicates`() {
        val recent = RecentEntries().used("v", "a").used("v", "b").used("v", "c").used("v", "a")
        assertEquals(listOf("a", "c", "b"), recent.of("v"))
        assertEquals(listOf("b", "a", "c"), recent.used("v", "b").of("v"))
        assertEquals(recent, recent.used("v", "a"))
    }

    @Test fun `only the ten most recent entries are kept`() {
        var recent = RecentEntries()
        (1..12).forEach { recent = recent.used("v", "e$it") }
        assertEquals(RecentEntries.CAPACITY, recent.ids.size)
        assertEquals((12 downTo 3).map { "e$it" }, recent.of("v"))
        assertEquals(listOf("e3") + (12 downTo 4).map { "e$it" }, recent.used("v", "e3").of("v"))
    }

    @Test fun `another vault starts an empty list and never sees the previous one`() {
        val first = RecentEntries().used("v1", "a").used("v1", "b")
        assertTrue(first.of("v2").isEmpty())
        assertTrue(first.of(null).isEmpty())
        val second = first.used("v2", "c")
        assertEquals(listOf("c"), second.of("v2"))
        assertTrue(second.of("v1").isEmpty())
        assertTrue(RecentEntries().of(null).isEmpty())
    }

    @Test fun `locking discards the recent entries`() {
        val state = AppState()
        try {
            state.used("ignored-without-vault")
            assertEquals(RecentEntries(), state.recent)
            state.vault = Vault()
            val vaultId = requireNotNull(state.vault).id
            state.used("a")
            state.used("b")
            assertEquals(listOf("b", "a"), state.recent.of(vaultId))
            state.lockNow {}
            assertEquals(RecentEntries(), state.recent)
            assertTrue(state.recent.of(vaultId).isEmpty())
        } finally { state.dispose() }
    }
}
