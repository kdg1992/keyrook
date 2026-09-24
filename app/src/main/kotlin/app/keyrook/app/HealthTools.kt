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

internal fun healthIssueText(issue: HealthIssue): String = when (issue) {
    HealthIssue.EXPIRED -> UiText.text("health.expired")
    HealthIssue.EXPIRING_SOON -> UiText.text("health.expiring")
    HealthIssue.SHORT_OR_REPETITIVE_PASSWORD -> UiText.text("health.weak")
    HealthIssue.REUSED_PASSWORD -> UiText.text("health.reused")
}

internal fun warningSummaryText(counts: WarningCounts): String =
    UiText.text("health.summary", counts.entries, counts.expired, counts.expiringSoon, counts.weak, counts.reused)

/**
 * The warning list. [findings] are the latest background results of core health checks (null while they run);
 * the dialog shows titles and reasons only. With [onSelect], each title selects its entry in the list.
 */
@Composable
internal fun HealthDialog(vault: Vault, findings: List<EntryHealth>?, onSelect: ((String) -> Unit)?, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text(UiText.text("health.title")) }, text = {
        val titles = remember(vault) { vault.entries.associate { it.id to it.title } }
        LazyColumn(Modifier.heightIn(max = 450.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(UiText.text("health.hint")) }
            if (findings == null) item { Text(UiText.text("health.checking")) }
            else {
                item { Text(warningSummaryText(warningCounts(findings)), style = MaterialTheme.typography.subtitle2) }
                if (findings.isEmpty()) item { Text(UiText.text("health.empty")) }
                else if (onSelect != null) item { Text(UiText.text("health.selectHint"), style = MaterialTheme.typography.caption) }
                items(findings, key = { it.entryId }) { finding ->
                    Column {
                        TextButton(enabled = onSelect != null, onClick = { onSelect?.invoke(finding.entryId) }) {
                            Text(titles[finding.entryId].orEmpty(), style = MaterialTheme.typography.subtitle1)
                        }
                        Text(finding.issues.joinToString("; ") { healthIssueText(it) }, Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(UiText.text("health.close")) } })
}
