// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.ssh

import app.keyrook.core.crypto.Secret
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo
import org.bouncycastle.asn1.pkcs.PBES2Parameters
import org.bouncycastle.asn1.pkcs.PBKDF2Params
import org.bouncycastle.asn1.pkcs.KeyDerivationFunc
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PKCS8Generator
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder
import org.bouncycastle.util.io.pem.PemHeader
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.security.KeyPairGenerator
import java.nio.ByteBuffer
import java.util.Base64
import java.util.HexFormat

class SshKeyServiceTest {
    private val service = SshKeyService()
    private fun password() = Secret("synthetic key password".toCharArray())

    // RFC 8032 section 7.1 test vector 1, wrapped in RFC 8410 PKCS#8.
    private fun rfcPrivateKey() = HexFormat.of().parseHex(
        "302e020100300506032b657004220420" + "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
    )

    private fun pem(label: String, bytes: ByteArray): Secret = Secret(
        ("-----BEGIN $label-----\n" + Base64.getEncoder().encodeToString(bytes).chunked(64).joinToString("\n") +
            "\n-----END $label-----\n").toCharArray(),
    )

    @Test fun `RFC8032 Ed25519 PKCS8 import matches the known public key`() {
        password().use { password -> pem("PRIVATE KEY", rfcPrivateKey()).use { input ->
            service.importKey(input, password, password).use { key ->
                val blob = Base64.getDecoder().decode(key.publicKey.split(' ')[1])
                assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", HexFormat.of().formatHex(blob.takeLast(32).toByteArray()))
            }
        } }
    }

