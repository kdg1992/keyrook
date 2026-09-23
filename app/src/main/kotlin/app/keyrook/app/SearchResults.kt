// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.runtime.*
import app.keyrook.core.model.Vault
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

@Composable
internal fun searchResults(vault: Vault, controller: VaultController, query: String, includeHidden: Boolean): Set<String>? {
    val worker = remember { Executors.newSingleThreadExecutor { Thread(it, "vault-search").apply { isDaemon = true } } }
    var result by remember(vault, query, includeHidden) { mutableStateOf<Set<String>?>(null) }
    DisposableEffect(Unit) { onDispose { worker.shutdown() } }
    DisposableEffect(vault, query, includeHidden) {
        val active = AtomicBoolean(true)
        val task = worker.submit {
            // Own a session snapshot; UI lock may close its separate copy while this scan is running.
            val matches = runCatching {
                if (!active.get()) emptySet() else controller.session.snapshot().use { snapshot ->
                    if (snapshot.id != vault.id || snapshot.revision != vault.revision) emptySet()
                    else VaultSearch.find(snapshot, query, includeHidden) { !active.get() }
                }
            }
            SwingUtilities.invokeLater { if (active.get()) result = matches.getOrDefault(emptySet()) }
        }
        onDispose { active.set(false); task.cancel(false) }
    }
    return result
}
