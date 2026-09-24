// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import app.keyrook.core.model.EntryData
import app.keyrook.core.model.Field
import app.keyrook.core.model.FieldKind
import app.keyrook.core.otp.Totp
import java.awt.Desktop
import java.net.URI
import java.time.Instant

internal enum class QuickField { USERNAME, SECRET, TOTP, URL }

/** Selection follows field semantics, never translated or custom labels. */
internal fun EntryData.quickField(kind: QuickField): Field? = when (kind) {
    QuickField.USERNAME -> when (this) {
        is EntryData.Web -> username
        is EntryData.Transfer -> username
        is EntryData.Email -> username
        is EntryData.Panel -> username
        is EntryData.Server -> username
        is EntryData.Ssh, is EntryData.Domain, is EntryData.Custom -> null
    }
    // SSH offers the passphrase; the private key itself is only copied from the editor.
    QuickField.SECRET -> when (this) {
        is EntryData.Web -> password
        is EntryData.Transfer -> password
        is EntryData.Email -> password
        is EntryData.Panel -> password
        is EntryData.Server -> password
        is EntryData.Ssh -> passphrase
        is EntryData.Domain -> null
        is EntryData.Custom -> values.values.firstOrNull { it.hidden }
    }
    // Copies the current one-time code computed from the stored secret, never the secret itself.
    QuickField.TOTP -> (this as? EntryData.Web)?.totp
    QuickField.URL -> when (this) {
        is EntryData.Web -> url
        is EntryData.Panel -> url
        is EntryData.Custom -> values.values.firstOrNull { it.kind == FieldKind.URL }
        else -> null
    }
}

internal fun EntryData.quickLabel(field: Field): String = labels()[fields().indexOfFirst { it === field }]

/** Values are read only for an explicit action and are never rendered in the list. */
internal object EntryQuickActions {
    fun available(field: Field): Boolean = runCatching { field.value.useChars { it.isNotEmpty() } }.getOrDefault(false)

    /** A TOTP action is offered only for a stored value the core parser accepts. */
    fun available(field: Field, kind: QuickField): Boolean =
        if (kind == QuickField.TOTP) runCatching { Totp.isValid(field.value) }.getOrDefault(false) else available(field)

    /** Uses the owned, expiring clipboard; the parameter only exists to substitute a guarded test clipboard. */
    fun copy(field: Field, clipboard: (String) -> Unit = SecretClipboard::copy) {
        field.value.useChars { chars -> clipboard(String(chars)) }
    }

    /**
     * Computes the code for [now] from the stored secret and copies only the code; the decoded key is erased inside
     * the core and the code container is closed here. Returns the whole seconds the copied code remains valid.
     */
    fun copyTotp(field: Field, now: Instant = Instant.now(), clipboard: (String) -> Unit = SecretClipboard::copy): Long =
        Totp.code(field.value, now).use { code ->
            code.code.useChars { chars -> clipboard(String(chars)) }
            code.remainingSeconds(now)
        }

    /** The list's TOTP quick action: copies the current code and names its remaining validity, or why it cannot. */
    fun copyTotpNotice(data: EntryData, now: Instant = Instant.now(), clipboard: (String) -> Unit = SecretClipboard::copy): String {
        val field = data.quickField(QuickField.TOTP)
        return when {
            field == null || !available(field) -> UiText.text("list.noQuickField")
            !available(field, QuickField.TOTP) -> UiText.text("totp.invalid")
            else -> runCatching { copyTotp(field, now, clipboard) }
                .fold({ UiText.text("list.totpCopied", it) }, { UiText.text("list.copyFailed") })
        }
    }

    fun open(field: Field, browse: (URI) -> Unit = { Desktop.getDesktop().browse(it) }) {
        browse(field.value.useChars { chars -> BrowserLinks.parse(String(chars)) })
    }
}

internal sealed interface ListConfirmation {
    data class Trash(val id: String, val title: String) : ListConfirmation
    data class Purge(val id: String, val title: String) : ListConfirmation
    data class EmptyTrash(val count: Int) : ListConfirmation
    /** Moves the marked entries [ids] to the trash in one save. */
    data class TrashMarked(val ids: Set<String>) : ListConfirmation
}

/**
 * The confirmation of a destructive action, named by [confirmLabel]; [dismissLabel] names the way back. Escape
 * cancels. Enter activates a focused button; without such focus it confirms only reversible actions. Irreversible
 * confirmations use the error color and start focused on cancel.
 */
@Composable
internal fun ConfirmationDialog(title: String, body: String, confirmLabel: String, busy: Boolean, irreversible: Boolean,
                                onConfirm: () -> Unit, onDismiss: () -> Unit,
                                dismissLabel: String = UiText.text("common.cancel")) {
    val confirmFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    var confirmFocused by remember { mutableStateOf(false) }
    var cancelFocused by remember { mutableStateOf(false) }
    val latestConfirm by rememberUpdatedState({ if (!busy) onConfirm() })
    val latestDismiss by rememberUpdatedState(onDismiss)
    AlertDialog(
        onDismissRequest = { latestDismiss() },
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false
            else when (event.key) {
                Key.Escape -> { latestDismiss(); true }
                Key.Enter, Key.NumPadEnter -> when {
                    cancelFocused -> { latestDismiss(); true }
                    confirmFocused || !irreversible -> { latestConfirm(); true }
                    else -> false
                }
                else -> false
            }
        },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Button(onClick = { latestConfirm() }, enabled = !busy,
                modifier = Modifier.focusRequester(confirmFocus).onFocusChanged { confirmFocused = it.isFocused },
                colors = if (irreversible) ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error,
                    contentColor = MaterialTheme.colors.onError) else ButtonDefaults.buttonColors()) { Text(confirmLabel) }
            if (!irreversible) LaunchedEffect(Unit) { runCatching { confirmFocus.requestFocus() } }
        },
        dismissButton = {
            TextButton(onClick = { latestDismiss() },
                modifier = Modifier.focusRequester(cancelFocus).onFocusChanged { cancelFocused = it.isFocused }) {
                Text(dismissLabel)
            }
            if (irreversible) LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
        },
    )
}
