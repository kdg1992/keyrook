// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.keyrook.core.model.Entry
import app.keyrook.core.model.EntryTemplate
import app.keyrook.core.model.Vault
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

/**
 * App-level state of the main window: the presented vault snapshot, progress and messages, the screen state that
 * locking resets, and the single vault worker with the operations that run on it. Used on the UI thread only; one
 * instance lives as long as the window's composition, and [dispose] ends it.
 */
internal class AppState {
    val controller = VaultController()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "vault-worker").apply { isDaemon = true } }
    val dialogs = DialogHost()
    // Optional breach check results; memory only and discarded on lock (see BreachChecks).
    val breaches = BreachChecks()
    var vault by mutableStateOf<Vault?>(null)
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    // Visible information that is not an error, such as a restored backup configuration; cleared on dismiss or lock.
    var notice by mutableStateOf("")
    var about by mutableStateOf(false)
    var editing by mutableStateOf<Entry?>(null)
    var creating by mutableStateOf(false)
    // The template a new entry starts from while [creating]; null for a blank entry.
    var template by mutableStateOf<EntryTemplate?>(null)
    var locking by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    // List view, selection and shown detail values live here so they survive the editor; lock resets them.
    var listView by mutableStateOf(ListView())
    var selection by mutableStateOf(EntrySelection())
    var reveal by mutableStateOf(RevealState())
    var organizer by mutableStateOf(false)
    // Recently used entries of the open vault; memory only and reset on lock (see RecentEntries).
    var recent by mutableStateOf(RecentEntries())
    var warningsOpen by mutableStateOf(false)
    var confirmClose by mutableStateOf(false)
    val inactivity = InactivityDeadline()
    val live = java.util.concurrent.atomic.AtomicBoolean(true)

    fun lockNow(onCloseAnswered: (quit: Boolean) -> Unit) {
        if (locking || (vault == null && !busy)) return
        locking = true
        val token = controller.sessionEpoch.invalidate()
        // After invalidating: a worker's dialog request is either canceled here or refused by its guard.
        dialogs.cancelAll()
        java.awt.Window.getWindows().filterIsInstance<java.awt.Dialog>().filter { it.isVisible }.forEach { it.dispose() }
        editing = null; creating = false; template = null; about = false; showSettings = false
        if (confirmClose) { confirmClose = false; onCloseAnswered(false) }
        reveal = RevealState(); listView = ListView(); selection = EntrySelection(); organizer = false; warningsOpen = false
        recent = RecentEntries()
        breaches.clear()
        vault?.close(); vault = null
        runCatching { SecretClipboard.clear() }
        busy = true
        message = UiText.text("shell.locked")
        notice = ""
        worker.execute {
            controller.lock()
            SwingUtilities.invokeLater {
                if (live.get() && controller.sessionEpoch.accepts(token)) { locking = false; busy = false }
            }
        }
    }
    /** Records that the entry [id] of the open vault was opened in the editor, copied from or had its link opened. */
    fun used(id: String) {
        val open = vault ?: return
        recent = recent.used(open.id, id)
    }

    /**
     * Runs [action] on the vault worker and presents the vault it returns. [onSuccess] runs on the UI thread only when
     * the action succeeded in the current session, so a dialog that starts an operation can stay open with its input
     * when it fails. Nothing runs while another operation is busy.
     */
    fun operation(onSuccess: () -> Unit = {}, action: () -> Vault?) {
        if (busy) return
        busy = true
        message = ""
        val token = controller.sessionEpoch.capture()
        worker.execute {
            val result = runCatching { withOperationGuard(controller, token) { action() } }
            // A save stays successful when old backups cannot be removed; that is only reported as a notice.
            val rotation = runCatching { rotationNotice(controller) }.getOrNull()
            // The first save of a migrated vault names the kept copy of the old file once.
            val migrated = runCatching { migrationCopyNotice(controller) }.getOrNull()
            SwingUtilities.invokeLater {
                if (!live.get()) { result.getOrNull()?.close() }
                else controller.sessionEpoch.deliver(token, result.getOrNull()) { snapshot ->
                    listOfNotNull(rotation, migrated).takeIf { it.isNotEmpty() }?.let { notice = it.joinToString("\n") }
                    if (result.isSuccess) {
                        vault?.close()
                        vault = snapshot
                        editing = null
                        creating = false
                        inactivity.activity()
                        onSuccess()
                    } else {
                        // Only the reasons of refused checks are shown, never the messages of other exceptions.
                        message = failureMessage(result.exceptionOrNull())
                    }
                    busy = false
                }
            }
        }
    }

    /** Ends the window: late results are discarded, dialogs closed and the session closed on the worker. */
    fun dispose() {
        live.set(false)
        controller.sessionEpoch.invalidate()
        breaches.clear()
        dialogs.close()
        runCatching { SecretClipboard.clear() }
        vault?.close()
        worker.execute { controller.close() }
        worker.shutdown()
    }
}
