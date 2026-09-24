// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.otp

import app.keyrook.core.crypto.Secret
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class TotpTest {
    private val sha1Key = "12345678901234567890"
    private val sha256Key = "12345678901234567890123456789012"
    private val sha512Key = "1234567890123456789012345678901234567890123456789012345678901234"

    /** Independent RFC 4648 encoder, unpadded, for the ASCII test keys. */
    private fun base32(ascii: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        ascii.toByteArray().forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) { bits -= 5; out.append(alphabet[(buffer ushr bits) and 31]) }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    private fun code(value: String, at: Instant): String =
        Secret(value.toCharArray()).use { secret -> Totp.code(secret, at).use { it.code.useChars(::String) } }

    private fun valid(value: String) = Totp.isValid(value.toCharArray())

    @Test fun `test encoder matches the RFC key encoding`() {
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", base32(sha1Key))
    }

    @Test fun `RFC 4226 appendix D HOTP values`() {
        val expected = listOf("755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489")
        val key = sha1Key.toByteArray()
        expected.forEachIndexed { counter, value ->
            val chars = Totp.hotp(key, counter.toLong(), 6, TotpAlgorithm.SHA1)
            assertEquals(value, String(chars))
        }
    }

    @Test fun `RFC 6238 appendix B TOTP values for all three algorithms`() {
        val vectors = listOf(
            59L to listOf("94287082", "46119246", "90693936"),
            1111111109L to listOf("07081804", "68084774", "25091201"),
            1111111111L to listOf("14050471", "67062674", "99943326"),
            1234567890L to listOf("89005924", "91819424", "93441116"),
            2000000000L to listOf("69279037", "90698825", "38618901"),
            20000000000L to listOf("65353130", "77737706", "47863826"),
        )
        val keys = listOf(TotpAlgorithm.SHA1 to sha1Key, TotpAlgorithm.SHA256 to sha256Key, TotpAlgorithm.SHA512 to sha512Key)
        vectors.forEach { (time, codes) ->
            keys.forEachIndexed { index, (algorithm, key) ->
                val uri = "otpauth://totp/RFC%206238:test?secret=${base32(key)}&algorithm=${algorithm.name}&digits=8&period=30"
                assertEquals(codes[index], code(uri, Instant.ofEpochSecond(time)), "$algorithm at $time")
            }
        }
    }

    @Test fun `plain secrets use SHA-1, six digits and thirty seconds`() {
        val at = Instant.ofEpochSecond(59)
        assertEquals("287082", code(base32(sha1Key), at))
        Secret(base32(sha1Key).toCharArray()).use { secret ->
            Totp.code(secret, Instant.ofEpochSecond(45, 500_000_000)).use { result ->
                assertEquals(Instant.ofEpochSecond(30), result.validFrom)
                assertEquals(Instant.ofEpochSecond(60), result.validUntil)
                assertEquals(30, result.periodSeconds)
                assertEquals(15, result.remainingSeconds(Instant.ofEpochSecond(45, 500_000_000)))
                assertEquals(0, result.remainingSeconds(Instant.ofEpochSecond(60)))
                assertFalse(result.toString().contains(result.code.useChars(::String)))
            }
        }
    }

    @Test fun `base32 decoding edge cases`() {
        val plain = base32(sha1Key)
        val at = Instant.ofEpochSecond(59)
        listOf(plain.lowercase(), plain.chunked(4).joinToString(" "), plain.chunked(4).joinToString("-"),
            "  $plain\n", plain.lowercase().chunked(3).joinToString(" - ")).forEach {
            assertEquals("287082", code(it, at), it)
        }
        // 11 bytes: 18 symbols (remainder 2) padded to 24 with six '='.
        val eleven = base32("12345678901")
        assertEquals(18, eleven.length)
        assertTrue(valid(eleven))
        assertTrue(valid("$eleven======"))
        assertTrue(valid("$eleven== ====".lowercase()))
        assertFalse(valid("$eleven====="), "wrong padding length")
        assertFalse(valid("$plain========"), "padding after a complete block")
        assertFalse(valid("${eleven.dropLast(2)}=A======"), "symbol after padding")
        assertFalse(valid(plain.dropLast(1) + "1"), "digit outside the alphabet")
        assertFalse(valid(plain.dropLast(1) + "_"))
        assertFalse(valid("${plain}A"), "impossible remainder length")
        assertFalse(valid(eleven.dropLast(1) + "B"), "non-zero trailing bits")
        assertFalse(valid(base32("123456789")), "shorter than 80 bits")
        assertTrue(valid(base32("1234567890")))
        assertTrue(valid(base32("x".repeat(128))))
        assertFalse(valid(base32("x".repeat(129))), "longer than 1024 bits")
        assertFalse(valid(""))
        assertFalse(valid("   "))
        assertFalse(valid("A".repeat(Totp.MAX_INPUT_CHARS + 1)))
        assertFalse(valid("ÄEZDGNBVGY3TQOJQ"))
    }

    @Test fun `otpauth URIs are accepted strictly`() {
        val secret = base32(sha1Key)
        val at = Instant.ofEpochSecond(59)
        assertEquals("287082", code("otpauth://totp/Example:alice%40example.com?secret=$secret&issuer=Example", at))
        assertEquals("287082", code("OTPAUTH://TOTP/label?issuer=Ex%20ample&secret=${secret.lowercase()}", at))
        assertEquals("94287082", code("otpauth://totp/?secret=$secret&digits=8&algorithm=sha1", at))
        assertEquals("287082", code("otpauth://totp/x?secret=${secret.take(16)}%20${secret.drop(16)}", at))
        assertTrue(valid("otpauth://totp/x?secret=${base32("12345678901")}%3D%3D%3D%3D%3D%3D"))
        assertTrue(valid("otpauth://totp/x?secret=$secret&period=15"))
        assertTrue(valid("otpauth://totp/x?secret=$secret&period=120&digits=6"))
        Secret("otpauth://totp/x?secret=$secret&period=60".toCharArray()).use { value ->
            Totp.code(value, Instant.ofEpochSecond(59)).use { assertEquals(Instant.ofEpochSecond(60), it.validUntil) }
        }
        val rejected = listOf(
            "otpauth://hotp/x?secret=$secret&counter=1",
            "otpauth://totp/x",
            "otpauth://totp/x?",
            "otpauth://totp/x?issuer=Example",
            "otpauth://totp/x?secret=",
            "otpauth://totp/x?secret=$secret&secret=$secret",
            "otpauth://totp/x?secret=$secret&issuer=a&issuer=b",
            "otpauth://totp/x?secret=$secret&algorithm=MD5",
            "otpauth://totp/x?secret=$secret&algorithm=",
            "otpauth://totp/x?secret=$secret&digits=5",
            "otpauth://totp/x?secret=$secret&digits=9",
            "otpauth://totp/x?secret=$secret&digits=06",
            "otpauth://totp/x?secret=$secret&digits=+6",
            "otpauth://totp/x?secret=$secret&period=14",
            "otpauth://totp/x?secret=$secret&period=121",
            "otpauth://totp/x?secret=$secret&period=30s",
            "otpauth://totp/x?secret=$secret&period=30&period=30",
            "otpauth://totp/x?secret=$secret&image=https://example.com/a.png",
            "otpauth://totp/x?secret=$secret&",
            "otpauth://totp/x?secret=$secret&&digits=6",
            "otpauth://totp/x?secret=$secret&digits",
            "otpauth://totp/x?secret=$secret#fragment",
            "otpauth://totp/a b?secret=$secret",
            "otpauth://totp/x?secret=$secret&issuer=a%2",
            "otpauth://totp/x?secret=$secret&issuer=a%zz",
            "otpauth://totp/x?secret=${secret.take(16)}%C3%84",
            "otpauth://totp/x?SECRET=$secret",
            "otpauth:/totp/x?secret=$secret",
            "https://totp/x?secret=$secret",
            "otpauth://totpx?secret=$secret",
            "otpauth://totp/x?secret=GEZDGNBV",
        )
        rejected.forEach { assertFalse(valid(it), it) }
    }

    @Test fun `failures are generic and never repeat input`() {
        val input = "otpauth://totp/private-label?secret=NOTVALID1&issuer=private"
        val failure = assertThrows(InvalidTotpException::class.java) {
            Secret(input.toCharArray()).use { Totp.code(it, Instant.EPOCH) }
        }
        assertEquals("Invalid TOTP secret", failure.message)
        assertNull(failure.cause)
        assertThrows(InvalidTotpException::class.java) {
            Secret(base32(sha1Key).toCharArray()).use { Totp.code(it, Instant.ofEpochSecond(-1)) }
        }
    }

    @Test fun `decoded keys, codes and inputs are handled as owned secrets`() {
        val chars = base32(sha1Key).toCharArray()
        val parameters = Totp.parse(chars)
        assertArrayEquals(sha1Key.toByteArray(), parameters.key)
        assertEquals("TotpParameters([redacted])", parameters.toString())
        val result = parameters.code(Instant.ofEpochSecond(59))
        parameters.close()
        assertTrue(parameters.key.all { it == 0.toByte() }, "key wiped on close")
        assertEquals(base32(sha1Key), String(chars), "caller input untouched")
        assertEquals("287082", result.code.useChars(::String))
        result.close()
        assertThrows(IllegalStateException::class.java) { result.code.useChars { it.size } }

        Secret(chars).use { secret ->
            Totp.code(secret, Instant.ofEpochSecond(59)).close()
            assertEquals(base32(sha1Key), secret.useChars(::String), "stored secret still usable")
            assertTrue(Totp.isValid(secret))
        }
        chars.fill('\u0000')
    }
}
