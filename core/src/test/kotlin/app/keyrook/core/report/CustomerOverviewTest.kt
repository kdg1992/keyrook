// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.report

import app.keyrook.core.DATE
import app.keyrook.core.field
import app.keyrook.core.id
import app.keyrook.core.sampleVault
import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CustomerOverviewTest {
    @Test fun `customers come from the entry or its project and trash is left out`() {
        val alpha = Customer(id(), "alpha")
        val beta = Customer(id(), "Beta")
        val project = Project(id(), "project", beta.id)
        fun entry(title: String, data: EntryData, customer: String? = null, project: String? = null,
                  expiry: String? = null, deleted: Boolean = false) =
            Entry(id(), title, data, DATE, DATE, customer, project, expiresOn = expiry, deletedAt = if (deleted) DATE else null)
        Vault(customers = listOf(beta, alpha), projects = listOf(project), entries = listOf(
            entry("site", EntryData.Web(field("https://a.invalid", false), field("u"), field("p")), alpha.id),
            entry("domain", EntryData.Domain(field("a.invalid", false), field("r"), field("d")), alpha.id, expiry = "2027-02-03"),
            entry("hidden domain", EntryData.Domain(field("secret-domain.invalid"), field("r"), field("d")), alpha.id),
            entry("host", EntryData.Server(field("srv.invalid", false), 2222, field("u"), field("p"), field("os"), field("r")),
                project = project.id),
            entry("trashed", EntryData.Server(field("gone.invalid", false), 22, field("u"), field("p"), field("os"), field("r")),
                beta.id, deleted = true),
            entry("loose", EntryData.Custom(mapOf("x" to field("y"))))),
        ).use { vault ->
            val overviews = customerOverviews(vault)
            assertEquals(listOf("alpha", "Beta", null), overviews.map { it.name })
            assertEquals(mapOf("web" to 1, "domain" to 2), overviews[0].counts)
            assertEquals(listOf(DomainLine("domain", "a.invalid", "2027-02-03"), DomainLine("hidden domain", null, null)),
                overviews[0].domains)
            assertEquals(listOf(HostLine("host", "server", "srv.invalid", 2222)), overviews[1].hosts)
            assertEquals(mapOf("custom" to 1), overviews[2].counts)
            val text = customerOverviewText(overviews, reportKeys)
            assertTrue(text.contains("a.invalid (domain) – report.expires 2027-02-03"), text)
            assertTrue(text.contains("common.hidden (hidden domain) – report.noExpiry"), text)
            assertTrue(text.contains("srv.invalid:2222 (host, entry.type.server)"), text)
            assertTrue(text.contains("report.noCustomer"), text)
            assertFalse(text.contains("secret-domain") || text.contains("gone.invalid"), text)
        }
    }

    @Test fun `overview never reads a hidden field or any secret of the sample vault`() {
        sampleVault().use { vault ->
            closeHiddenSecrets(vault)
            val text = customerOverviewText(customerOverviews(vault), reportKeys)
            assertNoSentinel(text)
            assertTrue(text.contains("Customer-SENTINEL-4531"))
            assertTrue(text.contains("Contact-SENTINEL-2291") && text.contains("Description-SENTINEL-1182"), text)
        }
    }

    @Test fun `overview shows visible metadata but no sentinel of any entry type`() {
        sentinelVault().use { vault ->
            closeHiddenSecrets(vault)
            val overviews = customerOverviews(vault)
            assertEquals(listOf(SENTINEL_CUSTOMER.name), overviews.map { it.name })
            assertEquals(REPORT_TYPE_KEYS.associateWith { 2 }, overviews.single().counts)
            val text = customerOverviewText(overviews, reportKeys)
            assertNoSentinel(text)
            assertTrue(text.contains("srv.invalid:2222") && text.contains("ftp.invalid:2121") && text.contains("example.invalid"), text)
            assertEquals(ContactLine("Erika <Kontakt>", "kontakt@kunde.invalid", "+49 30 555", "https://kunde.invalid"),
                overviews.single().contact)
            assertEquals(listOf(ProjectLine("Projekt <P>", "Beschreibung <D>")), overviews.single().projects)
            listOf("report.contactName: Erika <Kontakt>", "report.contactEmail: kontakt@kunde.invalid",
                "report.phone: +49 30 555", "report.website: https://kunde.invalid", "Projekt <P> – Beschreibung <D>")
                .forEach { assertTrue(text.contains(it), it) }
        }
    }

    @Test fun `an empty vault has a short notice`() {
        Vault().use { assertEquals("report.overviewEmpty", customerOverviewText(customerOverviews(it), reportKeys)) }
    }
}
