// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.ssh

import app.keyrook.core.crypto.Secret
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateCrtKey
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PpkKeyReaderTest {
    private val service = SshKeyService()
    private val publicBlob = HexFormat.of().parseHex(
        "0000000b7373682d65643235353139000000207242b33387688f57ff218bb639f6d9fd213ba54f3100d5b5cb64ca6e85247d56")
    private fun fixture(name: String) = javaClass.getResourceAsStream("/ssh/putty/$name.ppk")!!.use {
        it.readBytes().toString(Charsets.UTF_8)
    }
    private fun import(text: String, password: String = "test-passphrase", action: (SshKeyMaterial) -> Unit) {
        Secret(text.toCharArray()).use { input -> Secret(password.toCharArray()).use { previous ->
            Secret("replacement test phrase".toCharArray()).use { next ->
                service.importKey(input, previous, next).use(action)
                // Import borrows caller-owned secrets without closing them.
                input.useChars { assertEquals(text, String(it)) }
                previous.useChars { assertEquals(password, String(it)) }
            }
        } }
    }
    private fun rejects(text: String, password: String = "test-passphrase") {
        val failure = assertThrows(IllegalArgumentException::class.java) { import(text, password) { fail("Must not import") } }
        assertNull(failure.cause)
        assertFalse(failure.message.orEmpty().contains("ed25519-key-20200105"))
        assertFalse(failure.message.orEmpty().contains(password))
    }

    @Test fun `official PuTTY v2 and v3 clear and encrypted vectors match and reencrypt`() {
        for (name in listOf("input_clear_key", "input_encrypted_key", "v2_clear_key", "v2_encrypted_key")) {
            import(fixture(name)) { key ->
                assertEquals(SshKeyType.ED25519, key.type)
                assertEquals("ssh-ed25519 " + Base64.getEncoder().encodeToString(publicBlob), key.publicKey)
                Secret("replacement test phrase".toCharArray()).use { password ->
                    service.importKey(key.privateKey, password, password).use {
                        assertEquals(key.publicKey, it.publicKey)
                        assertEquals(key.fingerprint, it.fingerprint)
                    }
                }
            }
        }
    }

    @Test fun `unencrypted PPK ignores supplied password and supports CRLF and CR line endings`() {
        for (name in listOf("input_clear_key", "v2_clear_key")) {
            for (ending in listOf("\r\n", "\r")) {
                import(fixture(name).replace("\n", ending), "irrelevant password") { assertEquals(SshKeyType.ED25519, it.type) }
            }
        }
    }

    @Test fun `wrong password and tampered metadata public private or MAC fail for all versions`() {
        for (name in listOf("input_clear_key", "input_encrypted_key", "v2_clear_key", "v2_encrypted_key")) {
            val text = fixture(name)
            if (name.contains("encrypted")) rejects(text, "incorrect password")
            rejects(text.replace("Comment: ed25519-key-20200105", "Comment: ed25519-key-20200105 "))
            rejects(text.replace("ssh-ed25519", "ssh-rsa"))
            rejects(text.replace("JH1W", "JH1X"))
            val privateLine = text.substringAfter("Private-Lines: 1\n").substringBefore('\n')
            rejects(text.replace(privateLine, (if (privateLine[0] == 'A') "B" else "A") + privateLine.drop(1)))
            val mac = text.substringAfter("Private-MAC: ").trimEnd()
            rejects(text.replace(mac, (if (mac[0] == '0') "1" else "0") + mac.drop(1)))
        }
    }

    @Test fun `oversized counts KDF costs duplicate headers and trailing content fail before work`() {
        val clear = fixture("input_clear_key")
        for (text in listOf(clear.replace("Public-Lines: 2", "Public-Lines: 2147483647"),
            clear.replace("Public-Lines: 2", "Public-Lines: 1025"),
            clear.replace("Private-Lines: 1", "Private-Lines: -1"),
            clear.replace("Encryption: none", "Encryption: none\nEncryption: none"),
            clear + "unexpected\n", clear + "\n", clear.replace("File-3:", "File-1:"))) rejects(text)
        val encrypted = fixture("input_encrypted_key")
        for ((from, to) in listOf("Argon2-Memory: 8192" to "Argon2-Memory: 262145",
            "Argon2-Memory: 8192" to "Argon2-Memory: 262144",
            "Argon2-Memory: 8192" to "Argon2-Memory: 7",
            "Argon2-Passes: 13" to "Argon2-Passes: 129",
            "Argon2-Passes: 13" to "Argon2-Passes: 0",
            "Argon2-Parallelism: 1" to "Argon2-Parallelism: 17",
            "Key-Derivation: Argon2id" to "Key-Derivation: unknown")) rejects(encrypted.replace(from, to))
        rejects(clear, "x".repeat(1025))
    }

    @Test fun `RSA4096 clear and encrypted PPK2 and PPK3 import with independently encoded JCE material`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
        val rsa = pair.private as RSAPrivateCrtKey
        val pub = Base64.getDecoder().decode(PublicKeyEntry.toString(pair.public).substringAfter(' '))
        val privateBlob = framing(listOf(rsa.privateExponent.toByteArray(), rsa.primeP.toByteArray(),
            rsa.primeQ.toByteArray(), rsa.crtCoefficient.toByteArray()))
        try {
            for (version in listOf(2, 3)) for (encrypted in listOf(false, true)) {
                import(ppk(version, "ssh-rsa", pub, privateBlob, encrypted)) {
                    assertEquals(SshKeyType.RSA4096, it.type)
                    assertEquals(PublicKeyEntry.toString(pair.public), it.publicKey)
                }
            }
            val malformed = framing(listOf(rsa.privateExponent.toByteArray(), byteArrayOf(1),
                rsa.primeQ.toByteArray(), rsa.crtCoefficient.toByteArray()))
            try { rejects(ppk(2, "ssh-rsa", pub, malformed, false)) } finally { malformed.fill(0) }
        } finally { privateBlob.fill(0) }
    }

    @Test fun `PPK3 Argon2 flavours and exact UTF8 comments interoperate`() {
        val privateBlob = Base64.getDecoder().decode(fixture("input_clear_key").substringAfter("Private-Lines: 1\n").substringBefore('\n'))
        try {
            for (flavour in listOf("Argon2d", "Argon2i", "Argon2id")) {
                import(ppk(3, "ssh-ed25519", publicBlob, privateBlob, true, flavour, "  Grüße 🔑  ")) {
                    assertEquals("ssh-ed25519 " + Base64.getEncoder().encodeToString(publicBlob), it.publicKey)
                }
            }
        } finally { privateBlob.fill(0) }
    }

    @Test fun `valid MAC does not bypass private public consistency or private framing`() {
        val privateBlob = Base64.getDecoder().decode(fixture("input_clear_key").substringAfter("Private-Lines: 1\n").substringBefore('\n'))
        try {
            val changed = privateBlob.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            try { rejects(ppk(2, "ssh-ed25519", publicBlob, changed, false)) } finally { changed.fill(0) }
            rejects(ppk(2, "ssh-ed25519", publicBlob, privateBlob.copyOf(20), false))
            rejects(ppk(2, "ssh-ed25519", publicBlob, privateBlob + byteArrayOf(0), false))
        } finally { privateBlob.fill(0) }
    }

    @Test fun `authenticated oversized RSA public operands are rejected before provider construction`() {
        for (bits in listOf(8192, 65536)) {
            val modulus = java.math.BigInteger.ONE.shiftLeft(bits - 1).add(java.math.BigInteger.ONE).toByteArray()
            val public = framing(listOf("ssh-rsa".toByteArray(), byteArrayOf(1, 0, 1), modulus))
            rejects(ppk(2, "ssh-rsa", public, byteArrayOf(0, 0, 0, 1, 1), false))
        }
        val public = framing(listOf("ssh-rsa".toByteArray(), ByteArray(6) { 1 }, ByteArray(513) { 1 }))
        rejects(ppk(2, "ssh-rsa", public, byteArrayOf(0, 0, 0, 1, 1), false))
    }

    private fun framing(fields: List<ByteArray>): ByteArray = ByteArrayOutputStream().use { out ->
        DataOutputStream(out).use { stream -> fields.forEach { stream.writeInt(it.size); stream.write(it) } }
        out.toByteArray()
    }

    /** Test-only writer uses JCE AES/HMAC, independent of the production parser and BC MAC APIs. */
    private fun ppk(version: Int, algorithm: String, pub: ByteArray, privateBlob: ByteArray, encrypted: Boolean,
                    flavour: String = "Argon2id", comment: String = "synthetic fixture"): String {
        val password = "test-passphrase".toByteArray()
        val encryption = if (encrypted) "aes256-cbc" else "none"
        val plaintext = if (encrypted) privateBlob.copyOf((privateBlob.size + 15) / 16 * 16) else privateBlob.copyOf()
        val sha1 = MessageDigest.getInstance("SHA-1")
        val material = if (version == 3 && encrypted) {
            val type = when (flavour) { "Argon2d" -> 0; "Argon2i" -> 1; else -> 2 }
            ByteArray(80).also { bytes ->
                val params = Argon2Parameters.Builder(type).withMemoryAsKB(8192).withIterations(2)
                    .withParallelism(1).withSalt(ByteArray(16) { it.toByte() }).build()
                try { Argon2BytesGenerator().apply { init(params) }.generateBytes(password, bytes) }
                finally { params.clear() }
            }
        } else sha1.digest(byteArrayOf(0, 0, 0, 0) + password) + sha1.digest(byteArrayOf(0, 0, 0, 1) + password)
        val macKey = if (version == 2) sha1.digest("putty-private-key-file-mac-key".toByteArray() + if (encrypted) password else byteArrayOf())
            else if (encrypted) material.copyOfRange(48, 80) else ByteArray(64)
        val macData = framing(listOf(algorithm.toByteArray(), encryption.toByteArray(), comment.toByteArray(), pub, plaintext))
        try {
            val mac = Mac.getInstance(if (version == 2) "HmacSHA1" else "HmacSHA256")
            mac.init(SecretKeySpec(macKey, mac.algorithm))
            val expected = HexFormat.of().formatHex(mac.doFinal(macData))
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            val output = if (!encrypted) plaintext else {
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(material.copyOf(32), "AES"),
                    IvParameterSpec(if (version == 2) ByteArray(16) else material.copyOfRange(32, 48)))
                cipher.doFinal(plaintext)
            }
            try {
                fun blob(name: String, bytes: ByteArray): String {
                    val lines = Base64.getEncoder().encodeToString(bytes).chunked(64)
                    return "$name: ${lines.size}\n" + lines.joinToString("\n") + "\n"
                }
                return "PuTTY-User-Key-File-$version: $algorithm\nEncryption: $encryption\nComment: $comment\n" +
                    blob("Public-Lines", pub) + (if (version == 3 && encrypted)
                    "Key-Derivation: $flavour\nArgon2-Memory: 8192\nArgon2-Passes: 2\nArgon2-Parallelism: 1\nArgon2-Salt: 000102030405060708090a0b0c0d0e0f\n" else "") +
                    blob("Private-Lines", output) + "Private-MAC: $expected\n"
            } finally { if (output !== plaintext) output.fill(0) }
        } finally { plaintext.fill(0); password.fill(0); material.fill(0); macKey.fill(0); macData.fill(0) }
    }
}
