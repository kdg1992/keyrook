// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.security

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class HealthIssue { EXPIRED, EXPIRING_SOON, SHORT_OR_REPETITIVE_PASSWORD, REUSED_PASSWORD, OLD_PASSWORD, DUPLICATE_ENTRY }
data class EntryHealth(val entryId: String, val issues: Set<HealthIssue>)

/**
 * Local heuristics, not a strength certificate. Results contain IDs and reasons, never secret values.
 *
 * Empty passwords are not checked for strength, reuse or age. [HealthIssue.OLD_PASSWORD] dates the current passwords
 * from the latest history item whose passwords differ from them, because a history item holds the data that was
 * replaced at its time. Without such an item they date from entry creation, or from the oldest retained item when the
 * history is full; an entry without any history dates from its last edit. [HealthIssue.DUPLICATE_ENTRY] marks active
 * entries of the same type with the same host or URL and the same user name, compared case-insensitively without
 * surrounding blanks and trailing slashes, independent of their passwords.
 */
class VaultHealth(private val clock: Clock = Clock.systemDefaultZone()) {
    fun inspect(vault: Vault, warningDays: Long = EXPIRY_WARNING_DAYS,
                maxPasswordAgeDays: Long = PASSWORD_MAX_AGE_DAYS): List<EntryHealth> {
        require(warningDays in 0..365)
        require(maxPasswordAgeDays in 1..3650)
        val today = LocalDate.now(clock)
        val oldBefore = Instant.now(clock).minus(Duration.ofDays(maxPasswordAgeDays))
        val issues = linkedMapOf<String, MutableSet<HealthIssue>>()
        val groups = mutableMapOf<Digest, MutableSet<String>>()
        val identities = mutableMapOf<Digest, MutableSet<String>>()
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val mac = Mac.getInstance("HmacSHA256")
        try {
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            fun add(id: String, issue: HealthIssue) { issues.getOrPut(id) { linkedSetOf() }.add(issue) }
            fun group(target: MutableMap<Digest, MutableSet<String>>, digest: Digest, id: String) {
                val existing = target[digest]
                if (existing == null) target[digest] = mutableSetOf(id)
                else { existing.add(id); digest.clear() }
            }
            for (entry in vault.entries.filter { it.deletedAt == null }) {
                entry.expiresOn?.let {
                    val date = LocalDate.parse(it)
                    when {
                        date.isBefore(today) -> add(entry.id, HealthIssue.EXPIRED)
                        !date.isAfter(today.plusDays(warningDays)) -> add(entry.id, HealthIssue.EXPIRING_SOON)
                    }
                }
                val current = passwords(entry.data)
                for (password in current) {
                    password.useChars { chars ->
                        if (chars.size < 14 || chars.toSet().size < 4) add(entry.id, HealthIssue.SHORT_OR_REPETITIVE_PASSWORD)
                    }
                    group(groups, password.useUtf8 { Digest(mac.doFinal(it)) }, entry.id)
                }
                if (current.isNotEmpty() && passwordSince(entry, current) < oldBefore) add(entry.id, HealthIssue.OLD_PASSWORD)
                identity(mac, entry.data)?.let { group(identities, it, entry.id) }
            }
            groups.values.filter { it.size > 1 }.forEach { ids -> ids.forEach { add(it, HealthIssue.REUSED_PASSWORD) } }
            identities.values.filter { it.size > 1 }.forEach { ids -> ids.forEach { add(it, HealthIssue.DUPLICATE_ENTRY) } }
            return issues.map { EntryHealth(it.key, it.value.toSet()) }
        } finally { key.fill(0); groups.keys.forEach { it.clear() }; identities.keys.forEach { it.clear() } }
    }

    /** When the [current] passwords of [entry] were set, as far as its history tells; see the class comment. */
    private fun passwordSince(entry: Entry, current: List<Secret>): Instant {
        val changed = entry.history.filterNot { samePasswords(passwords(it.data), current) }
            .maxOfOrNull { Instant.parse(it.changedAt) }
        return changed ?: when {
            entry.history.isEmpty() -> Instant.parse(entry.modifiedAt)
            entry.history.size >= MAX_HISTORY -> entry.history.minOf { Instant.parse(it.changedAt) }
            else -> Instant.parse(entry.createdAt)
        }
    }

