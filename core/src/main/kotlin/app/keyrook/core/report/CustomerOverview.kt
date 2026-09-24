// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.report

import app.keyrook.core.model.Customer
import app.keyrook.core.model.Entry
import app.keyrook.core.model.EntryData
import app.keyrook.core.model.Field
import app.keyrook.core.model.Project
import app.keyrook.core.model.Vault
import java.util.Locale

/**
 * Looks up a translated report label by its message key, for example `entry.type.web` or `report.domains`. Reports
 * are built in core without the desktop's message catalog; tests pass the key itself.
 */
fun interface ReportText {
    fun text(key: String): String
}

/**
 * Type keys in a fixed order, equal to the serialized type names and to the desktop's `entry.type.<key>` labels.
 */
val REPORT_TYPE_KEYS = listOf("web", "transfer", "email", "panel", "server", "ssh", "domain", "custom")

internal fun EntryData.typeKey(): String = when (this) {
    is EntryData.Web -> "web"
    is EntryData.Transfer -> "transfer"
    is EntryData.Email -> "email"
    is EntryData.Panel -> "panel"
    is EntryData.Server -> "server"
    is EntryData.Ssh -> "ssh"
    is EntryData.Domain -> "domain"
    is EntryData.Custom -> "custom"
}

/**
 * The customer of [entry]: its own customer, otherwise the customer of its project. This is the assignment the
 * entry list's customer filter uses; the vault model already guarantees that both never disagree.
 */
fun Vault.customerIdOf(entry: Entry): String? =
    entry.customerId ?: entry.projectId?.let { id -> projects.firstOrNull { it.id == id }?.customerId }

/** Active (not trashed) entries of [customerId]; null selects the entries without a customer. */
fun Vault.activeEntriesOf(customerId: String?): List<Entry> {
    val owners = projects.associate { it.id to it.customerId }
    return entries.filter { it.deletedAt == null && (it.customerId ?: owners[it.projectId]) == customerId }
}

/**
 * The value of a field that is not marked hidden, or null for a hidden one. Only fields that reports list by
 * meaning (hosts, URLs, user names and similar) are ever passed here; a hidden field is never read.
 */
internal fun visible(field: Field): String? = if (field.hidden) null else field.value.useChars { String(it) }

internal val titleOrder: Comparator<Entry> =
    compareBy<Entry> { it.title.lowercase(Locale.ROOT) }.thenBy { it.title }.thenBy { it.id }

/** A domain entry of an overview; [name] is null when the domain field is hidden. */
data class DomainLine(val title: String, val name: String?, val expiresOn: String?)

/** A server or file-transfer entry of an overview; [host] is null when the host field is hidden. */
data class HostLine(val title: String, val typeKey: String, val host: String?, val port: Int)

/** The plain contact details of a customer; customer notes are secret and never part of a report. */
data class ContactLine(val contactName: String?, val email: String?, val phone: String?, val website: String?) {
    val isEmpty: Boolean get() = contactName == null && email == null && phone == null && website == null
}

/** A project of an overview with its plain description; project notes are secret and never part of a report. */
data class ProjectLine(val name: String, val description: String?)

/**
 * Metadata of one customer's active entries: counts per type key (only types that occur), domains and hosts, and the
 * customer's plain contact details and projects. [customerId] and [name] are null for the entries without a
 * customer, whose [contact] is null and whose [projects] are the projects without a customer. No secret, no notes
 * and no hidden field is included.
 */
data class CustomerOverview(val customerId: String?, val name: String?, val counts: Map<String, Int>,
                            val domains: List<DomainLine>, val hosts: List<HostLine>,
                            val contact: ContactLine? = null, val projects: List<ProjectLine> = emptyList())

/**
 * One overview per customer, sorted by name, followed by the entries without a customer if there are any.
 * Trashed entries are left out. The result holds immutable strings of visible fields only.
 */
