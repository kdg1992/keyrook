// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.keyrook.core.model.Vault
import app.keyrook.core.security.EntryHealth
import app.keyrook.core.security.HealthIssue
import app.keyrook.core.security.VaultHealth
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * Core health findings for the displayed vault revision, or null while locked or while the check runs. The check
 * runs once per vault revision and day on its own worker thread, never on the UI thread, on a session copy taken
 * without serializing secrets and erased afterwards. Results from an older revision or a previous session are discarded.
 */
@Composable
internal fun vaultWarnings(vault: Vault?, controller: VaultController): List<EntryHealth>? {
    val worker = remember { Executors.newSingleThreadExecutor { Thread(it, "vault-health").apply { isDaemon = true } } }
    DisposableEffect(Unit) { onDispose { worker.shutdown() } }
    val today by produceState(LocalDate.now()) {
        while (true) { kotlinx.coroutines.delay(60_000); value = LocalDate.now() }
    }
    val id = vault?.id
    val revision = vault?.revision
    var result by remember(id, revision, today) { mutableStateOf<List<EntryHealth>?>(null) }
    DisposableEffect(id, revision, today) {
        val active = AtomicBoolean(id != null)
        val token = controller.sessionEpoch.capture()
        val task = if (id == null) null else worker.submit {
            val findings = runCatching {
                if (!active.get()) null else controller.read { current ->
                    if (current.id != id || current.revision != revision) null else VaultHealth().inspect(current)
                }
            }.getOrNull()
            SwingUtilities.invokeLater {
                if (findings != null && active.get() && controller.sessionEpoch.accepts(token)) result = findings
            }
        }
        onDispose { active.set(false); task?.cancel(false) }
    }
    return if (id == null) null else result
}

/** A small colored label; [severe] uses the error color like the expiry badge of expired entries. */
@Composable
internal fun WarningChip(text: String, severe: Boolean) {
    val colors = MaterialTheme.colors
    Surface(color = if (severe) colors.error else colors.secondary,
        contentColor = if (severe) colors.onError else colors.onSecondary, shape = RoundedCornerShape(4.dp)) {
        Text(text, style = MaterialTheme.typography.caption, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** Header button of the warning list with the number of affected entries per reason. */
@Composable
internal fun WarningsBadge(findings: List<EntryHealth>?, onClick: () -> Unit) {
    val counts = remember(findings) { findings?.let(::warningCounts) }
    TextButton(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(UiText.text("health.list"))
            when {
                counts == null -> Text(UiText.text("health.pending"), style = MaterialTheme.typography.caption)
                counts.entries == 0 -> Text(UiText.text("health.none"), style = MaterialTheme.typography.caption)
                else -> HealthIssue.entries.forEach { issue ->
                    val count = counts.count(issue)
                    if (count > 0) WarningChip(UiText.text("health.badge.${issue.name.lowercase()}", count),
                        severe = severeIssue(issue))
                }
            }
        }
    }
}
