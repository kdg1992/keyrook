// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.security

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.Vault
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Answers one range query of the Pwned Passwords service: the raw body for a five-character uppercase hexadecimal SHA-1
 * prefix. Implementations perform the only network access of a [BreachCheck] and throw on any transport or status
 * problem; tests inject fakes. Calls may come from several threads at once and should react to interruption.
 */
fun interface RangeSource {
    fun range(prefix: String): ByteArray
}

/** An active entry whose password or passphrase appears [count] times in the service's breach corpus. */
data class BreachFinding(val entryId: String, val modifiedAt: String, val count: Long)

/**
 * The result of one completed run for the vault revision it was planned on. Held in memory only and never persisted.
 * Contains entry IDs, modification times and counts, never passwords or hashes.
 */
data class BreachReport(val vaultId: String, val revision: Long, val passwords: Int, val requests: Int,
                        val findings: List<BreachFinding>) {
    /**
     * Breach counts per entry ID that still apply to [vault]: the same vault and an active entry whose modification
     * time is unchanged, so an edited or trashed entry loses its finding until the next run.
     */
    fun current(vault: Vault): Map<String, Long> {
        if (vault.id != vaultId) return emptyMap()
        val entries = vault.entries.associateBy { it.id }
        return findings.filter { finding ->
            entries[finding.entryId]?.let { it.deletedAt == null && it.modifiedAt == finding.modifiedAt } == true
        }.associate { it.entryId to it.count }
    }
}

/** Why a run ended without a report. Deliberately carries no response content, address or secret. */
class BreachCheckException(val reason: Reason) : Exception("Breach check ended: $reason") {
    enum class Reason { NETWORK, INVALID_RESPONSE, TIMEOUT, CANCELLED }
}

/**
 * Hashes of the non-empty passwords of the active entries of one vault revision, grouped by prefix. Only [requests] and
 * [passwords] are meant for display; suffixes stay in erasable character arrays and are cleared by [close].
 */
class BreachPlan internal constructor(val vaultId: String, val revision: Long,
                                      internal val targets: Map<String, List<BreachTarget>>) : AutoCloseable {
    /** One request per distinct prefix. */
    val requests: Int get() = targets.size
    val passwords: Int = targets.values.sumOf { it.size }

    override fun close() { targets.values.forEach { list -> list.forEach { it.suffix.fill('\u0000') } } }
    override fun toString(): String = "BreachPlan(passwords=$passwords, requests=$requests)"
}

internal class BreachTarget(val entryId: String, val modifiedAt: String, val suffix: CharArray) {
    override fun toString(): String = "BreachTarget([redacted])"
}

/**
 * Optional, user-started comparison with the Pwned Passwords range API using k-anonymity: only the first five
 * hexadecimal characters of each password's SHA-1 hash leave the process; the returned suffixes are matched locally.
 * The response is untrusted and validated strictly; any failure ends the whole run without a partial report.
 */
object BreachCheck {
    const val RANGE_API = "https://api.pwnedpasswords.com/range/"
    const val PREFIX_LENGTH = 5
    const val SUFFIX_LENGTH = 35
    /** Real padded responses hold roughly one to two thousand lines of about 40 bytes. */
    const val MAX_RANGE_BYTES = 512 * 1024
    const val MAX_RANGE_LINES = 8192
    const val MAX_PARALLELISM = 4
    val DEFAULT_TIMEOUT: Duration = Duration.ofMinutes(10)
    private const val MAX_COUNT_DIGITS = 12
    private val PREFIX = Regex("^[0-9A-F]{5}$")
    private val HEX = "0123456789ABCDEF".toCharArray()

    fun rangeAddress(prefix: String): String {
        require(PREFIX.matches(prefix))
        return RANGE_API + prefix
    }

    /**
     * Hashes every non-empty password of the active entries of [vault], typically the erasable copy passed to
     * `VaultSession.read`. The caller owns and closes the plan.
     */
    fun plan(vault: Vault): BreachPlan {
        val targets = sortedMapOf<String, MutableList<BreachTarget>>()
        try {
            for (entry in vault.entries.filter { it.deletedAt == null }) {
                for (password in passwordSecrets(entry.data)) {
                    val hex = sha1Hex(password)
                    try {
                        val prefix = String(hex, 0, PREFIX_LENGTH)
                        targets.getOrPut(prefix) { mutableListOf() }
                            .add(BreachTarget(entry.id, entry.modifiedAt, hex.copyOfRange(PREFIX_LENGTH, hex.size)))
                    } finally { hex.fill('\u0000') }
                }
            }
        } catch (e: Throwable) {
            targets.values.forEach { list -> list.forEach { it.suffix.fill('\u0000') } }
            throw e
        }
        return BreachPlan(vault.id, vault.revision, targets)
    }

