// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EntrySelectionTest {
    private val query = ListQuery("", EntryListFilters(), includeHidden = false)
    private val ids = listOf("a", "b", "c", "d")

    @Test fun `first result is selected and moves clamp at both ends`() {
        val start = EntrySelection().update(query, ids)
        assertEquals("a", start.selectedId)
        assertEquals("a", start.previous().selectedId)
        assertEquals("b", start.next().selectedId)
        assertEquals("d", start.next().next().next().next().next().selectedId)
        assertEquals("d", start.last().selectedId)
        assertEquals("c", start.last().previous().selectedId)
        assertEquals("a", start.last().first().selectedId)
        assertEquals("c", start.select("c").selectedId)
        assertEquals("a", start.select("unknown").selectedId)
    }

    @Test fun `empty lists have no selection and moves are safe`() {
        val empty = EntrySelection().update(query, emptyList())
        assertNull(empty.selectedId)
        assertNull(empty.next().selectedId)
        assertNull(empty.previous().selectedId)
        assertNull(empty.first().selectedId)
        assertNull(empty.last().selectedId)
        assertEquals("a", empty.update(query, ids).selectedId)
    }

    @Test fun `a new search or filter selects its first result while pending results keep the old list`() {
        val selected = EntrySelection().update(query, ids).last()
        val typing = query.copy(search = "b")
        val pending = selected.update(typing, null)
        assertSame(selected, pending)
        assertEquals("d", pending.selectedId)
        assertEquals("b", pending.update(typing, listOf("b", "c")).selectedId)
        val filtered = selected.update(query.copy(filters = EntryListFilters(type = EntryType.WEB)), listOf("c", "d"))
        assertEquals("c", filtered.selectedId)
        assertEquals("c", selected.update(query.copy(includeHidden = true), listOf("c", "a")).selectedId)
    }

    @Test fun `vault changes under the same query keep the selection or move to its neighbor`() {
        val onB = EntrySelection().update(query, ids).next()
        assertEquals("b", onB.update(query, listOf("x", "a", "b", "c", "d")).selectedId)
        assertEquals("b", onB.update(query, listOf("d", "c", "b", "a")).selectedId)
        // Trashing the selected entry removes it and selects the following entry.
        val trashed = onB.update(query, listOf("a", "c", "d"))
        assertEquals("c", trashed.selectedId)
        assertFalse("b" in trashed.visible)
        assertEquals(listOf("a", "c", "d"), trashed.visible)
        // Without a following entry the previous one is selected, otherwise the first.
        val onD = onB.last()
        assertEquals("c", onD.update(query, listOf("a", "b", "c")).selectedId)
        assertEquals("x", onD.update(query, listOf("x")).selectedId)
        assertNull(onD.update(query, emptyList()).selectedId)
    }

    @Test fun `selection follows filtered vault entries and drops trashed entries`() {
        fun entry(id: String, trash: Boolean = false) = Entry(id, id,
            EntryData.Custom(mapOf("Secret" to Field(Secret("synthetic".toCharArray())))),
            "2025-01-01T00:00:00Z", "2026-01-01T00:00:00Z", deletedAt = if (trash) "2026-01-01T00:00:00Z" else null)
        val today = LocalDate.of(2026, 12, 15)
        val filters = EntryListFilters()
        val active = ListQuery("", filters, false)
        Vault(entries = listOf(entry("a"), entry("b"), entry("c"))).use { before ->
            val all = before.entries.map { it.id }.toSet()
            val selection = EntrySelection().update(active, filters.select(before, all, today).map { it.id }).next()
            assertEquals("b", selection.selectedId)
            val after = before.copy(entries = before.entries.map { if (it.id == "b") it.copy(deletedAt = "2026-01-01T00:00:00Z") else it })
            val next = selection.update(active, filters.select(after, all, today).map { it.id })
            assertEquals("c", next.selectedId)
            assertEquals(listOf("a", "c"), next.visible)
            val trashView = filters.copy(trash = true)
            assertEquals("b", next.update(ListQuery("", trashView, false), trashView.select(after, all, today).map { it.id }).selectedId)
        }
    }
}
