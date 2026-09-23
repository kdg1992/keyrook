// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.ssh

import app.keyrook.core.crypto.Secret
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.RSAPrivateCrtKey

class SshImportBoundsTest {
    private val service = SshKeyService()
    private val modulus = BigInteger.ONE.shiftLeft(4095).add(BigInteger.valueOf(3))
    private val exponent = BigInteger.valueOf(65537)

    private fun rsa(publicChanges: Map<String, BigInteger> = emptyMap(), privateChanges: Map<String, BigInteger> = emptyMap()): KeyPair {
        val publicValues = mapOf("getModulus" to modulus, "getPublicExponent" to exponent) + publicChanges
        val privateValues = mapOf(
            "getModulus" to modulus, "getPublicExponent" to exponent,
            "getPrivateExponent" to BigInteger.valueOf(5), "getPrimeP" to BigInteger.valueOf(7),
            "getPrimeQ" to BigInteger.valueOf(11), "getPrimeExponentP" to BigInteger.valueOf(13),
            "getPrimeExponentQ" to BigInteger.valueOf(17), "getCrtCoefficient" to BigInteger.valueOf(19),
        ) + privateChanges
        // Only exercises the arithmetic boundary: these synthetic values are not a usable RSA key.
        val public = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RSAPublicKey::class.java)) { _, method, _ ->
            publicValues[method.name]
        } as RSAPublicKey
        val private = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RSAPrivateCrtKey::class.java)) { _, method, _ ->
            privateValues[method.name]
        } as RSAPrivateCrtKey
        return KeyPair(public, private)
    }

    @Test fun `RSA arithmetic bounds reject oversized or mismatched public parameters`() {
        val tooWide = BigInteger.ONE.shiftLeft(65536).add(BigInteger.ONE)
        listOf(BigInteger.ZERO, BigInteger.TWO, BigInteger.valueOf(-3), tooWide).forEach { exponent ->
            assertThrows(IllegalArgumentException::class.java) {
                service.validateRsaParameters(rsa(mapOf("getPublicExponent" to exponent), mapOf("getPublicExponent" to exponent)))
            }
        }
        assertThrows(IllegalArgumentException::class.java) { service.validateRsaParameters(rsa(privateChanges = mapOf("getModulus" to modulus.add(BigInteger.TWO)))) }
        assertThrows(IllegalArgumentException::class.java) { service.validateRsaParameters(rsa(privateChanges = mapOf("getPublicExponent" to BigInteger.valueOf(3)))) }
    }

    @Test fun `every RSA private arithmetic operand is bounded before signing`() {
        val fields = listOf("getPrivateExponent", "getPrimeP", "getPrimeQ", "getPrimeExponentP", "getPrimeExponentQ", "getCrtCoefficient")
        fields.forEach { field ->
            listOf(BigInteger.ZERO, BigInteger.ONE.negate(), BigInteger.ONE.shiftLeft(4096)).forEach { invalid ->
                assertThrows(IllegalArgumentException::class.java) { service.validateRsaParameters(rsa(privateChanges = mapOf(field to invalid))) }
            }
        }
    }

    @Test fun `mismatched OpenSSH public and private keys are rejected`() {
        val generator = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider())
        val first = generator.generateKeyPair()
        val second = generator.generateKeyPair()
        val output = ByteArrayOutputStream()
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(KeyPair(first.public, second.private), "synthetic", null, output)
        Secret(output.toString(Charsets.US_ASCII).toCharArray()).use { input ->
            Secret("synthetic output password".toCharArray()).use { password ->
                val error = assertThrows(IllegalArgumentException::class.java) { service.importKey(input, password, password) }
                assertNull(error.cause)
                assertFalse(error.message.orEmpty().contains("synthetic"))
            }
        }
    }

    @Test fun `concatenated keys cannot be silently truncated to the first key`() {
        val generator = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider())
        val output = ByteArrayOutputStream()
        repeat(2) { OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(generator.generateKeyPair(), "", null, output) }
        Secret(output.toString(Charsets.US_ASCII).toCharArray()).use { input ->
            Secret("synthetic output password".toCharArray()).use { password ->
                assertThrows(IllegalArgumentException::class.java) { service.importKey(input, password, password) }
            }
        }
    }
}
