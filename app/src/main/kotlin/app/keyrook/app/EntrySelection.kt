// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

/** Identifies what the list shows; a different query restarts the selection at the first result. */
internal data class ListQuery(val search: String, val filters: EntryListFilters, val includeHidden: Boolean)

/**
 * Keyboard selection of the entry list. Holds entry IDs in display order only, never entries or values.
 * Moves clamp at both ends. A new query selects the first result; when the vault changes under the same query,
 * the selection stays on its entry or, if that entry left the list (for example moved to the trash), moves to
 * the next remaining entry, otherwise the previous one.
 */
internal data class EntrySelection(val selectedId: String? = null, val visible: List<String> = emptyList(),
                                   val query: Any? = null) {
    /** [ids] is null while results are pending; the previous list is then kept unchanged. */
    fun update(query: Any, ids: List<String>?): EntrySelection = when {
        ids == null -> this
        query != this.query -> EntrySelection(ids.firstOrNull(), ids, query)
        else -> copy(selectedId = follow(ids), visible = ids)
    }

    fun next(): EntrySelection = step(1)
    fun previous(): EntrySelection = step(-1)
    fun first(): EntrySelection = copy(selectedId = visible.firstOrNull())
    fun last(): EntrySelection = copy(selectedId = visible.lastOrNull())
    fun select(id: String): EntrySelection = if (id in visible) copy(selectedId = id) else this

    private fun step(delta: Int): EntrySelection {
        if (visible.isEmpty()) return this
        val index = visible.indexOf(selectedId)
        val target = if (index < 0) { if (delta > 0) 0 else visible.lastIndex }
        else (index + delta).coerceIn(0, visible.lastIndex)
        return copy(selectedId = visible[target])
    }

    private fun follow(ids: List<String>): String? {
        val selected = selectedId ?: return ids.firstOrNull()
        val present = ids.toHashSet()
        if (selected in present) return selected
        val index = visible.indexOf(selected)
        if (index < 0) return ids.firstOrNull()
        return visible.subList(index + 1, visible.size).firstOrNull { it in present }
            ?: visible.subList(0, index).lastOrNull { it in present }
            ?: ids.firstOrNull()
    }
}
