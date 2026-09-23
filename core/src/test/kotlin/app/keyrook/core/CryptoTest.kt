// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.crypto.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.charset.CharacterCodingException
import java.util.HexFormat

class CryptoTest {
    @Test fun `Argon2id matches RFC 9106 section 5_3 including optional secret`() {
        // https://www.rfc-editor.org/rfc/rfc9106.html#section-5.3
        val result = VaultCrypto.argon2(ByteArray(32) { 1 }, ByteArray(16) { 2 },
            KdfParameters(32, 3, 4), ByteArray(8) { 3 }, ByteArray(12) { 4 })
        HexFormat.of().formatHex(result) shouldBe "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"
        result.fill(0)
    }

    @Test fun `AES256 GCM matches NIST example 1`() {
        // NIST AES_GCM.pdf, GCM-AES256 Example #1 (pages 21–22).
        val hex = HexFormat.of()
        val key = hex.parseHex("feffe9928665731c6d6a8f9467308308".repeat(2))
        val nonce = hex.parseHex("cafebabefacedbaddecaf888")
        val tag = hex.parseHex("fd2caa16a5832e76aa132c1453eeda7e")
        assertArrayEquals(tag, VaultCrypto.aesGcm(true, key, nonce, byteArrayOf(), byteArrayOf()))
        assertArrayEquals(byteArrayOf(), VaultCrypto.aesGcm(false, key, nonce, byteArrayOf(), tag))
    }

    @Test fun `GCM authenticates additional data and tag`() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { it.toByte() }
        val encrypted = VaultCrypto.aesGcm(true, key, nonce, byteArrayOf(1), byteArrayOf(9, 8, 7))
        assertThrows(AuthenticationException::class.java) { VaultCrypto.aesGcm(false, key, nonce, byteArrayOf(2), encrypted) }
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(AuthenticationException::class.java) { VaultCrypto.aesGcm(false, key, nonce, byteArrayOf(1), encrypted) }
    }

    @Test fun `AES256 GCM matches NIST example 4 with plaintext and AAD`() {
        val hex = HexFormat.of()
        val key = hex.parseHex("feffe9928665731c6d6a8f9467308308".repeat(2))
        val nonce = hex.parseHex("cafebabefacedbaddecaf888")
        val aad = hex.parseHex("3ad77bb40d7a3660a89ecaf32466ef97f5d3d58503b9699de785895a96fdbaaf" +
            "43b1cd7f598ece23881b00e3ed0306887b0c785e27e8ad3f8223207104725dd4")
        val plain = hex.parseHex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
            "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255")
        val expected = hex.parseHex("522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
            "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad" +
            "c06d76f31930fef37acae23ed465ae62")
        assertArrayEquals(expected, VaultCrypto.aesGcm(true, key, nonce, aad, plain))
        assertArrayEquals(plain, VaultCrypto.aesGcm(false, key, nonce, aad, expected))
    }

    @Test fun `secret owns its storage and wipes borrowed copies on exception`() {
        val input = "private".toCharArray()
        val secret = Secret(input)
        input.fill('x')
        var borrowed = charArrayOf()
        assertThrows(IllegalArgumentException::class.java) {
            secret.useChars { borrowed = it; String(it) shouldBe "private"; throw IllegalArgumentException() }
        }
        borrowed.all { it == '\u0000' } shouldBe true
        secret.toString() shouldBe "Secret([redacted])"
        secret.close()
        assertThrows(IllegalStateException::class.java) { secret.useChars { } }
        secret.close()
    }

    @Test fun `UTF8 is exact without normalization and bytes are erased`() {
        var borrowed = byteArrayOf()
        Secret("é\u0000🗝".toCharArray()).use { secret ->
            secret.useUtf8 { borrowed = it; assertArrayEquals("é\u0000🗝".toByteArray(), it) }
        }
        borrowed.all { it == 0.toByte() } shouldBe true
        Secret(charArrayOf('\uD800')).use { secret ->
            assertThrows(CharacterCodingException::class.java) { secret.useUtf8 { } }
        }
    }

    @Test fun `credential copy survives caller closure and key mutation`() {
        val key = KeyFiles.generate()
        credentials(key = key).use { original ->
            original.copy().use { copied ->
                val before = copied.derive(ByteArray(32), testKdf)
                original.close(); key.fill(0)
                assertArrayEquals(before, copied.derive(ByteArray(32), testKdf))
            }
        }
    }

    @Test fun `invalid credential sizes and closed credentials rejected`() {
        Secret(charArrayOf()).use { assertThrows(IllegalArgumentException::class.java) { Credentials(it) } }
        assertThrows(IllegalArgumentException::class.java) { credentials(key = ByteArray(31)) }
        val closed = credentials()
        closed.close()
        assertThrows(IllegalStateException::class.java) { closed.copy() }
        assertThrows(IllegalStateException::class.java) { closed.hasKeyFile() }
        KeyFiles.generate().size shouldBe 32
        assertFalse(KeyFiles.generate().contentEquals(KeyFiles.generate()))
    }
}