    /**
     * Uppercase hexadecimal SHA-1 of the UTF-8 encoding of [secret] as a new array the caller erases. The encoded bytes
     * and the digest are erased before returning.
     */
    fun sha1Hex(secret: Secret): CharArray = secret.useUtf8 { bytes ->
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes)
        try {
            CharArray(digest.size * 2).also { hex ->
                digest.forEachIndexed { index, byte ->
                    hex[index * 2] = HEX[(byte.toInt() shr 4) and 0x0F]
                    hex[index * 2 + 1] = HEX[byte.toInt() and 0x0F]
                }
            }
        } finally { digest.fill(0) }
    }

    /**
     * Queries [source] once per prefix of [plan], at most [parallelism] at a time, and matches the suffixes locally.
     * [cancelled] is polled while requests run; [progress] receives completed and total requests on the calling thread.
     * Throws [BreachCheckException] on the first transport, status or parse error, on timeout and on cancellation;
     * no partial result is returned then. Running requests are interrupted when the run ends early.
     */
    fun run(plan: BreachPlan, source: RangeSource, cancelled: () -> Boolean = { false },
            progress: (done: Int, total: Int) -> Unit = { _, _ -> }, parallelism: Int = 2,
            timeout: Duration = DEFAULT_TIMEOUT, nanoTime: () -> Long = System::nanoTime): BreachReport {
        require(parallelism in 1..MAX_PARALLELISM)
        require(!timeout.isNegative && !timeout.isZero)
        val total = plan.requests
        val counts = linkedMapOf<String, Pair<String, Long>>()
        if (total > 0) {
            val deadline = nanoTime() + timeout.toNanos()
            val pool = Executors.newFixedThreadPool(minOf(parallelism, total)) { task ->
                Thread(task, "breach-check").apply { isDaemon = true }
            }
            try {
                val completion = ExecutorCompletionService<List<Pair<BreachTarget, Long>>>(pool)
                plan.targets.forEach { (prefix, targets) ->
                    completion.submit {
                        val body = try { source.range(prefix) } catch (_: InterruptedException) {
                            throw BreachCheckException(BreachCheckException.Reason.CANCELLED)
                        } catch (_: Exception) {
                            throw BreachCheckException(BreachCheckException.Reason.NETWORK)
                        }
                        val found = matchRange(body, targets.map { it.suffix })
                        targets.indices.filter { found[it] > 0 }.map { targets[it] to found[it] }
                    }
                }
                var done = 0
                while (done < total) {
                    if (cancelled()) throw BreachCheckException(BreachCheckException.Reason.CANCELLED)
                    val remaining = deadline - nanoTime()
                    if (remaining <= 0) throw BreachCheckException(BreachCheckException.Reason.TIMEOUT)
                    val future = try { completion.poll(minOf(remaining, POLL_NANOS), TimeUnit.NANOSECONDS) }
                        catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw BreachCheckException(BreachCheckException.Reason.CANCELLED)
                        } ?: continue
                    val matches = try { future.get() } catch (e: ExecutionException) {
                        throw e.cause as? BreachCheckException ?: BreachCheckException(BreachCheckException.Reason.INVALID_RESPONSE)
                    }
                    matches.forEach { (target, count) ->
                        val previous = counts[target.entryId]?.second ?: 0
                        if (count > previous) counts[target.entryId] = target.modifiedAt to count
                    }
                    done++
                    progress(done, total)
                }
            } finally { pool.shutdownNow() }
        }
        if (cancelled()) throw BreachCheckException(BreachCheckException.Reason.CANCELLED)
        val findings = counts.map { (id, value) -> BreachFinding(id, value.first, value.second) }
        return BreachReport(plan.vaultId, plan.revision, plan.passwords, total, findings)
    }

    /**
     * Validates a whole range response and returns, per suffix in [suffixes], its breach count or 0. The body must be
     * bounded ASCII with one `SUFFIX:COUNT` line per record (LF or CRLF, one optional final line break), 35 hexadecimal
     * characters and a decimal count. Padding records with count 0 are accepted but never match. Anything else is
     * refused with [BreachCheckException.Reason.INVALID_RESPONSE].
     */
    fun matchRange(body: ByteArray, suffixes: List<CharArray>): LongArray {
        fun invalid(): Nothing = throw BreachCheckException(BreachCheckException.Reason.INVALID_RESPONSE)
        if (body.isEmpty() || body.size > MAX_RANGE_BYTES) invalid()
        val result = LongArray(suffixes.size)
        var lines = 0
        var start = 0
        while (start < body.size) {
            var end = start
            while (end < body.size && body[end] != '\n'.code.toByte()) end++
            var last = end
            if (last > start && body[last - 1] == '\r'.code.toByte()) last--
            if (++lines > MAX_RANGE_LINES) invalid()
            val length = last - start
            if (length < SUFFIX_LENGTH + 2 || length > SUFFIX_LENGTH + 1 + MAX_COUNT_DIGITS) invalid()
            for (i in 0 until SUFFIX_LENGTH) if (hexValue(body[start + i]) < 0) invalid()
            if (body[start + SUFFIX_LENGTH] != ':'.code.toByte()) invalid()
            var count = 0L
            for (i in start + SUFFIX_LENGTH + 1 until last) {
                val digit = body[i] - '0'.code.toByte()
                if (digit !in 0..9) invalid()
                count = count * 10 + digit
            }
            if (count > 0) suffixes.forEachIndexed { index, suffix ->
                if (suffix.size == SUFFIX_LENGTH && (0 until SUFFIX_LENGTH).all { i ->
                        hexValue(body[start + i]) == hexValue(suffix[i].code.toByte()) }) {
                    result[index] = maxOf(result[index], count)
                }
            }
            start = end + 1
        }
        return result
    }

    private const val POLL_NANOS = 100_000_000L

    /** Value of an ASCII hexadecimal digit in either case, or -1. */
    private fun hexValue(byte: Byte): Int = when (val c = byte.toInt()) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        else -> -1
    }
}
