// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

internal enum class ShortcutKey { L, N, F, S, ESCAPE, OTHER }
internal enum class ShortcutAction { LOCK, NEW_ENTRY, SEARCH, SAVE, CANCEL }
internal data class ShortcutContext(val unlocked: Boolean, val busy: Boolean, val editing: Boolean,
                                    val locking: Boolean = false, val modal: Boolean = false)

/** Exact modifiers avoid intercepting text input, AltGr, OS shortcuts or key releases. */
internal fun keyboardShortcut(key: ShortcutKey, keyDown: Boolean, control: Boolean, meta: Boolean,
                              alt: Boolean, shift: Boolean, mac: Boolean, context: ShortcutContext): ShortcutAction? {
    if (!keyDown || alt || shift) return null
    if (key == ShortcutKey.ESCAPE && !control && !meta) {
        return if (context.unlocked && context.editing && !context.busy && !context.modal) ShortcutAction.CANCEL else null
    }
    val primary = if (mac) meta && !control else control && !meta
    if (!primary) return null
    if (key == ShortcutKey.L) return if (!context.locking && (context.unlocked || context.busy)) ShortcutAction.LOCK else null
    if (!context.unlocked || context.busy || context.modal) return null
    return when (key) {
        ShortcutKey.N -> if (!context.editing) ShortcutAction.NEW_ENTRY else null
        ShortcutKey.F -> if (!context.editing) ShortcutAction.SEARCH else null
        ShortcutKey.S -> if (context.editing) ShortcutAction.SAVE else null
        else -> null
    }
}

/** Installed handlers live only as long as their editor/list composition. Access is on the UI thread. */
internal class ShortcutActions {
    var newEntry: (() -> Unit)? = null
    var save: (() -> Unit)? = null
    var cancel: (() -> Unit)? = null
    var search: (() -> Unit)? = null
}

/** Receives only this JVM's key events, including its Swing/Compose modal dialogs. */
internal class LockShortcutDispatcher(mac: Boolean, context: () -> ShortcutContext, lock: () -> Unit) : AutoCloseable {
    private val manager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
    private val dispatcher = java.awt.KeyEventDispatcher { event ->
        val action = keyboardShortcut(
            if (event.keyCode == java.awt.event.KeyEvent.VK_L) ShortcutKey.L else ShortcutKey.OTHER,
            event.id == java.awt.event.KeyEvent.KEY_PRESSED, event.isControlDown, event.isMetaDown,
            event.isAltDown, event.isShiftDown, mac, context())
        if (action == ShortcutAction.LOCK) { lock(); true } else false
    }
    init { manager.addKeyEventDispatcher(dispatcher) }
    override fun close() { manager.removeKeyEventDispatcher(dispatcher) }
}
