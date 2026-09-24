// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EntryListFiltersTest {
    private val today = LocalDate.of(2026, 12, 15)
    private fun entry(id: String, expires: LocalDate? = null, title: String = id,
                      modified: String = "2026-01-01T00:00:00Z", trash: Boolean = false,
                      customer: String? = null, project: String? = null, tags: List<String> = emptyList()) = Entry(
        id, title, EntryData.Custom(mapOf("Secret" to Field(Secret("synthetic".toCharArray())))),
        "2025-01-01T00:00:00Z", modified, customerId = customer, projectId = project, tags = tags,
        expiresOn = expires?.toString(), deletedAt = if (trash) modified else null,
    )

    private fun EntryListFilters.ids(vault: Vault, matches: Set<String> = vault.entries.map { it.id }.toSet()) =
        select(vault, matches, today).map { it.id }

    @Test fun `expiry filters separate yesterday today day thirty and later across year boundary`() {
        Vault(entries = listOf(entry("yesterday", today.minusDays(1)), entry("today", today),
            entry("thirty", today.plusDays(30)), entry("later", today.plusDays(31)), entry("none"),
            entry("trash", today, trash = true))).use { vault ->
            assertEquals(listOf("yesterday"), EntryListFilters(expiry = ExpiryFilter.EXPIRED).ids(vault))
            assertEquals(listOf("thirty", "today"), EntryListFilters(expiry = ExpiryFilter.UPCOMING).ids(vault))
            assertEquals(listOf("none"), EntryListFilters(expiry = ExpiryFilter.NONE).ids(vault))
            assertEquals(5, EntryListFilters().ids(vault).size)
            assertEquals(listOf("trash"), EntryListFilters(trash = true, expiry = ExpiryFilter.UPCOMING).ids(vault))
        }
    }

    @Test fun `customer filter includes inherited owner and combines project tag type and search`() {
        Vault(customers = listOf(Customer("a", "A"), Customer("b", "B")),
            projects = listOf(Project("project-a", "A", "a"), Project("project-b", "B", "b"), Project("free", "Free")),
            entries = listOf(entry("inherited", customer = null, project = "project-a", tags = listOf("tag")),
                entry("direct", customer = "a", project = "project-a"), entry("other", project = "project-b"),
                entry("independent", customer = "a", project = "free"))).use { vault ->
            assertEquals(listOf("direct", "independent", "inherited"), EntryListFilters(customerId = "a").ids(vault))
            val filters = EntryListFilters(customerId = "a", projectId = "project-a", tag = "tag", type = EntryType.CUSTOM)
            assertEquals(listOf("inherited"), filters.ids(vault))
            assertTrue(filters.ids(vault, setOf("direct")).isEmpty())
            assertTrue(filters.copy(type = EntryType.WEB).ids(vault).isEmpty())
            assertEquals(listOf("project-a", "free"), filters.projects(vault).map { it.id })
        }
    }

    @Test fun `incompatible and removed organization selections are cleared`() {
        Vault(customers = listOf(Customer("a", "A"), Customer("b", "B")),
            projects = listOf(Project("owned", "Owned", "a"), Project("free", "Free"))).use { vault ->
            assertNull(EntryListFilters(customerId = "b", projectId = "owned").normalized(vault).projectId)
            assertEquals("free", EntryListFilters(customerId = "b", projectId = "free").normalized(vault).projectId)
            assertNull(EntryListFilters(customerId = "missing").normalized(vault).customerId)
            assertNull(EntryListFilters(projectId = "missing").normalized(vault).projectId)
        }
    }

    @Test fun `sort is deterministic by title then identifier regardless of input order`() {
        Vault(entries = listOf(entry("c", title = "beta"), entry("b", title = "Alpha"), entry("a", title = "Alpha"))).use { vault ->
            val expected = listOf("a", "b", "c")
            EntrySort.entries.forEach { sort ->
                assertEquals(expected, EntryListFilters(sort = sort).ids(vault))
                assertEquals(expected, EntryListFilters(sort = sort).ids(vault.copy(entries = vault.entries.reversed())))
            }
        }
    }

    @Test fun `modified sorting compares instants and expiry puts undated entries last`() {
        Vault(entries = listOf(entry("older", today.plusDays(1), modified = "2026-01-01T01:00:00+02:00"),
            entry("newer", today, modified = "2026-01-01T00:00:00Z"),
            entry("undated", modified = "2025-01-01T00:00:00Z"))).use { vault ->
            assertEquals(listOf("newer", "older", "undated"), EntryListFilters(sort = EntrySort.MODIFIED).ids(vault))
            assertEquals(listOf("newer", "older", "undated"), EntryListFilters(sort = EntrySort.EXPIRY).ids(vault))
        }
    }

    @Test fun `filtering and sorting never access closed secret values`() {
        val vault = Vault(entries = listOf(entry("one", today), entry("two", today.minusDays(1))))
        vault.close()
        EntrySort.entries.forEach { sort ->
            assertEquals(listOf("one"), EntryListFilters(expiry = ExpiryFilter.UPCOMING, sort = sort).ids(vault))
        }
    }

    @Test fun `favorites filter keeps only pinned entries in both lists`() {
        Vault(entries = listOf(entry("plain", tags = listOf("favorite")), entry("star").copy(pinned = true),
            entry("both", tags = listOf("ops")).copy(pinned = true), entry("gone", trash = true).copy(pinned = true)))
            .use { vault ->
                assertEquals(listOf("both", "star"), EntryListFilters(favorites = true).ids(vault))
                assertEquals(listOf("both"), EntryListFilters(favorites = true, tag = "ops").ids(vault))
                assertEquals(listOf("gone"), EntryListFilters(favorites = true, trash = true).ids(vault))
                assertEquals(listOf("both", "plain", "star"), EntryListFilters().ids(vault))
            }
    }

    @Test fun `recent filter keeps recently used entries newest first regardless of sort`() {
        Vault(entries = listOf(entry("a"), entry("b"), entry("c"), entry("t", trash = true))).use { vault ->
            val recent = listOf("c", "t", "a", "missing")
            val all = vault.entries.map { it.id }.toSet()
            listOf(EntrySort.TITLE, EntrySort.MODIFIED).forEach { sort ->
                assertEquals(listOf("c", "a"),
                    EntryListFilters(recent = true, sort = sort).select(vault, all, today, recent).map { it.id })
            }
            assertEquals(listOf("t"), EntryListFilters(recent = true, trash = true).select(vault, all, today, recent).map { it.id })
            assertEquals(listOf("a"), EntryListFilters(recent = true).select(vault, setOf("a"), today, recent).map { it.id })
            assertTrue(EntryListFilters(recent = true).ids(vault).isEmpty())
            assertEquals(listOf("a", "b", "c"), EntryListFilters().select(vault, all, today, recent).map { it.id })
        }
    }

    @Test fun `keyed sorting keeps the order of comparing the parsed values per comparison`() {
        val titles = listOf("alpha", "Alpha", "ALPHA", "beta", "Beta", "Ärger", "zeta", "Zeta", "", "10", "9")
        val instants = listOf("2026-01-01T00:00:00Z", "2026-01-01T01:00:00+01:00", "2026-01-01T00:00:00.5Z",
            "2025-12-31T23:59:59Z", "2026-06-01T12:00:00-02:00")
        val random = java.util.Random(7)
        val entries = (0 until 400).map { index ->
            entry("id-%03d".format(random.nextInt(1000)) + "-$index", title = titles[random.nextInt(titles.size)],
                modified = instants[random.nextInt(instants.size)],
                expires = if (random.nextInt(4) == 0) null else today.plusDays(random.nextInt(5).toLong() - 2))
        }
        val titleOrder = compareBy<Entry> { it.title.lowercase(java.util.Locale.ROOT) }.thenBy { it.title }.thenBy { it.id }
        val reference = mapOf(
            EntrySort.TITLE to titleOrder,
            EntrySort.MODIFIED to compareByDescending<Entry> { java.time.Instant.parse(it.modifiedAt) }.then(titleOrder),
            EntrySort.EXPIRY to compareBy<Entry, LocalDate?>(nullsLast()) { it.expiresOn?.let(LocalDate::parse) }.then(titleOrder),
        )
        Vault(entries = entries).use { vault ->
            EntrySort.entries.forEach { sort ->
                val expected = vault.entries.sortedWith(reference.getValue(sort)).map { it.id }
                assertEquals(expected, sortEntries(vault.entries, sort).map { it.id }, sort.name)
                assertEquals(expected, sortEntries(vault.entries.shuffled(random), sort).map { it.id }, sort.name)
                assertEquals(expected, EntryListFilters(sort = sort).ids(vault), sort.name)
            }
        }
    }
}
