// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.runtime.*
import app.keyrook.core.model.Vault
import java.util.Locale
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

/** Runs a task after a delay; returns a handle that cancels it while it has not started. */
internal fun interface DelayScheduler {
    fun schedule(delayMillis: Long, task: Runnable): Future<*>
}

/** Runs only the latest submission; submitting again or [cancel] drops a task that has not started yet. */
internal class Debouncer(private val scheduler: DelayScheduler) {
    constructor(executor: ScheduledExecutorService) :
        this(DelayScheduler { delayMillis, task -> executor.schedule(task, delayMillis, TimeUnit.MILLISECONDS) })

    private var pending: Future<*>? = null
    @Synchronized fun submit(delayMillis: Long, task: () -> Unit) {
        pending?.cancel(false)
        pending = scheduler.schedule(delayMillis, Runnable(task))
    }
    @Synchronized fun cancel() { pending?.cancel(false); pending = null }
}

/** What a search result answers: the vault and the search, but not its revision (see [LatestResult]). */
internal data class SearchKey(val vaultId: String, val query: String, val includeHidden: Boolean, val locale: Locale)

/**
 * The latest [value] computed for [key]. A newer vault revision is scanned or checked again in the background; until
 * that finishes, the previous value stays shown for the same key instead of an empty or pending state, so a save
 * neither empties the list nor loses its scroll position. A different key shows nothing until its own result arrives.
 */
internal data class LatestResult<K, V>(val key: K, val value: V)

internal fun <K, V> LatestResult<K, V>?.valueFor(key: K): V? = this?.takeIf { it.key == key }?.value

/**
 * IDs of the entries of [vault] that match [query], or null until the first scan for this search finishes. While a
 * newer revision is rescanned, the results of the previous revision remain (see [LatestResult]).
 */
@Composable
internal fun searchResults(vault: Vault, controller: VaultController, query: String, includeHidden: Boolean): Set<String>? {
    val worker = remember { Executors.newSingleThreadScheduledExecutor { Thread(it, "vault-search").apply { isDaemon = true } } }
    val debouncer = remember { Debouncer(worker) }
    val lastQuery = remember { AtomicReference<String?>(null) }
    // Type and field labels are searchable, so results follow the selected language.
    val locale = UiText.locale
    val key = SearchKey(vault.id, query, includeHidden, locale)
    var result by remember { mutableStateOf<LatestResult<SearchKey, Set<String>>?>(null) }
    DisposableEffect(Unit) { onDispose { debouncer.cancel(); worker.shutdown() } }
    DisposableEffect(key, vault.revision) {
        val active = AtomicBoolean(true)
        val token = controller.sessionEpoch.capture()
        debouncer.submit(searchDelayMillis(lastQuery.getAndSet(query), query)) {
            // Scans a session copy that is erased afterwards; UI lock may close the displayed copy meanwhile.
            // Null when the scan is outdated: a newer revision or search replaces it, so nothing is shown from it.
            val matches = runCatching {
                if (!active.get()) null else controller.read { current ->
                    if (current.id != vault.id || current.revision != vault.revision) null
                    else VaultSearch.find(current, query, includeHidden, locale) { !active.get() }
                }
            }
            if (matches.isSuccess && matches.getOrNull() == null) return@submit
            SwingUtilities.invokeLater {
                if (active.get() && controller.sessionEpoch.accepts(token)) result = LatestResult(key, matches.getOrNull() ?: emptySet())
            }
        }
        onDispose { active.set(false) }
    }
    return result.valueFor(key)
}
