// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp

/**
 * Enter in a text field submits the form, like the default button of a system dialog. [submit] applies the same
 * checks as the form's button, so Enter never submits what the button would refuse.
 */
internal fun Modifier.submitOnEnter(submit: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) { submit(); true }
    else false
}

/** A focus requester that takes the focus once when the calling composable is first shown, for a dialog's first field. */
@Composable
internal fun rememberInitialFocus(): FocusRequester {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }
    return focus
}

internal enum class ShortcutKey { L, N, F, S, C, B, U, E, T, A, SPACE, ESCAPE, UP, DOWN, HOME, END, ENTER, DELETE, BACKSPACE, OTHER }
internal enum class ShortcutAction {
    LOCK, NEW_ENTRY, SEARCH, SAVE, CANCEL, FOCUS_LIST, SELECT_PREVIOUS, SELECT_NEXT, SELECT_FIRST, SELECT_LAST,
    COPY_PASSWORD, COPY_USERNAME, COPY_TOTP, OPEN_URL, EDIT_ENTRY, TRASH_ENTRY, TOGGLE_MARK, MARK_ALL,
}

/**
 * Where keyboard focus is. Entry shortcuts act only with [LIST] focus (the entry list or a button in one of its
 * cards). A focused text field ([TEXT], or [SEARCH] for the list's search field) keeps every key for text editing,
 * so Ctrl/⌘+C copies selected text, Delete/Backspace edit, arrows/Home/End move the caret and Enter stays with the
 * field. The only list key a search field forwards is Down, which a single-line field does not use.
 */
internal enum class ShortcutFocus { OTHER, TEXT, SEARCH, LIST }

internal data class ShortcutContext(val unlocked: Boolean, val busy: Boolean, val editing: Boolean,
                                    val locking: Boolean = false, val modal: Boolean = false,
                                    val focus: ShortcutFocus = ShortcutFocus.OTHER, val selection: Boolean = false,
                                    val trash: Boolean = false)

internal fun shortcutKey(key: Key): ShortcutKey = when (key) {
    Key.L -> ShortcutKey.L
    Key.N -> ShortcutKey.N
    Key.F -> ShortcutKey.F
    Key.S -> ShortcutKey.S
    Key.C -> ShortcutKey.C
    Key.B -> ShortcutKey.B
    Key.U -> ShortcutKey.U
    Key.E -> ShortcutKey.E
    Key.T -> ShortcutKey.T
    Key.A -> ShortcutKey.A
    Key.Spacebar -> ShortcutKey.SPACE
    Key.Escape -> ShortcutKey.ESCAPE
    Key.DirectionUp -> ShortcutKey.UP
    Key.DirectionDown -> ShortcutKey.DOWN
    Key.MoveHome -> ShortcutKey.HOME
    Key.MoveEnd -> ShortcutKey.END
    Key.Enter, Key.NumPadEnter -> ShortcutKey.ENTER
    Key.Delete -> ShortcutKey.DELETE
    Key.Backspace -> ShortcutKey.BACKSPACE
    else -> ShortcutKey.OTHER
}

internal fun keyboardShortcut(event: KeyEvent, mac: Boolean, context: ShortcutContext): ShortcutAction? =
    keyboardShortcut(shortcutKey(event.key), event.type == KeyEventType.KeyDown, event.isCtrlPressed,
        event.isMetaPressed, event.isAltPressed, event.isShiftPressed, mac, context)

/** Exact modifiers avoid intercepting text input, AltGr, OS shortcuts or key releases. */
internal fun keyboardShortcut(key: ShortcutKey, keyDown: Boolean, control: Boolean, meta: Boolean,
                              alt: Boolean, shift: Boolean, mac: Boolean, context: ShortcutContext): ShortcutAction? {
    if (!keyDown || alt || shift) return null
    if (!control && !meta) return unmodifiedShortcut(key, mac, context)
    val primary = if (mac) meta && !control else control && !meta
    if (!primary) return null
    if (key == ShortcutKey.L) return if (!context.locking && (context.unlocked || context.busy)) ShortcutAction.LOCK else null
    if (!context.unlocked || context.busy || context.modal) return null
    return when (key) {
        ShortcutKey.N -> if (!context.editing) ShortcutAction.NEW_ENTRY else null
        ShortcutKey.F -> if (!context.editing) ShortcutAction.SEARCH else null
        ShortcutKey.S -> if (context.editing) ShortcutAction.SAVE else null
        ShortcutKey.C -> context.onSelectedEntry(ShortcutAction.COPY_PASSWORD)
        ShortcutKey.B -> context.onSelectedEntry(ShortcutAction.COPY_USERNAME)
        ShortcutKey.T -> context.onSelectedEntry(ShortcutAction.COPY_TOTP)
        ShortcutKey.U -> context.onSelectedEntry(ShortcutAction.OPEN_URL)
        ShortcutKey.E -> context.onSelectedEntry(ShortcutAction.EDIT_ENTRY)
        // Only with list focus, so Ctrl/⌘+A keeps selecting text in text fields.
        ShortcutKey.A -> if (context.focus == ShortcutFocus.LIST && !context.editing) ShortcutAction.MARK_ALL else null
        else -> null
    }
}

