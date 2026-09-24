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
}
