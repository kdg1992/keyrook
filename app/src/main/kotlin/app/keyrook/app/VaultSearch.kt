// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.Entry
import app.keyrook.core.model.Field
import app.keyrook.core.model.ReservedTags
import app.keyrook.core.model.Vault
import app.keyrook.core.model.primaryUrl
import app.keyrook.core.model.primaryUsername
import java.nio.CharBuffer
import java.util.Locale

/** A search term limited to one field by a prefix such as `kunde:` or `host:`; null [scope] searches everything. */
internal data class SearchTerm(val text: String, val scope: SearchScope? = null)

internal enum class SearchScope(vararg val prefixes: String) {
    CUSTOMER("kunde", "customer"), PROJECT("projekt", "project"), HOST("host"), USER("user", "benutzer"),
    TYPE("typ", "type"), TAG("tag");

    companion object {
        fun of(prefix: String): SearchScope? = entries.firstOrNull { prefix.lowercase(Locale.ROOT) in it.prefixes }
    }
}

/** Splits on whitespace; `prefix:value` with a known prefix and a non-empty value becomes a field term. */
internal fun searchTerms(query: String): List<SearchTerm> = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { word ->
    val colon = word.indexOf(':')
    val scope = if (colon > 0 && colon < word.lastIndex) SearchScope.of(word.substring(0, colon)) else null
    if (scope != null) SearchTerm(word.substring(colon + 1), scope) else SearchTerm(word)
}

/**
 * Searches current values without creating a plaintext index or immutable copies of secret fields. Every term must
 * match. Results are ordered by relevance: a title starting with a term ranks first, then a title containing it, then
 * customer, project, tag, host or user name, then any other text; favorites rank before other entries with the same
 * relevance, then titles in alphabetical order. A blank query returns every entry in vault order.
 */
internal object VaultSearch {
    private const val TITLE_PREFIX = 0
    private const val TITLE = 1
    private const val PRIMARY = 2
    private const val OTHER = 3

    fun find(vault: Vault, query: String, includeHidden: Boolean = false, locale: Locale = UiText.locale,
             cancelled: () -> Boolean = { false }): Set<String> {
        require(query.length <= 256)
        val terms = searchTerms(query)
        if (cancelled()) return emptySet()
        if (terms.isEmpty()) return vault.entries.mapTo(linkedSetOf()) { it.id }
        val customers = vault.customers.associate { it.id to it.name }
        val projects = vault.projects.associate { it.id to it.name }
        class Match(val entry: Entry, val score: Int)
        val matches = mutableListOf<Match>()
        for (entry in vault.entries) {
            if (cancelled()) return emptySet()
            // Best (lowest) relevance class found so far per term; a term without a value has not matched.
            val best = arrayOfNulls<Int>(terms.size)
            // A field term matches only text of its own scope; [scopedOnly] text matches field terms alone.
            fun inspect(text: CharSequence, rank: Int, scope: SearchScope? = null, scopedOnly: Boolean = false) {
                terms.forEachIndexed { index, term ->
                    if (term.scope != null && term.scope != scope) return@forEachIndexed
                    if (term.scope == null && scopedOnly) return@forEachIndexed
                    val current = best[index]
                    if (current != null && current <= rank) return@forEachIndexed
                    if (text.contains(term.text, ignoreCase = true)) best[index] = rank
                }
            }
            fun inspect(field: Field?, rank: Int, scope: SearchScope?) {
                if (field != null && (!field.hidden || includeHidden)) field.value.useChars { inspect(CharBuffer.wrap(it), rank, scope) }
            }
            terms.forEachIndexed { index, term ->
                if (term.scope == null && entry.title.startsWith(term.text, ignoreCase = true)) best[index] = TITLE_PREFIX
            }
            inspect(entry.title, TITLE)
            customers[entry.customerId]?.let { inspect(it, PRIMARY, SearchScope.CUSTOMER) }
            projects[entry.projectId]?.let { inspect(it, PRIMARY, SearchScope.PROJECT) }
            // Reserved tags are hidden, so they neither match nor explain a match.
            ReservedTags.visible(entry.tags).forEach { inspect(it, PRIMARY, SearchScope.TAG) }
            inspect(entry.data.type().label(locale), OTHER, SearchScope.TYPE)
            inspect(entry.data.type().name, OTHER, SearchScope.TYPE, scopedOnly = true)
            inspect(entry.data.hostField(), PRIMARY, SearchScope.HOST)
            inspect(entry.data.primaryUrl(), PRIMARY, SearchScope.HOST)
            inspect(entry.data.primaryUsername(), PRIMARY, SearchScope.USER)
            if (best.any { it == null || it > TITLE }) {
                entry.expiresOn?.let { inspect(it, OTHER) }
                entry.data.labels(locale).forEach { inspect(it, OTHER) }
                entry.notes.useChars { inspect(CharBuffer.wrap(it), OTHER) }
                for (field in entry.data.fields()) {
                    if (cancelled()) return emptySet()
                    if (best.all { it != null && it <= PRIMARY }) break
                    inspect(field, OTHER, null)
                }
            }
            if (best.all { it != null }) matches += Match(entry, best.sumOf { it!! })
        }
        return matches.sortedWith(compareBy<Match> { it.score }.thenBy { !it.entry.pinned }
            .thenBy { it.entry.title.lowercase(Locale.ROOT) }.thenBy { it.entry.title }.thenBy { it.entry.id })
            .mapTo(linkedSetOf()) { it.entry.id }
    }
}
