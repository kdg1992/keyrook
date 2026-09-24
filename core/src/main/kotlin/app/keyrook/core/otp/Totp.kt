// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.otp

import app.keyrook.core.crypto.Secret
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC variants allowed by RFC 6238, named as in `otpauth` URIs. */
enum class TotpAlgorithm(internal val mac: String) { SHA1("HmacSHA1"), SHA256("HmacSHA256"), SHA512("HmacSHA512") }

/** Deliberately generic: never names the rejected part or repeats any input. */
class InvalidTotpException : IllegalArgumentException("Invalid TOTP secret")

/**
 * One time-based code, valid from [validFrom] (inclusive) to [validUntil] (exclusive). The digits are a secret for
 * that interval: callers read them through [code] and close this object once the value is no longer displayed.
 */
class TotpCode internal constructor(val code: Secret, val validFrom: Instant, val validUntil: Instant) : AutoCloseable {
    val periodSeconds: Long get() = Duration.between(validFrom, validUntil).seconds

    /** Whole seconds, rounded up, until [validUntil]; zero once the code has expired. */
    fun remainingSeconds(now: Instant): Long {
        val left = Duration.between(now, validUntil)
        if (left.isNegative || left.isZero) return 0
        return left.seconds + if (left.nano > 0) 1 else 0
    }

    override fun close() = code.close()
    override fun toString(): String = "TotpCode([redacted], validUntil=$validUntil)"
}

/** Decoded key and parameters. The key is owned and erased by [close]; it is never kept beyond one computation. */
internal class TotpParameters(val key: ByteArray, val algorithm: TotpAlgorithm, val digits: Int, val periodSeconds: Int) :
    AutoCloseable {
    fun code(at: Instant): TotpCode {
        val seconds = at.epochSecond
        if (seconds < 0) throw InvalidTotpException()
        val counter = seconds / periodSeconds
        val digitsOut = Totp.hotp(key, counter, digits, algorithm)
        try {
            val from = Instant.ofEpochSecond(counter * periodSeconds)
            return TotpCode(Secret(digitsOut), from, from.plusSeconds(periodSeconds.toLong()))
        } finally { digitsOut.fill('\u0000') }
    }

    override fun close() { key.fill(0) }
    override fun toString(): String = "TotpParameters([redacted])"
}

/**
 * RFC 6238 time-based one-time codes on top of RFC 4226 HOTP, computed with the JDK's HMAC implementations.
 *
 * Accepted stored values:
 * - a plain RFC 4648 Base32 secret: case-insensitive, spaces and hyphens ignored, padding optional but correct when
 *   present, unused trailing bits zero; SHA-1, 6 digits and 30 seconds apply;
 * - `otpauth://totp/<label>?secret=…` with the optional parameters `algorithm` (SHA1, SHA256, SHA512), `digits` (6–8)
 *   and `period` (15–120 seconds), each at most once. Other parameters such as `issuer` or `image` are ignored but must
 *   be well-formed `name=value` pairs. Other types, fragments, whitespace and malformed escapes are refused. Label and
 *   ignored parameters do not affect the code.
 *
 * Decoded keys must hold 10 to 128 bytes. Every failure is an [InvalidTotpException] without details.
 */
object Totp {
    const val MAX_INPUT_CHARS = 2048
    private const val MIN_KEY_BYTES = 10
    private const val MAX_KEY_BYTES = 128
    private const val DEFAULT_DIGITS = 6
    private const val DEFAULT_PERIOD = 30
    private const val SCHEME = "otpauth://totp/"
    private val POWERS = intArrayOf(1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000)

    /** The code for [at]. [secret] is only read; decoded key bytes are erased before this returns. */
    fun code(secret: Secret, at: Instant): TotpCode = secret.useChars { chars -> parse(chars).use { it.code(at) } }