fun customerOverviews(vault: Vault): List<CustomerOverview> {
    fun overview(customer: Customer?): CustomerOverview {
        val customerId = customer?.id
        val entries = vault.activeEntriesOf(customerId).sortedWith(titleOrder)
        val counts = entries.groupingBy { it.data.typeKey() }.eachCount()
        val domains = entries.mapNotNull { entry ->
            (entry.data as? EntryData.Domain)?.let { DomainLine(entry.title, visible(it.name), entry.expiresOn) }
        }
        val hosts = entries.mapNotNull { entry ->
            when (val data = entry.data) {
                is EntryData.Server -> HostLine(entry.title, "server", visible(data.host), data.port)
                is EntryData.Transfer -> HostLine(entry.title, "transfer", visible(data.host), data.port)
                else -> null
            }
        }
        val projects = vault.projects.filter { it.customerId == customerId }
            .sortedWith(compareBy<Project> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name }.thenBy { it.id })
            .map { ProjectLine(it.name, it.description) }
        val contact = customer?.let { ContactLine(it.contactName, it.contactEmail, it.phone, it.website) }
        return CustomerOverview(customerId, customer?.name,
            REPORT_TYPE_KEYS.filter { it in counts }.associateWith { counts.getValue(it) }, domains, hosts, contact, projects)
    }
    val customers = vault.customers.sortedWith(compareBy<Customer> { it.name.lowercase(Locale.ROOT) }
        .thenBy { it.name }.thenBy { it.id }).map { overview(it) }
    val unassigned = overview(null)
    return if (unassigned.counts.isEmpty()) customers else customers + unassigned
}

/** Renders [overviews] as plain text for a read-only dialog; values are never interpreted as markup. */
fun customerOverviewText(overviews: List<CustomerOverview>, text: ReportText): String = buildString {
    if (overviews.isEmpty()) {
        append(text.text("report.overviewEmpty"))
        return@buildString
    }
    overviews.forEachIndexed { index, overview ->
        if (index > 0) append('\n')
        append(overview.name ?: text.text("report.noCustomer")).append('\n')
        overview.contact?.takeUnless { it.isEmpty }?.let { contact ->
            listOf("report.contactName" to contact.contactName, "report.contactEmail" to contact.email,
                "report.phone" to contact.phone, "report.website" to contact.website).forEach { (key, value) ->
                if (value != null) append("  ").append(text.text(key)).append(": ").append(value).append('\n')
            }
        }
        if (overview.projects.isNotEmpty()) {
            append("  ").append(text.text("report.projects")).append(':')
            overview.projects.forEach { project ->
                append("\n    ").append(project.name)
                project.description?.let { append(" – ").append(it.lines().joinToString(" ")) }
            }
            append('\n')
        }
        append("  ").append(text.text("report.entries")).append(": ")
        append(if (overview.counts.isEmpty()) text.text("report.none")
            else overview.counts.entries.joinToString(", ") { "${text.text("entry.type.${it.key}")} ${it.value}" })
        append('\n')
        append("  ").append(text.text("report.domains")).append(':')
        if (overview.domains.isEmpty()) append(' ').append(text.text("report.none"))
        overview.domains.forEach { domain ->
            append("\n    ").append(domain.name ?: text.text("common.hidden")).append(" (").append(domain.title).append(") – ")
            append(domain.expiresOn?.let { "${text.text("report.expires")} $it" } ?: text.text("report.noExpiry"))
        }
        append('\n')
        append("  ").append(text.text("report.hosts")).append(':')
        if (overview.hosts.isEmpty()) append(' ').append(text.text("report.none"))
        overview.hosts.forEach { host ->
            append("\n    ").append(host.host?.let { "$it:${host.port}" } ?: text.text("common.hidden"))
            append(" (").append(host.title).append(", ").append(text.text("entry.type.${host.typeKey}")).append(')')
        }
        append('\n')
    }
}
