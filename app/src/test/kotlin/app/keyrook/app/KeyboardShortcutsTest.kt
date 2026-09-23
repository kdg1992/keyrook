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
}
