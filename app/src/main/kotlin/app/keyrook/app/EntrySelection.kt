// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

/** Identifies what the list shows; a different query restarts the selection at the first result. */
internal data class ListQuery(val search: String, val filters: EntryListFilters, val includeHidden: Boolean)

/**
 * Search text and filters of the entry list. Held above the list so they survive the editor; discarded on lock.
 * [search] may contain hidden-field search terms and is never persisted.
 */
internal data class ListView(val search: String = "", val filters: EntryListFilters = EntryListFilters(),
                             val includeHidden: Boolean = false) {
    fun query(filters: EntryListFilters = this.filters): ListQuery = ListQuery(search, filters, includeHidden)

    /**
     * The view in which [target] can be selected: unchanged when the list already shows it, otherwise all active
     * entries in the current sort order. Used to jump to an entry from outside the list, such as the warning list.
     */
    fun showing(target: String, visible: List<String>): ListView =
        if (target in visible) this else ListView(filters = EntryListFilters(sort = filters.sort))
}

/**
 * Keyboard selection of the entry list. Holds entry IDs in display order only, never entries or values.
 * Moves clamp at both ends. A new query selects the first result; when the vault changes under the same query,
 * the selection stays on its entry or, if that entry left the list (for example moved to the trash), moves to
 * the next remaining entry, otherwise the previous one. A [jump] target is selected as soon as a result list contains
 * it and is dropped by the first result list without it, so it never takes over a later, unrelated list.
 */
internal data class EntrySelection(val selectedId: String? = null, val visible: List<String> = emptyList(),
                                   val query: Any? = null, val target: String? = null) {
    /** [ids] is null while results are pending; the previous list and a pending [target] are then kept unchanged. */
    fun update(query: Any, ids: List<String>?): EntrySelection = when {
        ids == null -> this
        target != null && target in ids -> EntrySelection(target, ids, query)
        query != this.query -> EntrySelection(ids.firstOrNull(), ids, query)
        else -> copy(selectedId = follow(ids), visible = ids, target = null)
    }

    /** Selects [id] now when it is listed, otherwise with the next result list that contains it. */
    fun jump(id: String): EntrySelection = if (id in visible) copy(selectedId = id, target = null) else copy(target = id)

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
