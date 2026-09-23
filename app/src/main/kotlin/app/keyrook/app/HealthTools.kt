// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    }) { Text(UiText.text("health.list")) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(UiText.text("health.title")) }, text = {
        val titles = remember(vault) { vault.entries.associate { it.id to it.title } }
        LazyColumn(Modifier.heightIn(max = 450.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(UiText.text("health.hint")) }
            if (findings.isEmpty()) item { Text(UiText.text("health.empty")) }
            items(findings, key = { it.entryId }) { finding ->
                Text(titles[finding.entryId].orEmpty(), style = MaterialTheme.typography.subtitle1)
                Text(finding.issues.joinToString("; ") { issue -> when (issue) {
                    HealthIssue.EXPIRED -> UiText.text("health.expired")
                    HealthIssue.EXPIRING_SOON -> UiText.text("health.expiring")
                    HealthIssue.SHORT_OR_REPETITIVE_PASSWORD -> UiText.text("health.weak")
                    HealthIssue.REUSED_PASSWORD -> UiText.text("health.reused")
                } })
            }
        }
    }, confirmButton = { TextButton(onClick = { open = false }) { Text(UiText.text("health.close")) } })
}
