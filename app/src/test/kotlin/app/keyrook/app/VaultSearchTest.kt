// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.UUID

class VaultSearchTest {
    private fun field(text: String, hidden: Boolean = false) = Field(Secret(text.toCharArray()), hidden)
    private fun fixture(): Vault {
        val customer = Customer(UUID.randomUUID().toString(), "Synthetic Customer")
        val project = Project(UUID.randomUUID().toString(), "Monitoring", customer.id)
        val date = "2026-01-01T00:00:00Z"
        val entry = Entry(UUID.randomUUID().toString(), "Synthetic login",
            EntryData.Web(field("https://console.example.invalid"), field("ServiceUser"), field("secret-sentinel", true)),
            date, date, customer.id, project.id, listOf("Production"), Secret("Rotating access quarterly".toCharArray()),
            expiresOn = "2027-04-01", history = listOf(HistoryItem(date, EntryData.Custom(mapOf("Old" to field("historical-only"))))))
        return Vault(customers = listOf(customer), projects = listOf(project), entries = listOf(entry))
    }

    @Test fun `finds metadata notes and visible field values across case insensitive terms`() {
        fixture().use { vault ->
            val id = vault.entries.single().id
            listOf("SYNTHETIC", "console.example.invalid", "serviceuser", "quarterly", "customer monitoring",
                "production QUARTERLY", "2027-04-01", "Web-Login").forEach { query ->
                assertEquals(setOf(id), VaultSearch.find(vault, query, locale = Locale.GERMAN), query)
            }
            assertTrue(VaultSearch.find(vault, "quarterly missing").isEmpty())
            assertEquals(setOf(id), VaultSearch.find(vault, "web LOGIN", locale = Locale.ENGLISH))
            assertTrue(VaultSearch.find(vault, "Web-Login", locale = Locale.ENGLISH).isEmpty())
        }
    }

    @Test fun `hidden values require explicit option and history remains excluded`() {
        fixture().use { vault ->
            assertTrue(VaultSearch.find(vault, "secret-sentinel").isEmpty())
            assertEquals(setOf(vault.entries.single().id), VaultSearch.find(vault, "secret-sentinel", includeHidden = true))
            assertTrue(VaultSearch.find(vault, "historical-only", includeHidden = true).isEmpty())
            assertEquals("secret-sentinel", vault.entries.single().data.fields()[2].value.useChars { String(it) })
        }
    }

    @Test fun `searches custom field labels and unicode text`() {
        val date = "2026-01-01T00:00:00Z"
        val entry = Entry(UUID.randomUUID().toString(), "Custom", EntryData.Custom(mapOf("Region" to field("Düsseldorf"))), date, date)
        Vault(entries = listOf(entry)).use { vault ->
            assertEquals(setOf(entry.id), VaultSearch.find(vault, "region DÜSSELDORF"))
        }
    }

    @Test fun `blank query lists entries and cancellation discards all matches`() {
        fixture().use { vault ->
            assertEquals(setOf(vault.entries.single().id), VaultSearch.find(vault, "  "))
            assertTrue(VaultSearch.find(vault, "synthetic", cancelled = { true }).isEmpty())
            assertThrows(IllegalArgumentException::class.java) { VaultSearch.find(vault, "x".repeat(257)) }
        }
    }

    @Test fun `the favorite mark is not searchable`() {
        fixture().use { vault ->
            val entry = vault.entries.single()
            val favorite = vault.copy(entries = listOf(entry.copy(pinned = true)))
            assertTrue(VaultSearch.find(favorite, "keyrook:favorite").isEmpty())
            assertTrue(VaultSearch.find(favorite, "favorite").isEmpty())
            assertEquals(setOf(entry.id), VaultSearch.find(favorite, "production"))
        }
    }

    private fun server(title: String, host: String, user: String, customer: String? = null, pinned: Boolean = false, tags: List<String> = emptyList()): Entry {
        val date = "2026-01-01T00:00:00Z"
        return Entry(UUID.randomUUID().toString(), title, EntryData.Server(field(host), 22, field(user), field("synthetic-secret", true), field(""), field("")),
            date, date, customerId = customer, tags = tags, pinned = pinned)
    }

    @Test fun `field prefixes limit a term to one field`() {
        val miller = Customer(UUID.randomUUID().toString(), "Müller GmbH")
        val other = Customer(UUID.randomUUID().toString(), "Schmidt AG")
        val a = server("Webserver", "web01.example.invalid", "deploy", miller.id, tags = listOf("prod"))
        val b = server("Mail müller", "mail.example.invalid", "admin", other.id)
        Vault(customers = listOf(miller, other), entries = listOf(a, b)).use { vault ->
            assertEquals(setOf(a.id, b.id), VaultSearch.find(vault, "müller"))
            assertEquals(setOf(a.id), VaultSearch.find(vault, "kunde:müller"))
            assertEquals(setOf(a.id), VaultSearch.find(vault, "customer:MÜLLER host:web01"))
            assertEquals(setOf(b.id), VaultSearch.find(vault, "user:admin"))
            assertEquals(setOf(a.id), VaultSearch.find(vault, "tag:prod"))
            assertEquals(setOf(a.id, b.id), VaultSearch.find(vault, "typ:server", locale = Locale.GERMAN))
            assertTrue(VaultSearch.find(vault, "host:deploy").isEmpty())
            // Unknown prefixes and an empty value search the whole text as before.
            assertTrue(VaultSearch.find(vault, "unknown:web01").isEmpty())
            assertEquals(setOf(a.id), VaultSearch.find(vault, "web01.example.invalid"))
            assertTrue(VaultSearch.find(vault, "host:").isEmpty())
            // A field term never reaches a hidden value.
            assertTrue(VaultSearch.find(vault, "user:synthetic-secret", includeHidden = true).isEmpty())
        }
    }

    @Test fun `results rank title prefixes before title matches before other fields and favorites first`() {
        val prefix = server("Web01 Hosting", "a.example.invalid", "u1")
        val inTitle = server("Old web01 backup", "b.example.invalid", "u2")
        val inHost = server("Zeta", "web01.example.invalid", "u3")
        val pinnedHost = server("Omega", "web01.backup.invalid", "u4", pinned = true)
        Vault(entries = listOf(inHost, inTitle, pinnedHost, prefix)).use { vault ->
            assertEquals(listOf(prefix.id, inTitle.id, pinnedHost.id, inHost.id), VaultSearch.find(vault, "web01").toList())
            assertEquals(vault.entries.map { it.id }, VaultSearch.find(vault, " ").toList())
        }
    }
}
