// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Locale

class LatestResultTest {
    private val key = SearchKey("vault", "synthetic", includeHidden = false, Locale.GERMAN)

    @Test fun `results of the same search stay while a newer revision is rescanned`() {
        val shown = LatestResult(key, setOf("a", "b"))
        // The key has no revision: a save keeps the list instead of showing the pending state.
        assertEquals(setOf("a", "b"), shown.valueFor(SearchKey("vault", "synthetic", false, Locale.GERMAN)))
    }

    @Test fun `a different search vault option or language shows nothing until its own result`() {
        val shown = LatestResult(key, setOf("a"))
        listOf(key.copy(query = "other"), key.copy(vaultId = "other"), key.copy(includeHidden = true),
            key.copy(locale = Locale.ENGLISH)).forEach { assertNull(shown.valueFor(it), it.toString()) }
        assertNull((null as LatestResult<SearchKey, Set<String>>?).valueFor(key))
    }

    @Test fun `an empty result is a result and not the pending state`() {
        assertEquals(emptySet<String>(), LatestResult(key, emptySet<String>()).valueFor(key))
    }
}
