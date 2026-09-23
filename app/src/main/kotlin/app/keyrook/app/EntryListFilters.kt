// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.Entry
import app.keyrook.core.model.Project
import app.keyrook.core.model.Vault
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

    /** Uses metadata only; returned entries remain owned by the supplied vault snapshot. */
    fun select(vault: Vault, matches: Set<String>, today: LocalDate): List<Entry> {
        val owners = vault.projects.associate { it.id to it.customerId }
        val titleOrder = compareBy<Entry> { it.title.lowercase(Locale.ROOT) }.thenBy { it.title }.thenBy { it.id }
        val order = when (sort) {
            EntrySort.TITLE -> titleOrder
            EntrySort.MODIFIED -> compareByDescending<Entry> { Instant.parse(it.modifiedAt) }.then(titleOrder)
            EntrySort.EXPIRY -> compareBy<Entry, LocalDate?>(nullsLast()) { it.expiresOn?.let(LocalDate::parse) }.then(titleOrder)
        }
        return vault.entries.filter { entry ->
            (entry.deletedAt != null) == trash && entry.id in matches &&
                (type == null || entry.data.type() == type) &&
                (customerId == null || (entry.customerId ?: owners[entry.projectId]) == customerId) &&
                (projectId == null || entry.projectId == projectId) &&
                (tag == null || tag in entry.tags) && when (expiry) {
                    ExpiryFilter.ALL -> true
                    ExpiryFilter.NONE -> entry.expiresOn == null
                    ExpiryFilter.EXPIRED -> entry.expiresOn?.let { LocalDate.parse(it) < today } == true
                    ExpiryFilter.UPCOMING -> entry.expiresOn?.let { LocalDate.parse(it) in today..today.plusDays(30) } == true
                }
        }.sortedWith(order)
    }
}
