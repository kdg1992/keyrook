// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.keyrook.core.model.Vault
import app.keyrook.core.security.EntryHealth
import app.keyrook.core.security.HealthIssue
import app.keyrook.core.security.VaultHealth
import javax.swing.SwingUtilities

@Composable
internal fun HealthTools(vault: Vault, controller: VaultController, busy: Boolean, operation: (() -> Vault?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var findings by remember { mutableStateOf<List<EntryHealth>>(emptyList()) }
    TextButton(enabled = !busy, onClick = {
        val token = controller.sessionEpoch.capture()
        operation {
            ensureOperationCurrent()
            val result = controller.session.snapshot().use { VaultHealth().inspect(it) }
            SwingUtilities.invokeLater {
                if (controller.sessionEpoch.accepts(token)) { findings = result; open = true }
            }
            controller.session.snapshot()
        }
    }) { Text("Warnliste") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("Zugänge prüfen") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lokale Hinweise: Ablauf innerhalb von 30 Tagen, kurze/einförmige und wiederverwendete Passwörter. Keine Garantie für Passwortstärke.")
            if (findings.isEmpty()) Text("Keine Hinweise nach diesen Kriterien.")
            for (finding in findings) {
                Text(vault.entries.firstOrNull { it.id == finding.entryId }?.title.orEmpty(), style = MaterialTheme.typography.subtitle1)
                Text(finding.issues.joinToString("; ") { issue -> when (issue) {
                    HealthIssue.EXPIRED -> "abgelaufen"
                    HealthIssue.EXPIRING_SOON -> "läuft bald ab"
                    HealthIssue.SHORT_OR_REPETITIVE_PASSWORD -> "Passwort kurz oder einförmig"
                    HealthIssue.REUSED_PASSWORD -> "Passwort mehrfach verwendet"
                } })
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text("Schließen") } })
}
