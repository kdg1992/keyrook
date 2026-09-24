// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.model.*
import app.keyrook.core.security.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VaultHealthTest {
    private val health = VaultHealth(Clock.fixed(Instant.parse(DATE), ZoneOffset.UTC))
    private fun entry(password: String, expiry: String? = null, deleted: Boolean = false) =
        Entry(id(), "synthetic", EntryData.Web(field(""), field(""), field(password)), DATE, DATE,
            expiresOn = expiry, deletedAt = if (deleted) DATE else null)

    @Test fun `duplicates include all active affected entries but not trash`() {
        Vault(entries = listOf(entry("Synthetic-password-123!"), entry("Synthetic-password-123!"),
            entry("Synthetic-password-123!", deleted = true))).use { vault ->
            val result = health.inspect(vault)
            assertEquals(vault.entries.take(2).map { it.id }.toSet(), result.map { it.entryId }.toSet())
            assertTrue(result.all { it.issues == setOf(HealthIssue.REUSED_PASSWORD) })
            assertFalse(result.toString().contains("Synthetic-password"))
            vault.entries[0].data.fields().last().value.useChars { assertEquals("Synthetic-password-123!", String(it)) }
        }
    }

    @Test fun `expiry window and weak heuristics remain independent`() {
        Vault(entries = listOf(entry("short", "2026-09-22"), entry("abcdefghijklmnopqrstuvwxyz", "2026-10-23"),
            entry("aaaaaaaaaaaaaaaaaaa", "2026-10-24"), entry("Test-Synthetic-Unique-123", "2026-09-23"))).use { vault ->
            val result = health.inspect(vault).associate { it.entryId to it.issues }
            assertEquals(setOf(HealthIssue.EXPIRED, HealthIssue.SHORT_OR_REPETITIVE_PASSWORD), result[vault.entries[0].id])
            assertEquals(setOf(HealthIssue.EXPIRING_SOON), result[vault.entries[1].id])
            assertEquals(setOf(HealthIssue.SHORT_OR_REPETITIVE_PASSWORD), result[vault.entries[2].id])
            assertEquals(setOf(HealthIssue.EXPIRING_SOON), result[vault.entries[3].id])
        }
    }

    @Test fun `empty passwords are neither weak nor reused but other checks remain`() {
        Vault(entries = listOf(entry(""), entry(""), entry("", "2026-09-22"),
            Entry(id(), "synthetic", EntryData.Custom(mapOf("password" to field(""), "passphrase" to field("short"))), DATE, DATE),
            Entry(id(), "synthetic", EntryData.Ssh(SshKeyType.ED25519, field(""), field(""), field(""), field("")), DATE, DATE))).use { vault ->
            val result = health.inspect(vault).associate { it.entryId to it.issues }
            assertEquals(mapOf(vault.entries[2].id to setOf(HealthIssue.EXPIRED),
                vault.entries[3].id to setOf(HealthIssue.SHORT_OR_REPETITIVE_PASSWORD)), result)
        }
    }

    @Test fun `passwords on the same entry do not create false reuse alerts`() {
        Vault(entries = listOf(Entry(id(), "synthetic", EntryData.Custom(mapOf(
            "password" to field("Synthetic-password-123!"), "passphrase" to field("Synthetic-password-123!"))), DATE, DATE))).use {
            assertTrue(health.inspect(it).isEmpty())
        }
    }

    private fun aged(password: String, created: String, modified: String, vararg history: Pair<String, String>) =
        Entry(id(), "synthetic", web("", "", password), created, modified,
            history = history.map { (changed, old) -> HistoryItem(changed, web("", "", old)) })
    private fun web(url: String, user: String, password: String = "Synthetic-unique-${id()}") =
        EntryData.Web(field(url, false), field(user), field(password))

    @Test fun `password age follows the last password change in history, not other edits`() {
        val password = "Synthetic-current-123!"
        Vault(entries = listOf(
            // Password replaced 2025-06-01, later only the title changed: old.
            aged(password, "2024-01-01T00:00:00Z", DATE, "2025-06-01T00:00:00Z" to "Synthetic-previous-1!",
                DATE to password),
            // Password replaced 2026-01-01, an older change before that: recent.
            aged(password, "2020-01-01T00:00:00Z", DATE, "2019-01-01T00:00:00Z" to "Synthetic-older-222!",
                "2026-01-01T00:00:00Z" to "Synthetic-previous-2!", DATE to password),
            // History without a password change: dates from creation.
            aged(password, "2020-01-01T00:00:00Z", DATE, DATE to password),
            // No history: the last edit is the only known date.
            aged(password, "2020-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
            aged(password, "2020-01-01T00:00:00Z", "2025-09-22T00:00:00Z"),
            // Unset password has no age.
            aged("", "2020-01-01T00:00:00Z", "2020-01-01T00:00:00Z"),
        )).use { vault ->
            fun old(result: List<EntryHealth>) =
                result.filter { HealthIssue.OLD_PASSWORD in it.issues }.map { it.entryId }.toSet()
            val result = health.inspect(vault)
            assertEquals(setOf(vault.entries[0].id, vault.entries[2].id, vault.entries[4].id), old(result))
            assertEquals(setOf(vault.entries[2].id), old(health.inspect(vault, maxPasswordAgeDays = 600)))
            assertFalse(result.toString().contains("Synthetic"))
        }
    }

    @Test fun `a full history dates an unchanged password from its oldest retained item`() {
        val password = "Synthetic-current-123!"
        val history = (0 until 100).map { "2026-01-01T00:00:00Z" to password }.toTypedArray()
        Vault(entries = listOf(aged(password, "2020-01-01T00:00:00Z", DATE, *history))).use {
            assertTrue(health.inspect(it).isEmpty())
        }
    }

    @Test fun `duplicates match type, normalized host and user name regardless of passwords`() {
        fun entry(data: EntryData, deleted: Boolean = false) =
            Entry(id(), "synthetic", data, DATE, DATE, deletedAt = if (deleted) DATE else null)
        Vault(entries = listOf(
            entry(web(" HTTPS://Example.invalid/Login/ ", "Admin")),
            entry(web("https://example.invalid/login", " admin")),
            entry(web("https://example.invalid/login", "other")),
            entry(EntryData.Panel(field("https://example.invalid/login"), field("admin"), field("Synthetic-panel-123!"), field(""))),
            entry(web("https://example.invalid/login", "admin"), deleted = true),
            entry(web("", "admin")), entry(web("", "admin")),
            entry(web("https://blank.invalid", "")), entry(web("https://blank.invalid/", "")),
            entry(EntryData.Server(field("Host.invalid"), 22, field("root"), field(""), field(""), field(""))),
            entry(EntryData.Server(field("host.invalid"), 2222, field("ROOT"), field(""), field(""), field(""))),
            entry(EntryData.Custom(mapOf("URL" to field("https://kp.invalid/"), "UserName" to field("kp-user")))),
            entry(EntryData.Custom(mapOf("url1" to field("https://KP.invalid"), "username" to field("KP-user")))),
        )).use { vault ->
            val result = health.inspect(vault)
            val ids = vault.entries.map { it.id }
            assertEquals(setOf(ids[0], ids[1], ids[9], ids[10], ids[11], ids[12]),
                result.filter { HealthIssue.DUPLICATE_ENTRY in it.issues }.map { it.entryId }.toSet())
            assertTrue(result.none { HealthIssue.REUSED_PASSWORD in it.issues })
            assertFalse(result.toString().contains("invalid") || result.toString().contains("admin"))
        }
    }
}
