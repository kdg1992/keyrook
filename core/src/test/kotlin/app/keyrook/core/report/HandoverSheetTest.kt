// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.report

import app.keyrook.core.DATE
import app.keyrook.core.field
import app.keyrook.core.id
import app.keyrook.core.model.*
import app.keyrook.core.sampleVault
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class HandoverSheetTest {
    private val today = LocalDate.parse("2026-09-24")

    @Test fun `no secret or hidden value of any entry type appears, even when secrets are marked visible`() {
        sentinelVault().use { vault ->
            closeHiddenSecrets(vault)
            val html = handoverSheetHtml(vault, SENTINEL_CUSTOMER.id, reportKeys, "de", today)
            assertNoSentinel(html)
            listOf("https://web.invalid/", "web-user", "ftp.invalid", "/srv/www", "info@mail.invalid",
                "imap.invalid:993 (TLS)", "pop.invalid:995 (TLS)", "smtp.invalid:587 (STARTTLS)", "panel-user", "reseller",
                "srv.invalid", "2222", "Debian", "SHA256:fingerprint", "Ed25519", "example.invalid", "Registrar GmbH")
                .forEach { assertTrue(html.contains(escapeHtml(it)), it) }
            REPORT_TYPE_KEYS.forEach { assertEquals(2, Regex("<td>entry\\.type\\.$it</td>").findAll(html).count(), it) }
            assertFalse(html.contains("ssh-ed25519 AAAA"), "the public key is not part of the sheet")
        }
    }

    @Test fun `the sample vault's secrets stay out and its trash is left out`() {
        sampleVault().use { vault ->
            closeHiddenSecrets(vault)
            val html = handoverSheetHtml(vault, vault.customers.single().id, reportKeys, "en", today)
            assertNoSentinel(html)
            assertFalse(html.contains("Title-SENTINEL-7"), "trashed custom entry")
            assertTrue(html.contains("Title-SENTINEL-0") && html.contains("https://example.invalid/login"))
            assertTrue(html.contains("Project-SENTINEL-8421") && html.contains("Tag-SENTINEL-9891"))
        }
    }

    @Test fun `every value is escaped and nothing active is emitted`() {
        sentinelVault().use { vault ->
            val html = handoverSheetHtml(vault, SENTINEL_CUSTOMER.id, ReportText { "<i>$it</i>" }, "de\"x", today)
            assertTrue(html.contains("Kunde &amp; &lt;Co&gt; &quot;Test&quot;"))
            assertTrue(html.contains("<h2>Titel 0 &lt;b&gt;</h2>"))
            assertTrue(html.contains("&lt;i&gt;report.handoverTitle&lt;/i&gt;"))
            assertTrue(html.contains("<html lang=\"de&quot;x\">"))
            assertFalse(html.contains("<b>") || html.contains("<i>") || html.contains("<script") || html.contains("href=") ||
                html.contains("src=\""))
            assertTrue(html.contains("default-src 'none'"))
        }
    }

    @Test fun `only the chosen customer's entries appear, including those assigned through a project`() {
        val a = Customer(id(), "A")
        val b = Customer(id(), "B")
        val project = Project(id(), "P", a.id)
        fun web(title: String, customer: String?, project: String?) = Entry(id(), title,
            EntryData.Web(field("https://$title.invalid", false), field("u", false), field("p")), DATE, DATE, customer, project)
        Vault(customers = listOf(a, b), projects = listOf(project),
            entries = listOf(web("direct", a.id, null), web("viaproject", null, project.id), web("other", b.id, null))).use { vault ->
            val html = handoverSheetHtml(vault, a.id, reportKeys, "de", today)
            assertTrue(html.contains("direct.invalid") && html.contains("viaproject.invalid"))
            assertFalse(html.contains("other"))
        }
    }

    @Test fun `escaping covers all markup characters`() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;&#39;", escapeHtml("<a href=\"x\">&'"))
    }
}
