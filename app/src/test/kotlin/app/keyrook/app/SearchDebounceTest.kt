// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.Vault
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class SearchDebounceTest {
    @TempDir lateinit var directory: Path

    @Test fun `only a changed non-blank query waits`() {
        assertEquals(0L, searchDelayMillis(null, "a"))
        assertEquals(SEARCH_DEBOUNCE_MILLIS, searchDelayMillis("a", "ab"))
        assertEquals(SEARCH_DEBOUNCE_MILLIS, searchDelayMillis("", "a"))
        assertEquals(0L, searchDelayMillis("ab", "ab"))
        assertEquals(0L, searchDelayMillis("ab", " "))
        assertTrue(SEARCH_DEBOUNCE_MILLIS in 150L..250L)
    }

    /** Runs due tasks only when the test advances its clock, so no test depends on real time. */
    private class ManualScheduler : DelayScheduler {
        private class Scheduled(val at: Long, val task: FutureTask<Unit>)
        private val queue = mutableListOf<Scheduled>()
        var now = 0L
            private set

        override fun schedule(delayMillis: Long, task: Runnable): Future<*> =
            FutureTask(task, Unit).also { queue += Scheduled(now + delayMillis, it) }

        fun advance(millis: Long) {
            now += millis
            queue.filter { it.at <= now }.sortedBy { it.at }.forEach { queue.remove(it); it.task.run() }
        }
    }

    @Test fun `a burst of keystrokes runs one scan for the last query`() {
        val scheduler = ManualScheduler()
        val debouncer = Debouncer(scheduler)
        val scanned = mutableListOf<String>()
        val query = "synthetic-query-20c"
        var previous: String? = ""
        for (length in 1..query.length) {
            val typed = query.take(length)
            debouncer.submit(searchDelayMillis(previous, typed)) { scanned.add(typed) }
            previous = typed
            scheduler.advance(SEARCH_DEBOUNCE_MILLIS - 1)
        }
        assertTrue(scanned.isEmpty())
        scheduler.advance(1)
        assertEquals(listOf(query), scanned)
        scheduler.advance(SEARCH_DEBOUNCE_MILLIS * 10)
        assertEquals(listOf(query), scanned)
    }

    @Test fun `an unchanged query scans at once and replaces a waiting scan`() {
        val scheduler = ManualScheduler()
        val debouncer = Debouncer(scheduler)
        val scanned = mutableListOf<String>()
        debouncer.submit(searchDelayMillis("a", "ab")) { scanned.add("typed") }
        debouncer.submit(searchDelayMillis("ab", "ab")) { scanned.add("revision") }
        scheduler.advance(0)
        assertEquals(listOf("revision"), scanned)
        scheduler.advance(SEARCH_DEBOUNCE_MILLIS)
        assertEquals(listOf("revision"), scanned)
    }

    @Test fun `cancel drops a pending scan`() {
        val scheduler = ManualScheduler()
        val debouncer = Debouncer(scheduler)
        val scanned = mutableListOf<String>()
        debouncer.submit(SEARCH_DEBOUNCE_MILLIS) { scanned.add("late") }
        debouncer.cancel()
        scheduler.advance(SEARCH_DEBOUNCE_MILLIS * 2)
        assertTrue(scanned.isEmpty())
    }

    @Test fun `the executor scheduler runs the latest submission`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            val debouncer = Debouncer(executor)
            val done = CountDownLatch(1)
            val scanned = Collections.synchronizedList(mutableListOf<String>())
            debouncer.submit(TimeUnit.HOURS.toMillis(1)) { scanned.add("replaced") }
            debouncer.submit(0) { scanned.add("latest"); done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("latest"), scanned.toList())
        } finally { executor.shutdownNow() }
    }

    @Test fun `search through a session read matches search of a snapshot`() {
        VaultController().use { controller ->
            controller.unlock(directory.resolve("search.keyrook"), "synthetic-master-passphrase".toCharArray(), null, true).close()
            val data = blankData(EntryType.WEB)
            val entry = editedEntry(null, data, "Synthetic login", "Production", "", "",
                listOf("https://example.invalid", "ServiceUser", "secret-sentinel", ""), listOf(false, false, true, true))
            data.fields().forEach { it.value.close() }
            Vault(entries = listOf(entry)).use { controller.save(entry).close() }
            listOf("synthetic", "serviceuser", "secret-sentinel", "missing", "").forEach { query ->
                listOf(false, true).forEach { hidden ->
                    val expected = controller.session.snapshot().use { VaultSearch.find(it, query, hidden, Locale.ENGLISH) }
                    assertEquals(expected, controller.read { VaultSearch.find(it, query, hidden, Locale.ENGLISH) }, query)
                }
            }
            assertEquals(setOf(entry.id), controller.read { VaultSearch.find(it, "secret-sentinel", true, Locale.ENGLISH) })
        }
    }
}
