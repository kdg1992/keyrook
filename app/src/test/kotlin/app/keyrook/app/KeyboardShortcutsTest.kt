// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KeyboardShortcutsTest {
    private fun resolve(key: ShortcutKey, context: ShortcutContext, mac: Boolean = false,
                        control: Boolean = !mac, meta: Boolean = mac, alt: Boolean = false,
                        shift: Boolean = false, down: Boolean = true) =
        keyboardShortcut(key, down, control, meta, alt, shift, mac, context)

    @Test fun `control on Windows Linux and command on macOS select expected actions`() {
        listOf(false, true).forEach { mac ->
            val list = ShortcutContext(unlocked = true, busy = false, editing = false)
            val editor = list.copy(editing = true)
            assertEquals(ShortcutAction.LOCK, resolve(ShortcutKey.L, list, mac))
            assertEquals(ShortcutAction.NEW_ENTRY, resolve(ShortcutKey.N, list, mac))
            assertEquals(ShortcutAction.SEARCH, resolve(ShortcutKey.F, list, mac))
            assertEquals(ShortcutAction.SAVE, resolve(ShortcutKey.S, editor, mac))
            assertNull(resolve(ShortcutKey.N, list, mac, control = mac, meta = !mac))
        }
    }

    @Test fun `wrong modifiers key releases and ordinary input are not intercepted`() {
        val state = ShortcutContext(true, false, false)
        assertNull(resolve(ShortcutKey.N, state, control = false))
        assertNull(resolve(ShortcutKey.N, state, meta = true))
        assertNull(resolve(ShortcutKey.N, state, alt = true))
        assertNull(resolve(ShortcutKey.N, state, shift = true))
        assertNull(resolve(ShortcutKey.N, state, down = false))
        assertNull(resolve(ShortcutKey.OTHER, state))
    }

    @Test fun `locked busy and modal states reject editing actions while lock remains available`() {
        listOf(ShortcutContext(false, false, false), ShortcutContext(true, true, false),
            ShortcutContext(true, false, false, modal = true)).forEach { state ->
            assertNull(resolve(ShortcutKey.N, state))
            assertNull(resolve(ShortcutKey.F, state))
            assertNull(resolve(ShortcutKey.S, state.copy(editing = true)))
        }
        assertEquals(ShortcutAction.LOCK, resolve(ShortcutKey.L, ShortcutContext(false, true, false)))
        assertEquals(ShortcutAction.LOCK, resolve(ShortcutKey.L, ShortcutContext(true, true, true)))
        assertNull(resolve(ShortcutKey.L, ShortcutContext(true, true, true, locking = true)))
        assertNull(resolve(ShortcutKey.L, ShortcutContext(false, false, false)))
    }

    @Test fun `navigation never replaces an active editor and save requires an editor`() {
        val editor = ShortcutContext(true, false, true)
        assertNull(resolve(ShortcutKey.N, editor))
        assertNull(resolve(ShortcutKey.F, editor))
        assertNull(resolve(ShortcutKey.S, editor.copy(editing = false)))
    }

    @Test fun `escape only requests cancellation for an idle editor without modifiers`() {
        val editor = ShortcutContext(true, false, true)
        assertEquals(ShortcutAction.CANCEL, resolve(ShortcutKey.ESCAPE, editor, control = false))
        assertNull(resolve(ShortcutKey.ESCAPE, editor))
        assertNull(resolve(ShortcutKey.ESCAPE, editor.copy(busy = true), control = false))
        assertNull(resolve(ShortcutKey.ESCAPE, editor.copy(modal = true), control = false))
        assertNull(resolve(ShortcutKey.ESCAPE, editor.copy(editing = false), control = false))
        assertNull(resolve(ShortcutKey.ESCAPE, editor.copy(unlocked = false), control = false))
    }

    private val list = ShortcutContext(true, false, false, focus = ShortcutFocus.LIST, selection = true)

    @Test fun `entry shortcuts act on the selected entry only while the list has focus`() {
        listOf(false, true).forEach { mac ->
            assertEquals(ShortcutAction.COPY_PASSWORD, resolve(ShortcutKey.C, list, mac))
            assertEquals(ShortcutAction.COPY_USERNAME, resolve(ShortcutKey.B, list, mac))
            assertEquals(ShortcutAction.COPY_TOTP, resolve(ShortcutKey.T, list, mac))
            assertEquals(ShortcutAction.OPEN_URL, resolve(ShortcutKey.U, list, mac))
            assertEquals(ShortcutAction.EDIT_ENTRY, resolve(ShortcutKey.E, list, mac))
            assertEquals(ShortcutAction.EDIT_ENTRY, resolve(ShortcutKey.ENTER, list, mac, control = false, meta = false))
            assertEquals(ShortcutAction.TRASH_ENTRY, resolve(ShortcutKey.DELETE, list, mac, control = false, meta = false))
            assertEquals(ShortcutAction.SELECT_PREVIOUS, resolve(ShortcutKey.UP, list, mac, control = false, meta = false))
            assertEquals(ShortcutAction.SELECT_NEXT, resolve(ShortcutKey.DOWN, list, mac, control = false, meta = false))
            assertEquals(ShortcutAction.SELECT_FIRST, resolve(ShortcutKey.HOME, list, mac, control = false, meta = false))
            assertEquals(ShortcutAction.SELECT_LAST, resolve(ShortcutKey.END, list, mac, control = false, meta = false))
            assertNull(resolve(ShortcutKey.C, list, mac, control = mac, meta = !mac))
            assertNull(resolve(ShortcutKey.C, list, mac, shift = true))
            assertNull(resolve(ShortcutKey.DELETE, list, mac))
            assertNull(resolve(ShortcutKey.ENTER, list, mac, control = false, meta = false, down = false))
            // Global shortcuts still resolve with list focus.
            assertEquals(ShortcutAction.NEW_ENTRY, resolve(ShortcutKey.N, list, mac))
            assertEquals(ShortcutAction.SEARCH, resolve(ShortcutKey.F, list, mac))
        }
        assertEquals(ShortcutAction.TRASH_ENTRY, resolve(ShortcutKey.BACKSPACE, list, mac = true, control = false, meta = false))
        assertNull(resolve(ShortcutKey.BACKSPACE, list, control = false))
    }

    @Test fun `text fields keep copy delete arrows home end and enter for editing`() {
        listOf(ShortcutFocus.TEXT, ShortcutFocus.SEARCH, ShortcutFocus.OTHER).forEach { focus ->
            val state = list.copy(focus = focus)
            listOf(ShortcutKey.C, ShortcutKey.B, ShortcutKey.T, ShortcutKey.U, ShortcutKey.E).forEach { key ->
                assertNull(resolve(key, state), "$focus $key")
            }
            listOf(ShortcutKey.UP, ShortcutKey.HOME, ShortcutKey.END, ShortcutKey.ENTER, ShortcutKey.DELETE,
                ShortcutKey.BACKSPACE).forEach { key ->
                assertNull(resolve(key, state, control = false), "$focus $key")
                assertNull(resolve(key, state, mac = true, meta = false), "$focus $key mac")
            }
            assertEquals(ShortcutAction.SEARCH, resolve(ShortcutKey.F, state))
        }
        val search = list.copy(focus = ShortcutFocus.SEARCH)
        assertEquals(ShortcutAction.FOCUS_LIST, resolve(ShortcutKey.DOWN, search, control = false))
        assertNull(resolve(ShortcutKey.DOWN, list.copy(focus = ShortcutFocus.TEXT), control = false))
        assertNull(resolve(ShortcutKey.DOWN, list.copy(focus = ShortcutFocus.OTHER), control = false))
        assertNull(resolve(ShortcutKey.DOWN, search, control = false, shift = true))
    }

    @Test fun `entry actions need a selection an active list and an idle unlocked vault`() {
        val entryKeys = listOf(ShortcutKey.C, ShortcutKey.B, ShortcutKey.T, ShortcutKey.U, ShortcutKey.E)
        listOf(list.copy(selection = false), list.copy(trash = true), list.copy(busy = true), list.copy(modal = true),
            list.copy(editing = true), list.copy(unlocked = false)).forEach { state ->
            entryKeys.forEach { assertNull(resolve(it, state), "$state $it") }
            assertNull(resolve(ShortcutKey.ENTER, state, control = false))
            assertNull(resolve(ShortcutKey.DELETE, state, control = false))
        }
        // Navigation works without a selection and in the trash, but not while busy, modal or editing.
        assertEquals(ShortcutAction.SELECT_NEXT, resolve(ShortcutKey.DOWN, list.copy(selection = false), control = false))
        assertEquals(ShortcutAction.SELECT_LAST, resolve(ShortcutKey.END, list.copy(trash = true), control = false))
        listOf(list.copy(busy = true), list.copy(modal = true), list.copy(editing = true)).forEach { state ->
            assertNull(resolve(ShortcutKey.DOWN, state, control = false))
        }
        assertEquals(ShortcutAction.CANCEL, resolve(ShortcutKey.ESCAPE, list.copy(editing = true), control = false))
    }

    @Test fun `help documents every action with keys that resolve to it`() {
        assertEquals(ShortcutAction.entries.toSet(), ShortcutHelp.entries.flatMap { it.actions }.toSet())
        val contexts = listOf(list, ShortcutContext(true, false, true))
        ShortcutHelp.entries.forEach { row ->
            row.shortcutKey?.let { key ->
                listOf(false, true).forEach { mac ->
                    assertTrue(contexts.any { resolve(key, it, mac) in row.actions }, "${row.name} $mac")
                }
            }
        }
        try {
            listOf(AppLanguage.GERMAN, AppLanguage.ENGLISH).forEach { language ->
                UiText.select(language)
                ShortcutHelp.entries.forEach { row ->
                    listOf(false, true).forEach { mac -> assertTrue(row.keys(mac).isNotBlank(), row.name) }
                    assertTrue(row.text.isNotBlank(), row.name)
                }
                assertTrue(UiText.text("shortcuts.textRule").isNotBlank())
            }
            UiText.select(AppLanguage.ENGLISH)
            assertEquals("Ctrl+C", ShortcutHelp.COPY_PASSWORD.keys(mac = false))
            assertEquals("⌘C", ShortcutHelp.COPY_PASSWORD.keys(mac = true))
        } finally { UiText.select(AppLanguage.GERMAN) }
    }
}