    /** Compares through temporary character arrays that [Secret.useChars] erases. */
    private fun samePasswords(a: List<Secret>, b: List<Secret>): Boolean =
        a.size == b.size && a.indices.all { i -> a[i].useChars { x -> b[i].useChars { y -> x.contentEquals(y) } } }

    /** Non-empty passwords of [data]; an unset password is not a finding. */
    private fun passwords(data: EntryData): List<Secret> = when (data) {
        is EntryData.Web -> listOf(data.password.value)
        is EntryData.Transfer -> listOf(data.password.value)
        is EntryData.Email -> listOf(data.password.value)
        is EntryData.Panel -> listOf(data.password.value)
        is EntryData.Server -> listOf(data.password.value)
        is EntryData.Ssh -> listOf(data.passphrase.value)
        is EntryData.Custom -> data.values.filterKeys { it.lowercase() in PASSWORD_KEYS }.values.map { it.value }
        is EntryData.Domain -> emptyList()
    }.filter { secret -> secret.useChars { it.isNotEmpty() } }

    /** Keyed digest of the type, normalized host or URL and user name; null if either is empty or the type has none. */
    private fun identity(mac: Mac, data: EntryData): Digest? {
        fun custom(values: Map<String, Field>, keys: Set<String>): Secret? = values.entries
            .filter { it.key.lowercase() in keys }.map { it.value.value }
            .firstOrNull { secret -> secret.useChars { chars -> chars.any { !it.isWhitespace() } } }
        val (host, user) = when (data) {
            is EntryData.Web -> data.url.value to data.username.value
            is EntryData.Transfer -> data.host.value to data.username.value
            is EntryData.Email -> data.address.value to data.username.value
            is EntryData.Panel -> data.url.value to data.username.value
            is EntryData.Server -> data.host.value to data.username.value
            is EntryData.Custom -> (custom(data.values, HOST_KEYS) ?: return null) to (custom(data.values, USER_KEYS) ?: return null)
            is EntryData.Ssh, is EntryData.Domain -> return null
        }
        mac.update(data::class.java.simpleName.toByteArray(Charsets.UTF_8))
        for (part in listOf(host, user)) {
            val normalized = part.useChars { normalize(it) }
            try {
                if (normalized.isEmpty()) { mac.reset(); return null }
                Secret(normalized).use { secret ->
                    secret.useUtf8 { bytes -> mac.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); mac.update(bytes) }
                }
            } finally { normalized.fill('\u0000') }
        }
        return Digest(mac.doFinal())
    }

    /** Lowercase copy without surrounding blanks and trailing slashes; the caller erases it. */
    private fun normalize(chars: CharArray): CharArray {
        var start = 0
        var end = chars.size
        while (start < end && chars[start].isWhitespace()) start++
        while (end > start && (chars[end - 1].isWhitespace() || chars[end - 1] == '/')) end--
        return CharArray(end - start) { chars[start + it].lowercaseChar() }
    }

    companion object {
        /** Days before an expiry date (inclusive) that count as [HealthIssue.EXPIRING_SOON] by default. */
        const val EXPIRY_WARNING_DAYS = 30L
        /** Days after which an unchanged password counts as [HealthIssue.OLD_PASSWORD] by default. */
        const val PASSWORD_MAX_AGE_DAYS = 365L
        /** History items the editor retains per entry; a full history may have lost its oldest items. */
        private const val MAX_HISTORY = 100
        private val PASSWORD_KEYS = setOf("password", "passwort", "passphrase")
        private val HOST_KEYS = setOf("url", "url1", "uri", "host")
        private val USER_KEYS = setOf("username", "user", "benutzername")
    }

    private class Digest(private val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Digest && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        fun clear() = bytes.fill(0)
    }
}
