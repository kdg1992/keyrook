// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.keyrook.core.security.BreachCheck
import app.keyrook.core.security.EntryHealth
import java.awt.Desktop
import java.net.URI

/** The window's dialogs: vault warnings, about, worker questions and the question before quitting during work. */
@Composable
internal fun AppDialogs(state: AppState, warnings: List<EntryHealth>?, breached: Map<String, Long>, updates: UpdateChecks,
                        mac: Boolean, onCloseAnswered: (quit: Boolean) -> Unit) {
    val dialogs = state.dialogs
    val vault by state::vault
    val busy by state::busy
    val creating by state::creating
    val editing by state::editing
    var about by state::about
    var listView by state::listView
    var selection by state::selection
    var warningsOpen by state::warningsOpen
    var confirmClose by state::confirmClose
    val jump: ((String) -> Unit)? = if (busy || creating || editing != null) null else ({ id ->
        warningsOpen = false
        listView = listView.showing(id, selection.visible)
        selection = selection.jump(id)
    })
    val shownVault = vault
    // The consent question replaces the warning list while it is open; the list returns with the progress afterwards.
    val breaches = state.breaches
    if (warningsOpen && shownVault != null && breaches.status !is BreachStatus.Consent) {
        HealthDialog(shownVault, warnings, breached, breaches, onBreachCheck = {
            breaches.prepare { state.controller.read(BreachCheck::plan) }
        }, onSelect = jump) { warningsOpen = false }
    }
    BreachConsentDialog(breaches)
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("Keyrook") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(UiText.text("shell.aboutBody", System.getProperty("keyrook.version", "dev")))
                UpdateCheckPanel(updates)
                ShortcutHelpTable(mac)
            }
        },
        confirmButton = { TextButton(onClick = { about = false }) { Text(UiText.text("shell.close")) } },
        dismissButton = { TextButton(onClick = {
            runCatching { Desktop.getDesktop().browse(URI("https://github.com/kdg1992/keyrook")) }
        }) { Text(UiText.text("shell.source")) } })
    DialogHostView(dialogs)
    if (confirmClose) ConfirmationDialog(UiText.text("shell.closeBusyTitle"), UiText.text("shell.closeBusyBody"),
        UiText.text("shell.closeBusyConfirm"), busy = false, irreversible = true,
        onConfirm = { confirmClose = false; onCloseAnswered(true) },
        onDismiss = { confirmClose = false; onCloseAnswered(false) })
}