    @Test fun `encrypted PKCS8 imports with correct passphrase and rejects wrong one and excessive cost`() {
        password().use { password -> password.useChars { chars ->
            val encryptor = JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC)
                .setProvider(BouncyCastleProvider()).setPassword(chars).setIterationCount(10000).build()
            val bytes = PKCS8Generator(PrivateKeyInfo.getInstance(rfcPrivateKey()), encryptor).generate().content
            pem("ENCRYPTED PRIVATE KEY", bytes).use { input ->
                service.importKey(input, password, password).use { assertEquals(SshKeyType.ED25519, it.type) }
                Secret("incorrect password".toCharArray()).use { wrong ->
                    assertThrows(IllegalArgumentException::class.java) { service.importKey(input, wrong, password) }
                }
            }
            val info = EncryptedPrivateKeyInfo.getInstance(bytes)
            val pbes = PBES2Parameters.getInstance(info.encryptionAlgorithm.parameters)
            val oversized = PBES2Parameters(
                KeyDerivationFunc(PKCSObjectIdentifiers.id_PBKDF2, PBKDF2Params(ByteArray(16), Int.MAX_VALUE)),
                pbes.encryptionScheme,
            )
            val bomb = EncryptedPrivateKeyInfo(AlgorithmIdentifier(PKCSObjectIdentifiers.id_PBES2, oversized), info.encryptedData).encoded
            pem("ENCRYPTED PRIVATE KEY", bomb).use { input ->
                assertThrows(IllegalArgumentException::class.java) { service.importKey(input, password, password) }
            }
        } }
    }

    @Test fun `traditional encrypted RSA PEM imports and reencrypts to OpenSSH`() {
        password().use { password -> password.useChars { chars ->
            val generator = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider()).apply { initialize(4096) }
            val pair = generator.generateKeyPair()
            val objectValue = JcaMiscPEMGenerator(
                pair, JcePEMEncryptorBuilder("AES-256-CBC").setProvider(BouncyCastleProvider()).build(chars),
            ).generate()
            val headers = objectValue.headers.map { it as PemHeader }.joinToString("\n") { "${it.name}: ${it.value}" }
            val text = "-----BEGIN ${objectValue.type}-----\n$headers\n\n" +
                Base64.getEncoder().encodeToString(objectValue.content).chunked(64).joinToString("\n") +
                "\n-----END ${objectValue.type}-----\n"
            Secret(text.toCharArray()).use { input -> service.importKey(input, password, password).use { imported ->
                assertEquals(SshKeyType.RSA4096, imported.type)
                imported.privateKey.useChars { assertTrue(String(it).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")) }
            } }
        } }
    }

    @Test fun `OpenSSH cost bombs and trailing data are rejected before decryption`() {
        password().use { password -> service.generate(SshKeyType.ED25519, password).use { original ->
            original.privateKey.useChars { chars ->
                val lines = String(chars).trim().lines()
                val bytes = Base64.getDecoder().decode(lines.subList(1, lines.lastIndex).joinToString(""))
                val buffer = ByteBuffer.wrap(bytes)
                buffer.position(15)
                repeat(2) { val size = buffer.int; buffer.position(buffer.position() + size) }
                buffer.int
                val saltSize = buffer.int
                buffer.position(buffer.position() + saltSize)
                buffer.putInt(Int.MAX_VALUE)
                pem("OPENSSH PRIVATE KEY", bytes).use { input ->
                    assertThrows(IllegalArgumentException::class.java) { service.importKey(input, password, password) }
                }
                val originalBytes = Base64.getDecoder().decode(lines.subList(1, lines.lastIndex).joinToString(""))
                pem("OPENSSH PRIVATE KEY", originalBytes + byteArrayOf(1)).use { input ->
                    assertThrows(IllegalArgumentException::class.java) { service.importKey(input, password, password) }
                }
            }
        } }
    }

    @Test fun `ed25519 encrypted OpenSSH roundtrip preserves public key and fingerprint`() {
        password().use { password ->
            service.generate(SshKeyType.ED25519, password, "synthetic@example.invalid").use { original ->
                assertTrue(original.publicKey.startsWith("ssh-ed25519 "))
                assertTrue(original.publicKey.endsWith(" synthetic@example.invalid"))
                val digest = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(original.publicKey.split(' ')[1]))
                assertEquals("SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest), original.fingerprint)
                service.importKey(original.privateKey, password, password, "synthetic@example.invalid").use { imported ->
                    assertEquals(original.publicKey, imported.publicKey)
                    assertEquals(original.fingerprint, imported.fingerprint)
                    original.privateKey.useChars { a -> imported.privateKey.useChars { b -> assertFalse(a.contentEquals(b)) } }
                }
            }
        }
    }

    @Test fun `rsa4096 encrypted OpenSSH roundtrip`() {
        password().use { password -> service.generate(SshKeyType.RSA4096, password).use { original ->
            assertTrue(original.publicKey.startsWith("ssh-rsa "))
            service.importKey(original.privateKey, password, password).use { imported ->
                assertEquals(SshKeyType.RSA4096, imported.type)
                assertEquals(original.fingerprint, imported.fingerprint)
            }
        } }
    }

    @Test fun `wrong password and modified encrypted key are rejected`() {
        password().use { password -> service.generate(SshKeyType.ED25519, password).use { original ->
            Secret("incorrect password".toCharArray()).use { wrong ->
                assertThrows(IllegalArgumentException::class.java) { service.importKey(original.privateKey, wrong, password) }
            }
            original.privateKey.useChars { chars ->
                val lines = String(chars).trim().lines()
                val binary = Base64.getDecoder().decode(lines.subList(1, lines.lastIndex).joinToString(""))
                binary[binary.lastIndex - 20] = (binary[binary.lastIndex - 20].toInt() xor 1).toByte()
                val altered = lines.first() + "\n" + Base64.getEncoder().encodeToString(binary).chunked(70).joinToString("\n") + "\n" + lines.last()
                Secret(altered.toCharArray()).use { modified ->
                    assertThrows(IllegalArgumentException::class.java) { service.importKey(modified, password, password) }
                }
                binary.fill(0)
            }
        } }
    }

    @Test fun `output passphrase and comment cannot be empty or inject authorized keys lines`() {
        Secret(CharArray(0)).use { empty ->
            assertThrows(IllegalArgumentException::class.java) { service.generate(SshKeyType.ED25519, empty) }
        }
        password().use { password ->
            assertThrows(IllegalArgumentException::class.java) { service.generate(SshKeyType.ED25519, password, "one\nssh-rsa injected") }
        }
    }

    @Test fun `oversized and unsupported PPK input is rejected without exposing key data`() {
        password().use { password ->
            listOf("PuTTY-User-Key-File-3: ssh-ed25519\nPrivate-MAC: synthetic", "x".repeat(65537)).forEach { text ->
                Secret(text.toCharArray()).use { input ->
                    val error = assertThrows(IllegalArgumentException::class.java) { service.importKey(input, password, password) }
                    assertFalse(error.toString().contains(text))
                }
            }
        }
    }
}
