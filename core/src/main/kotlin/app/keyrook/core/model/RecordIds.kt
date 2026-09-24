// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.model

import java.util.UUID

/**
 * Returns the same records under new IDs, for merging an import into a vault that may already contain them: a vault
 * imported from its own export, or the same file imported twice. Customers, projects, entries and templates get IDs
 * from [newId]; every reference follows: the customer of projects, the customer and project of entries and templates,
 * and the SSH server and registrar login references of current data and of history items. Historical references to
 * entries that are not part of this vault stay unchanged, like any stale historical reference. The vault's own ID and
 * revision are kept. The result shares all secrets with this vault, so the caller keeps ownership.
 */
fun Vault.withNewRecordIds(newId: () -> String = { UUID.randomUUID().toString() }): Vault {
    val issued = HashSet<String>()
    fun renamed(old: List<String>): Map<String, String> = old.associateWith {
        newId().also { id -> require(issued.add(id)) { "Duplicate new record ID" } }
    }
    val customerIds = renamed(customers.map { it.id })
    val projectIds = renamed(projects.map { it.id })
    val entryIds = renamed(entries.map { it.id })
    val templateIds = renamed(templates.map { it.id })
    fun Map<String, String>.of(old: String): String = get(old) ?: old
    fun Map<String, String>.ofOptional(old: String?): String? = old?.let { of(it) }
    fun data(value: EntryData): EntryData = when (value) {
        is EntryData.Ssh -> value.copy(serverIds = value.serverIds.map { entryIds.of(it) })
        is EntryData.Domain -> value.copy(registrarLoginId = entryIds.ofOptional(value.registrarLoginId))
        else -> value
    }
    return copy(
        customers = customers.map { it.copy(id = customerIds.of(it.id)) },
        projects = projects.map { it.copy(id = projectIds.of(it.id), customerId = customerIds.ofOptional(it.customerId)) },
        entries = entries.map { entry ->
            entry.copy(id = entryIds.of(entry.id), customerId = customerIds.ofOptional(entry.customerId),
                projectId = projectIds.ofOptional(entry.projectId), data = data(entry.data),
                history = entry.history.map { it.copy(data = data(it.data)) })
        },
        templates = templates.map {
            it.copy(id = templateIds.of(it.id), customerId = customerIds.ofOptional(it.customerId),
                projectId = projectIds.ofOptional(it.projectId))
        },
    )
}
