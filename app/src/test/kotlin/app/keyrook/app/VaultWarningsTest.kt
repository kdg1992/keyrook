// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import app.keyrook.core.security.EntryHealth
import app.keyrook.core.security.HealthIssue
import app.keyrook.core.security.VaultHealth
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class VaultWarningsTest {
    @Test fun `counts entries per reason and in total`() {
        val counts = warningCounts(listOf(
            EntryHealth("a", setOf(HealthIssue.EXPIRED, HealthIssue.SHORT_OR_REPETITIVE_PASSWORD)),
            EntryHealth("b", setOf(HealthIssue.REUSED_PASSWORD)),
            EntryHealth("c", setOf(HealthIssue.REUSED_PASSWORD, HealthIssue.SHORT_OR_REPETITIVE_PASSWORD)),
            EntryHealth("d", setOf(HealthIssue.EXPIRING_SOON)),
        ))
        assertEquals(WarningCounts(expired = 1, expiringSoon = 1, weak = 2, reused = 2, entries = 4), counts)
        assertEquals(2, counts.count(HealthIssue.REUSED_PASSWORD))
        assertEquals(1, counts.count(HealthIssue.EXPIRED))
        assertEquals(WarningCounts(), warningCounts(emptyList()))
    }

    @Test fun `repeated entries are merged and findings without issues ignored`() {
        val findings = listOf(EntryHealth("a", setOf(HealthIssue.EXPIRED)), EntryHealth("b", emptySet()),
            EntryHealth("a", setOf(HealthIssue.EXPIRED, HealthIssue.REUSED_PASSWORD)))
        assertEquals(WarningCounts(expired = 1, reused = 1, entries = 1), warningCounts(findings))
        assertEquals(mapOf("a" to setOf(HealthIssue.EXPIRED, HealthIssue.REUSED_PASSWORD)), warningIssues(findings))
    }

    @Test fun `card markers name password reasons only and in a fixed order`() {
        assertEquals(listOf(HealthIssue.SHORT_OR_REPETITIVE_PASSWORD, HealthIssue.REUSED_PASSWORD),
            passwordMarkers(setOf(HealthIssue.REUSED_PASSWORD, HealthIssue.EXPIRED, HealthIssue.SHORT_OR_REPETITIVE_PASSWORD)))
        assertEquals(emptyList<HealthIssue>(), passwordMarkers(setOf(HealthIssue.EXPIRED, HealthIssue.EXPIRING_SOON)))
    }

    @Test fun `core health findings aggregate without password values`() {
        val date = "2026-09-24T00:00:00Z"
        fun field(value: String, hidden: Boolean = false) = Field(Secret(value.toCharArray()), hidden)
        fun entry(password: String, expires: String? = null, trash: Boolean = false) = Entry(UUID.randomUUID().toString(),
            "synthetic", EntryData.Web(field(""), field("admin"), field(password, true)), date, date, expiresOn = expires,
            deletedAt = if (trash) date else null)
        Vault(entries = listOf(entry("Synthetic-reused-123!"), entry("Synthetic-reused-123!", "2026-10-01"),
            entry("short", "2026-09-01"), entry("short", trash = true), entry("Synthetic-unique-456!"))).use { vault ->
            val findings = VaultHealth(Clock.fixed(Instant.parse(date), ZoneOffset.UTC)).inspect(vault)
            assertEquals(WarningCounts(expired = 1, expiringSoon = 1, weak = 1, reused = 2, entries = 3), warningCounts(findings))
            assertFalse(warningIssues(findings).toString().contains("Synthetic"))
            assertFalse(vault.entries.last().id in warningIssues(findings))
        }
    }
}