    /** Whether [chars] is an accepted stored value. The caller keeps ownership of [chars]. */
    fun isValid(chars: CharArray): Boolean = try { parse(chars).close(); true } catch (_: InvalidTotpException) { false }

    fun isValid(secret: Secret): Boolean = secret.useChars(::isValid)

    internal fun parse(chars: CharArray): TotpParameters {
        var start = 0
        var end = chars.size
        while (start < end && chars[start].isWhitespace()) start++
        while (end > start && chars[end - 1].isWhitespace()) end--
        if (start == end || end - start > MAX_INPUT_CHARS) fail()
        return if ((start until end).any { chars[it] == ':' }) parseUri(chars, start, end)
        else TotpParameters(base32(chars, start, end), TotpAlgorithm.SHA1, DEFAULT_DIGITS, DEFAULT_PERIOD)
    }

    /** RFC 4226 section 5.3: dynamic truncation of the HMAC over the big-endian counter. */
    internal fun hotp(key: ByteArray, counter: Long, digits: Int, algorithm: TotpAlgorithm): CharArray {
        require(digits in 6..8 && counter >= 0)
        val message = ByteArray(8) { index -> (counter ushr (56 - 8 * index)).toByte() }
        val mac = Mac.getInstance(algorithm.mac)
        var hash: ByteArray? = null
        try {
            mac.init(SecretKeySpec(key, algorithm.mac))
            val digest = mac.doFinal(message)
            hash = digest
            val offset = digest[digest.size - 1].toInt() and 0x0f
            var value = ((digest[offset].toInt() and 0x7f) shl 24) or ((digest[offset + 1].toInt() and 0xff) shl 16) or
                ((digest[offset + 2].toInt() and 0xff) shl 8) or (digest[offset + 3].toInt() and 0xff)
            value %= POWERS[digits]
            val result = CharArray(digits)
            for (index in digits - 1 downTo 0) {
                result[index] = '0' + value % 10
                value /= 10
            }
            return result
        } finally {
            hash?.fill(0)
            message.fill(0)
            mac.reset()
        }
    }

    private fun parseUri(chars: CharArray, start: Int, end: Int): TotpParameters {
        if (end - start <= SCHEME.length) fail()
        SCHEME.forEachIndexed { index, expected -> if (chars[start + index].lowercaseChar() != expected) fail() }
        var position = start + SCHEME.length
        val query = (position until end).firstOrNull { chars[it] == '?' } ?: fail()
        checkText(chars, position, query)
        position = query + 1
        var secret: IntRange? = null
        var algorithm: TotpAlgorithm? = null
        var digits: Int? = null
        var period: Int? = null
        while (position <= end) {
            val next = (position until end).firstOrNull { chars[it] == '&' } ?: end
            if (next > position) {
                val equals = (position until next).firstOrNull { chars[it] == '=' } ?: fail()
                if (equals == position) fail()
                val value = equals + 1 until next
                when (parameterName(chars, position, equals)) {
                    "secret" -> { if (secret != null || value.isEmpty()) fail(); secret = value }
                    "algorithm" -> {
                        if (algorithm != null) fail()
                        val name = text(chars, value.first, next).uppercase()
                        algorithm = TotpAlgorithm.entries.firstOrNull { it.name == name } ?: fail()
                    }
                    "digits" -> { if (digits != null) fail(); digits = number(chars, value, 6..8) }
                    "period" -> { if (period != null) fail(); period = number(chars, value, 15..120) }
                    // issuer, image, color and provider-specific parameters do not affect the code.
                    else -> { checkText(chars, position, equals); checkText(chars, value.first, next) }
                }
            }
            position = next + 1
        }
        val range = secret ?: fail()
        val decoded = CharArray(range.last - range.first + 1)
        try {
            val length = percentDecode(chars, range.first, range.last + 1, decoded)
            return TotpParameters(base32(decoded, 0, length), algorithm ?: TotpAlgorithm.SHA1,
                digits ?: DEFAULT_DIGITS, period ?: DEFAULT_PERIOD)
        } finally { decoded.fill('\u0000') }
    }

