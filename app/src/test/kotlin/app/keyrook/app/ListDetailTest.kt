// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Frame
import java.awt.event.WindowEvent

class ListDetailTest {
    private val first = RevealKey("vault", "a", "2026-01-01T00:00:00Z")
    private val second = RevealKey("vault", "b", "2026-01-01T00:00:00Z")

    @Test fun `list and details are side by side from the minimum width and stacked below it`() {
        assertEquals(WorkspaceLayout.LIST_DETAIL, workspaceLayout(LIST_DETAIL_MIN_WIDTH_DP))
        assertEquals(WorkspaceLayout.LIST_DETAIL, workspaceLayout(1600f))
        assertEquals(WorkspaceLayout.SINGLE_COLUMN, workspaceLayout(LIST_DETAIL_MIN_WIDTH_DP - 0.5f))
        assertEquals(WorkspaceLayout.SINGLE_COLUMN, workspaceLayout(0f))
        assertEquals(WorkspaceLayout.SINGLE_COLUMN, workspaceLayout(Float.POSITIVE_INFINITY))
        assertEquals(WorkspaceLayout.SINGLE_COLUMN, workspaceLayout(Float.NaN))
    }

    @Test fun `values start masked and are shown only while toggled for the displayed entry`() {
        val start = RevealState().follow(first)
        assertFalse(start.shows(first, 0))
        val shown = start.toggle(first, 2)
        assertTrue(shown.shows(first, 2))
        assertFalse(shown.shows(first, 0))
        assertFalse(shown.shows(second, 2))
        assertFalse(shown.shows(null, 2))
        val notes = shown.toggle(first, RevealState.NOTES)
        assertTrue(notes.shows(first, RevealState.NOTES))
        assertFalse(notes.toggle(first, 2).shows(first, 2))
        assertTrue(notes.toggle(first, 2).shows(first, RevealState.NOTES))
    }

    @Test fun `another entry, vault or saved version masks everything again`() {
        val shown = RevealState().toggle(first, 1).toggle(first, RevealState.NOTES)
        assertSame(shown, shown.follow(first))
        assertEquals(emptySet<Int>(), shown.follow(second).revealed)
        assertEquals(emptySet<Int>(), shown.follow(first.copy(vaultId = "other")).revealed)
        assertEquals(emptySet<Int>(), shown.follow(first.copy(modifiedAt = "2026-02-01T00:00:00Z")).revealed)
        assertEquals(emptySet<Int>(), shown.follow(null).revealed)
        // Returning to the first entry does not bring its values back.
        assertFalse(shown.follow(second).follow(first).shows(first, 1))
        // Toggling on another entry masks the previous entry first.
        val other = shown.toggle(second, 0)
        assertEquals(setOf(0), other.revealed)
        assertFalse(other.shows(first, 1))
    }

    @Test fun `clearing masks every value but keeps the displayed entry`() {
        val shown = RevealState().toggle(first, 0).toggle(first, 3)
        val cleared = shown.cleared()
        assertEquals(first, cleared.key)
        assertEquals(emptySet<Int>(), cleared.revealed)
        assertSame(cleared, cleared.cleared())
        assertEquals(RevealState(), RevealState().cleared())
    }

    @Test fun `leaving or minimizing the window masks values under every lock choice`() {
        assertTrue(windowEventMasksValues(WindowEvent.WINDOW_DEACTIVATED, Frame.NORMAL, Frame.NORMAL))
        assertTrue(windowEventMasksValues(WindowEvent.WINDOW_ICONIFIED, Frame.NORMAL, Frame.ICONIFIED))
        assertTrue(windowEventMasksValues(WindowEvent.WINDOW_STATE_CHANGED, Frame.NORMAL, Frame.ICONIFIED))
        assertFalse(windowEventMasksValues(WindowEvent.WINDOW_STATE_CHANGED, Frame.NORMAL, Frame.MAXIMIZED_BOTH))
        assertFalse(windowEventMasksValues(WindowEvent.WINDOW_ACTIVATED, Frame.NORMAL, Frame.NORMAL))
        assertFalse(windowEventMasksValues(WindowEvent.WINDOW_DEICONIFIED, Frame.ICONIFIED, Frame.NORMAL))
    }

    @Test fun `jumping selects a listed entry at once`() {
        val query = ListView().query()
        val selection = EntrySelection().update(query, listOf("a", "b", "c"))
        val jumped = selection.jump("c")
        assertEquals("c", jumped.selectedId)
        assertNull(jumped.target)
        assertEquals(jumped, jumped.update(query, listOf("a", "b", "c")))
        val view = ListView(search = "b")
        assertSame(view, view.showing("c", jumped.visible))
    }

    @Test fun `jumping to an entry outside the list shows all active entries and selects it when listed`() {
        val view = ListView(search = "mail", filters = EntryListFilters(trash = true, tag = "ops", sort = EntrySort.EXPIRY),
            includeHidden = true)
        val selection = EntrySelection().update(view.query(), listOf("x", "y"))
        assertSame(view, view.showing("y", selection.visible))
        val shown = view.showing("c", selection.visible)
        assertEquals(ListView(filters = EntryListFilters(sort = EntrySort.EXPIRY)), shown)
        val pending = selection.jump("c")
        assertEquals("x", pending.selectedId)
        assertEquals("c", pending.target)
        // Search results are pending for the new view: the target waits.
        assertSame(pending, pending.update(shown.query(), null))
        val selected = pending.update(shown.query(), listOf("a", "b", "c", "d"))
        assertEquals("c", selected.selectedId)
        assertNull(selected.target)
        assertEquals(selected, selected.update(shown.query(), listOf("a", "b", "c", "d")))
    }

    @Test fun `a jump target missing from the new list is dropped and never selected later`() {
        val selection = EntrySelection().update(ListView(search = "x").query(), listOf("x"))
        val pending = selection.jump("gone")
        val listed = pending.update(ListView().query(), listOf("a", "b"))
        assertEquals("a", listed.selectedId)
        assertNull(listed.target)
        assertEquals("a", listed.update(ListView().query(), listOf("a", "b", "gone")).selectedId)
    }
}
