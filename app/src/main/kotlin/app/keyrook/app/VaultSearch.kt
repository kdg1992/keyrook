// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.ReservedTags
import app.keyrook.core.model.Vault
import java.nio.CharBuffer
import java.util.Locale

/** Searches current values without creating a plaintext index or immutable copies of secret fields. */
internal object VaultSearch {
    fun find(vault: Vault, query: String, includeHidden: Boolean = false, locale: Locale = UiText.locale,
             cancelled: () -> Boolean = { false }): Set<String> {
        require(query.length <= 256)
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (cancelled()) return emptySet()
        if (terms.isEmpty()) return vault.entries.mapTo(linkedSetOf()) { it.id }
        val customers = vault.customers.associate { it.id to it.name }
        val projects = vault.projects.associate { it.id to it.name }
        val result = linkedSetOf<String>()
        for (entry in vault.entries) {
            if (cancelled()) return emptySet()
            val remaining = terms.toMutableSet()
            fun inspect(text: CharSequence) { remaining.removeAll { term -> text.contains(term, ignoreCase = true) } }
            inspect(entry.title)
            // Reserved tags are hidden, so they neither match nor explain a match.
            ReservedTags.visible(entry.tags).forEach(::inspect)
            customers[entry.customerId]?.let(::inspect)
            projects[entry.projectId]?.let(::inspect)
            entry.expiresOn?.let(::inspect)
            inspect(entry.data.type().label(locale))
            entry.data.labels(locale).forEach(::inspect)
            if (remaining.isNotEmpty()) entry.notes.useChars { inspect(CharBuffer.wrap(it)) }
            for (field in entry.data.fields()) {
                if (cancelled()) return emptySet()
                if (remaining.isEmpty()) break
                if (!field.hidden || includeHidden) field.value.useChars { inspect(CharBuffer.wrap(it)) }
            }
            if (remaining.isEmpty()) result.add(entry.id)
        }
        return result
    }
}