    /** Label and issuer: ignored for computation, but still limited to printable characters and valid escapes. */
    private fun checkText(chars: CharArray, from: Int, to: Int) {
        var index = from
        while (index < to) {
            val char = chars[index]
            if (char.isWhitespace() || char.isISOControl() || char == '#' || char == '?') fail()
            if (char == '%') {
                if (index + 2 >= to || hex(chars[index + 1]) < 0 || hex(chars[index + 2]) < 0) fail()
                index += 3
            } else index++
        }
    }

    /** The name when it could be a computation parameter, otherwise an empty string for an ignored parameter. */
    private fun parameterName(chars: CharArray, from: Int, to: Int): String =
        if (to - from in 1..16) String(chars, from, to - from) else ""

    /** Parameter names and algorithm values are short public ASCII tokens, so an immutable copy is harmless. */
    private fun text(chars: CharArray, from: Int, to: Int): String {
        if (to - from !in 1..16) fail()
        return String(chars, from, to - from)
    }

    private fun number(chars: CharArray, range: IntRange, allowed: IntRange): Int {
        if (range.isEmpty() || range.count() > 3 || chars[range.first] == '0') fail()
        var value = 0
        for (index in range) {
            val digit = chars[index] - '0'
            if (digit !in 0..9) fail()
            value = value * 10 + digit
        }
        return if (value in allowed) value else fail()
    }

    /** Decodes `%XX` escapes to ASCII characters into [target] and returns the length; other escapes are refused. */
    private fun percentDecode(chars: CharArray, from: Int, to: Int, target: CharArray): Int {
        var index = from
        var length = 0
        while (index < to) {
            val char = chars[index]
            if (char.isWhitespace() || char.isISOControl() || char == '#' || char == '?') fail()
            if (char == '%') {
                if (index + 2 >= to) fail()
                val high = hex(chars[index + 1])
                val low = hex(chars[index + 2])
                if (high < 0 || low < 0 || high >= 8) fail()
                target[length++] = (high * 16 + low).toChar()
                index += 3
            } else {
                target[length++] = char
                index++
            }
        }
        return length
    }

    private fun hex(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }

    private fun base32Value(char: Char): Int = when (char) {
        in 'A'..'Z' -> char - 'A'
        in 'a'..'z' -> char - 'a'
        in '2'..'7' -> char - '2' + 26
        else -> -1
    }

    /** RFC 4648 section 6. The returned array belongs to the caller. */
    private fun base32(chars: CharArray, from: Int, to: Int): ByteArray {
        var symbols = 0
        var padding = 0
        for (index in from until to) {
            val char = chars[index]
            when {
                char == ' ' || char == '-' -> Unit
                char == '=' -> padding++
                base32Value(char) >= 0 -> if (padding > 0) fail() else symbols++
                else -> fail()
            }
        }
        val remainder = symbols % 8
        if (remainder !in intArrayOf(0, 2, 4, 5, 7)) fail()
        if (padding > 0 && (remainder == 0 || padding != 8 - remainder)) fail()
        val size = symbols * 5 / 8
        if (size !in MIN_KEY_BYTES..MAX_KEY_BYTES) fail()
        val key = ByteArray(size)
        var buffer = 0
        var bits = 0
        var written = 0
        try {
            for (index in from until to) {
                val value = base32Value(chars[index])
                if (value < 0) continue
                buffer = (buffer shl 5) or value
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    key[written++] = (buffer ushr bits).toByte()
                    buffer = buffer and ((1 shl bits) - 1)
                }
            }
            if (buffer != 0) fail()
            return key
        } catch (failure: Throwable) {
            key.fill(0)
            throw failure
        }
    }

    private fun fail(): Nothing = throw InvalidTotpException()
}