private fun unmodifiedShortcut(key: ShortcutKey, mac: Boolean, context: ShortcutContext): ShortcutAction? {
    if (key == ShortcutKey.ESCAPE) {
        return if (context.unlocked && context.editing && !context.busy && !context.modal) ShortcutAction.CANCEL else null
    }
    if (!context.unlocked || context.busy || context.modal || context.editing) return null
    if (context.focus == ShortcutFocus.SEARCH) return if (key == ShortcutKey.DOWN) ShortcutAction.FOCUS_LIST else null
    if (context.focus != ShortcutFocus.LIST) return null
    return when (key) {
        ShortcutKey.UP -> ShortcutAction.SELECT_PREVIOUS
        ShortcutKey.DOWN -> ShortcutAction.SELECT_NEXT
        ShortcutKey.HOME -> ShortcutAction.SELECT_FIRST
        ShortcutKey.END -> ShortcutAction.SELECT_LAST
        ShortcutKey.ENTER -> context.onSelectedEntry(ShortcutAction.EDIT_ENTRY)
        ShortcutKey.DELETE -> context.onSelectedEntry(ShortcutAction.TRASH_ENTRY)
        // macOS keyboards label Backspace as "delete".
        ShortcutKey.BACKSPACE -> if (mac) context.onSelectedEntry(ShortcutAction.TRASH_ENTRY) else null
        // Marking works in the trash too, for restoring several entries at once.
        ShortcutKey.SPACE -> if (context.selection) ShortcutAction.TOGGLE_MARK else null
        else -> null
    }
}

/** Trashed entries are neither edited, copied nor trashed again from the keyboard. */
private fun ShortcutContext.onSelectedEntry(action: ShortcutAction): ShortcutAction? =
    if (focus == ShortcutFocus.LIST && selection && !trash && !editing) action else null

/** Help rows; every [ShortcutAction] is documented by at least one row. */
internal enum class ShortcutHelp(val actions: Set<ShortcutAction>, private val letter: String?,
                                 private val keyName: String?, private val description: String) {
    LOCK(setOf(ShortcutAction.LOCK), "L", null, "shortcuts.lock"),
    NEW(setOf(ShortcutAction.NEW_ENTRY), "N", null, "shortcuts.new"),
    SEARCH(setOf(ShortcutAction.SEARCH), "F", null, "shortcuts.search"),
    FOCUS_LIST(setOf(ShortcutAction.FOCUS_LIST), null, "shortcuts.key.down", "shortcuts.focusList"),
    MOVE(setOf(ShortcutAction.SELECT_PREVIOUS, ShortcutAction.SELECT_NEXT), null, "shortcuts.key.arrows", "shortcuts.move"),
    FIRST_LAST(setOf(ShortcutAction.SELECT_FIRST, ShortcutAction.SELECT_LAST), null, "shortcuts.key.homeEnd", "shortcuts.firstLast"),
    OPEN(setOf(ShortcutAction.EDIT_ENTRY), null, "shortcuts.key.enter", "shortcuts.open"),
    EDIT(setOf(ShortcutAction.EDIT_ENTRY), "E", null, "shortcuts.edit"),
    COPY_PASSWORD(setOf(ShortcutAction.COPY_PASSWORD), "C", null, "shortcuts.copyPassword"),
    COPY_USERNAME(setOf(ShortcutAction.COPY_USERNAME), "B", null, "shortcuts.copyUsername"),
    COPY_TOTP(setOf(ShortcutAction.COPY_TOTP), "T", null, "shortcuts.copyTotp"),
    OPEN_URL(setOf(ShortcutAction.OPEN_URL), "U", null, "shortcuts.openUrl"),
    TRASH(setOf(ShortcutAction.TRASH_ENTRY), null, "shortcuts.key.delete", "shortcuts.trash"),
    MARK(setOf(ShortcutAction.TOGGLE_MARK), null, "shortcuts.key.space", "shortcuts.mark"),
    MARK_ALL(setOf(ShortcutAction.MARK_ALL), "A", null, "shortcuts.markAll"),
    SAVE(setOf(ShortcutAction.SAVE), "S", null, "shortcuts.save"),
    CANCEL(setOf(ShortcutAction.CANCEL), null, "shortcuts.key.escape", "shortcuts.cancel");

    /** The letter shortcut's key, or null for named keys. */
    val shortcutKey: ShortcutKey? get() = letter?.let(ShortcutKey::valueOf)

    fun keys(mac: Boolean): String = when {
        letter != null -> (if (mac) "⌘" else UiText.text("shell.ctrlPrefix")) + letter
        this == TRASH && mac -> UiText.text("shortcuts.key.deleteMac")
        else -> UiText.text(requireNotNull(keyName))
    }

    val text: String get() = UiText.text(description)
}

@Composable
internal fun ShortcutHelpTable(mac: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(UiText.text("shortcuts.title"), style = MaterialTheme.typography.subtitle1)
        ShortcutHelp.entries.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(row.keys(mac), style = MaterialTheme.typography.body2, modifier = Modifier.width(120.dp))
                Text(row.text, style = MaterialTheme.typography.body2, modifier = Modifier.weight(1f))
            }
        }
        Text(UiText.text("shortcuts.textRule"), style = MaterialTheme.typography.caption)
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
