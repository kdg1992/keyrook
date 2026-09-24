// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.runtime.*
import app.keyrook.core.model.Vault
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/** Quiet time after a changed query before the vault is scanned, so a burst of keystrokes causes one scan. */
internal const val SEARCH_DEBOUNCE_MILLIS = 200L

/** Only a changed, non-blank query waits; a new revision or option, the first and a cleared query scan at once. */
internal fun searchDelayMillis(previousQuery: String?, query: String): Long =
    if (previousQuery == null || previousQuery == query || query.isBlank()) 0 else SEARCH_DEBOUNCE_MILLIS

/** Runs only the latest submission; submitting again or [cancel] drops a task that has not started yet. */
internal class Debouncer(private val scheduler: ScheduledExecutorService) {
    private var pending: Future<*>? = null
    @Synchronized fun submit(delayMillis: Long, task: () -> Unit) {
        pending?.cancel(false)
        pending = scheduler.schedule(Runnable(task), delayMillis, TimeUnit.MILLISECONDS)
    }
    @Synchronized fun cancel() { pending?.cancel(false); pending = null }
}

@Composable
internal fun searchResults(vault: Vault, controller: VaultController, query: String, includeHidden: Boolean): Set<String>? {
    val worker = remember { Executors.newSingleThreadScheduledExecutor { Thread(it, "vault-search").apply { isDaemon = true } } }
    val debouncer = remember { Debouncer(worker) }
    val lastQuery = remember { AtomicReference<String?>(null) }
    // Type and field labels are searchable, so results follow the selected language.
    val locale = UiText.locale
    var result by remember(vault, query, includeHidden, locale) { mutableStateOf<Set<String>?>(null) }
    DisposableEffect(Unit) { onDispose { debouncer.cancel(); worker.shutdown() } }
    DisposableEffect(vault, query, includeHidden, locale) {
        val active = AtomicBoolean(true)
        val token = controller.sessionEpoch.capture()
        debouncer.submit(searchDelayMillis(lastQuery.getAndSet(query), query)) {
            // Scans a session copy that is erased afterwards; UI lock may close the displayed copy meanwhile.
            val matches = runCatching {
                if (!active.get()) emptySet() else controller.read { current ->
                    if (current.id != vault.id || current.revision != vault.revision) emptySet()
                    else VaultSearch.find(current, query, includeHidden, locale) { !active.get() }
                }
            }
            SwingUtilities.invokeLater {
                if (active.get() && controller.sessionEpoch.accepts(token)) result = matches.getOrDefault(emptySet())
            }
        }
        onDispose { active.set(false) }
    }
    return result
}
