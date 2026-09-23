// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.security

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class HealthIssue { EXPIRED, EXPIRING_SOON, SHORT_OR_REPETITIVE_PASSWORD, REUSED_PASSWORD }
data class EntryHealth(val entryId: String, val issues: Set<HealthIssue>)

/** Local heuristics, not a strength certificate. Results contain IDs and reasons, never secret values. */
class VaultHealth(private val clock: Clock = Clock.systemDefaultZone()) {
    fun inspect(vault: Vault, warningDays: Long = 30): List<EntryHealth> {
        require(warningDays in 0..365)
        val today = LocalDate.now(clock)
        val issues = linkedMapOf<String, MutableSet<HealthIssue>>()
        val groups = mutableMapOf<Digest, MutableSet<String>>()
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val mac = Mac.getInstance("HmacSHA256")
        try {
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            fun add(id: String, issue: HealthIssue) { issues.getOrPut(id) { linkedSetOf() }.add(issue) }
            for (entry in vault.entries.filter { it.deletedAt == null }) {
                entry.expiresOn?.let {
                    val date = LocalDate.parse(it)
                    when {
                        date.isBefore(today) -> add(entry.id, HealthIssue.EXPIRED)
                        !date.isAfter(today.plusDays(warningDays)) -> add(entry.id, HealthIssue.EXPIRING_SOON)
                    }
                }
                for (password in passwords(entry.data)) {
                    password.useChars { chars ->
                        if (chars.size < 14 || chars.toSet().size < 4) add(entry.id, HealthIssue.SHORT_OR_REPETITIVE_PASSWORD)
                    }
                    val digest = password.useUtf8 { Digest(mac.doFinal(it)) }
                    val existing = groups[digest]
                    if (existing == null) groups[digest] = mutableSetOf(entry.id)
                    else { existing.add(entry.id); digest.clear() }
                }
            }
            groups.values.filter { it.size > 1 }.forEach { ids -> ids.forEach { add(it, HealthIssue.REUSED_PASSWORD) } }
            return issues.map { EntryHealth(it.key, it.value.toSet()) }
        } finally { key.fill(0); groups.keys.forEach { it.clear() } }
    }

    private fun passwords(data: EntryData): List<Secret> = when (data) {
        is EntryData.Web -> listOf(data.password.value)
        is EntryData.Transfer -> listOf(data.password.value)
        is EntryData.Email -> listOf(data.password.value)
        is EntryData.Panel -> listOf(data.password.value)
        is EntryData.Server -> listOf(data.password.value)
        is EntryData.Ssh -> listOf(data.passphrase.value)
        is EntryData.Custom -> data.values.filterKeys { it.lowercase() in setOf("password", "passwort", "passphrase") }.values.map { it.value }
        is EntryData.Domain -> emptyList()
    }

    private class Digest(private val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Digest && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        fun clear() = bytes.fill(0)
    }
}
