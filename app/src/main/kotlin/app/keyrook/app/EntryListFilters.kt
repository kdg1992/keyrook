// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.Entry
import app.keyrook.core.model.Project
import app.keyrook.core.model.Vault
import app.keyrook.core.security.VaultHealth
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

internal enum class ExpiryFilter(private val labelKey: String) {
    ALL("filters.expiry.all"), EXPIRED("filters.expiry.expired"), UPCOMING("filters.expiry.upcoming"), NONE("filters.expiry.none");
    val label: String get() = UiText.text(labelKey)
}

internal enum class EntrySort(private val labelKey: String) {
    TITLE("filters.sort.title"), MODIFIED("filters.sort.modified"), EXPIRY("filters.sort.expiry");
    val label: String get() = UiText.text(labelKey)
}

internal data class EntryListFilters(
    val trash: Boolean = false,
    val type: EntryType? = null,
    val customerId: String? = null,
    val projectId: String? = null,
    val tag: String? = null,
    /** Only entries marked as favorites, i.e. pinned (see [Entry.pinned]). */
    val favorites: Boolean = false,
    /** Only recently used entries, most recent first instead of in [sort] order (see [RecentEntries]). */
    val recent: Boolean = false,
    val expiry: ExpiryFilter = ExpiryFilter.ALL,
    val sort: EntrySort = EntrySort.TITLE,
) {
    fun projects(vault: Vault): List<Project> = vault.projects.filter {
        customerId == null || it.customerId == null || it.customerId == customerId
    }

    fun normalized(vault: Vault): EntryListFilters {
        val customer = customerId?.takeIf { id -> vault.customers.any { it.id == id } }
        val available = copy(customerId = customer)
        return available.copy(projectId = projectId?.takeIf { id -> available.projects(vault).any { it.id == id } })
    }

    /**
     * Uses metadata only; returned entries remain owned by the supplied vault snapshot. [recentIds] are the recently
     * used entry IDs, newest first, for the [recent] filter.
     */
    fun select(vault: Vault, matches: Set<String>, today: LocalDate, recentIds: List<String> = emptyList()): List<Entry> {
        val recency = recentIds.withIndex().associate { it.value to it.index }
        val owners = vault.projects.associate { it.id to it.customerId }
        val selected = vault.entries.filter { entry ->
            (entry.deletedAt != null) == trash && entry.id in matches &&
                (type == null || entry.data.type() == type) &&
                (customerId == null || (entry.customerId ?: owners[entry.projectId]) == customerId) &&
                (projectId == null || entry.projectId == projectId) &&
                (tag == null || tag in entry.tags) && (!favorites || entry.pinned) &&
                (!recent || entry.id in recency) && when (expiry) {
                    ExpiryFilter.ALL -> true
                    ExpiryFilter.NONE -> entry.expiresOn == null
                    ExpiryFilter.EXPIRED -> entry.expiresOn?.let { LocalDate.parse(it) < today } == true
                    ExpiryFilter.UPCOMING -> entry.expiresOn?.let { LocalDate.parse(it) in today..today.plusDays(VaultHealth.EXPIRY_WARNING_DAYS) } == true
                }
        }
        return if (recent) selected.sortedBy { recency.getValue(it.id) } else sortEntries(selected, sort)
    }
}

/**
 * Orders [entries] by [sort]: by title case-insensitively, then exactly, then by ID; the change time (newest first)
 * and the expiry date (undated last) sort before the title. Each entry's keys are computed once, not per comparison.
 */
internal fun sortEntries(entries: List<Entry>, sort: EntrySort): List<Entry> {
    class Keyed(val entry: Entry) {
        val title = entry.title.lowercase(Locale.ROOT)
        val modified: Instant? = if (sort == EntrySort.MODIFIED) Instant.parse(entry.modifiedAt) else null
        val expires: LocalDate? = if (sort == EntrySort.EXPIRY) entry.expiresOn?.let(LocalDate::parse) else null
    }
    val titleOrder = compareBy<Keyed> { it.title }.thenBy { it.entry.title }.thenBy { it.entry.id }
    val order = when (sort) {
        EntrySort.TITLE -> titleOrder
        EntrySort.MODIFIED -> compareByDescending<Keyed> { it.modified }.then(titleOrder)
        EntrySort.EXPIRY -> compareBy<Keyed, LocalDate?>(nullsLast()) { it.expires }.then(titleOrder)
    }
    return entries.map(::Keyed).sortedWith(order).map { it.entry }
}
