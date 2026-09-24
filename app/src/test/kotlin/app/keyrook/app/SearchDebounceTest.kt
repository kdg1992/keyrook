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

    @Test fun `a burst of keystrokes runs one scan for the last query`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val debouncer = Debouncer(scheduler)
            val scanned = Collections.synchronizedList(mutableListOf<String>())
            val done = CountDownLatch(1)
            val query = "synthetic-query-20c"
            var previous: String? = ""
            for (length in 1..query.length) {
                val typed = query.take(length)
                debouncer.submit(searchDelayMillis(previous, typed)) { scanned.add(typed); done.countDown() }
                previous = typed
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
            scheduler.schedule({}, SEARCH_DEBOUNCE_MILLIS * 2, TimeUnit.MILLISECONDS).get()
            assertEquals(listOf(query), scanned.toList())
        } finally { scheduler.shutdownNow() }
    }

    @Test fun `cancel drops a pending scan`() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val debouncer = Debouncer(scheduler)
            val scanned = Collections.synchronizedList(mutableListOf<String>())
            debouncer.submit(SEARCH_DEBOUNCE_MILLIS) { scanned.add("late") }
            debouncer.cancel()
            scheduler.schedule({}, SEARCH_DEBOUNCE_MILLIS * 2, TimeUnit.MILLISECONDS).get()
            assertTrue(scanned.isEmpty())
        } finally { scheduler.shutdownNow() }
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
