// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import javax.swing.SwingUtilities

/**
 * The application's message, question and input dialogs, rendered by [DialogHostView] inside the main window and
 * its theme. Vault workers call [ask] with their captured operation guard and wait for the answer; locking calls
 * [cancelAll], which dismisses every dialog as canceled.
 */
internal class DialogHost : Dialogs {
    /** The request currently shown; read by the composition, written on the UI thread only. */
    var shown by mutableStateOf<DialogRequest<*>?>(null)
        private set

    private val bridge = DialogBridge(changed = { refresh() }, uiThread = SwingUtilities::isEventDispatchThread)

    override fun <T : Any> ask(request: DialogRequest<T>): T? = bridge.ask(request, capturedOperationGuard())

    /** Called by the dialog's buttons; an answer for a request that is no longer current is discarded. */
    fun <T : Any> answer(request: DialogRequest<T>, value: T?) { bridge.answer(request, value) }

    fun cancelAll() = bridge.cancelAll()

    fun close() = bridge.close()

    private fun refresh() {
        if (SwingUtilities.isEventDispatchThread()) shown = bridge.current
        else SwingUtilities.invokeLater { shown = bridge.current }
    }
}
