// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.ssh

import app.keyrook.core.crypto.Secret
import org.apache.sshd.common.config.keys.KeyEntryResolver
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.util.security.SecurityUtils
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPrivateCrtKeySpec
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** PPK 2/3 framing follows PuTTY Appendix C; all cryptographic primitives are supplied by BC. */
internal object PpkKeyReader {
    fun read(chars: CharArray, passphrase: Secret): KeyPair = Buffers().use { buffers ->
        require(chars.size in 32..65536)
        val lines = String(chars).split(Regex("\r\n|\r|\n")).toMutableList()
        if (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
        require(lines.size in 8..2048 && lines.all { it.length <= 4096 })
        var index = 0
        fun header(name: String): String {
            require(index < lines.size)
            val prefix = "$name: "
            return lines[index++].also { require(it.startsWith(prefix)) }.substring(prefix.length)
        }
        val version = when {
            lines.first().startsWith("PuTTY-User-Key-File-2: ") -> 2
            lines.first().startsWith("PuTTY-User-Key-File-3: ") -> 3
            else -> error("Unsupported PPK version")
        }
        val algorithm = header("PuTTY-User-Key-File-$version")
        require(algorithm == "ssh-ed25519" || algorithm == "ssh-rsa")
        val encryption = header("Encryption")
        require(encryption == "none" || encryption == "aes256-cbc")
        val encrypted = encryption != "none"
        val comment = header("Comment")
        fun blob(name: String): ByteArray {
            val count = decimal(header(name))
            require(count in 1..1024 && count <= lines.size - index)
            val encoded = lines.subList(index, index + count)
            require(encoded.all { it.length in 1..64 && it.all { c -> c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '=' } })
            index += count
            return buffers.own(Base64.getDecoder().decode(encoded.joinToString(""))).also { require(it.size in 1..8192) }
        }
        val publicBlob = blob("Public-Lines")
        var argonType = 0
        var memory = 0
        var passes = 0
        var lanes = 0
        var salt = byteArrayOf()
        if (version == 3 && encrypted) {
            argonType = when (header("Key-Derivation")) {
                "Argon2d" -> Argon2Parameters.ARGON2_d
                "Argon2i" -> Argon2Parameters.ARGON2_i
                "Argon2id" -> Argon2Parameters.ARGON2_id
                else -> error("Unsupported PPK derivation")
            }
            memory = decimal(header("Argon2-Memory"))
            passes = decimal(header("Argon2-Passes"))
            lanes = decimal(header("Argon2-Parallelism"))
            // PuTTY often uses many passes over small memory. Bound total work and peak memory
            // to the vault's automatic-approval budget, before invoking Argon2.
            require(lanes in 1..16 && memory in (8 * lanes)..262144 && passes in 1..128)
            require(memory.toLong() * passes <= 262144L * 5)
            val saltText = header("Argon2-Salt")
            require(saltText.length in 16..128 && saltText.length % 2 == 0)
            salt = buffers.own(HexFormat.of().parseHex(saltText))
        }
        val privateBlob = blob("Private-Lines")
        val macText = header("Private-MAC")
        require(macText.length == if (version == 2) 40 else 64)
        val expectedMac = buffers.own(HexFormat.of().parseHex(macText))
        require(index == lines.size)
        require(!encrypted || privateBlob.size % 16 == 0)
        passphrase.useUtf8 { password ->
            require(password.size <= 4096)
            val effectivePassword = if (encrypted) password else byteArrayOf()
            var cipherKey = byteArrayOf()
            var iv = buffers.own(ByteArray(16))
            val macKey: ByteArray
            if (version == 2) {
                if (encrypted) {
                    val material = buffers.own(ByteArray(40))
                    for (counter in 0..1) {
                        val digest = SHA1Digest()
                        val prefix = byteArrayOf(0, 0, 0, counter.toByte())
                        digest.update(prefix, 0, prefix.size)
                        digest.update(effectivePassword, 0, effectivePassword.size)
                        digest.doFinal(material, counter * 20)
                    }
                    cipherKey = buffers.own(material.copyOf(32))
                }
                val digest = SHA1Digest()
                val prefix = "putty-private-key-file-mac-key".toByteArray(Charsets.US_ASCII)
                digest.update(prefix, 0, prefix.size)
                digest.update(effectivePassword, 0, effectivePassword.size)
                macKey = buffers.own(ByteArray(20)).also { digest.doFinal(it, 0) }
            } else if (encrypted) {
                val parameters = Argon2Parameters.Builder(argonType).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withMemoryAsKB(memory).withIterations(passes).withParallelism(lanes).withSalt(salt).build()
                val material = buffers.own(ByteArray(80))
                try {
                    Argon2BytesGenerator().apply { init(parameters) }.generateBytes(effectivePassword, material)
                } finally { parameters.clear() }
                cipherKey = buffers.own(material.copyOfRange(0, 32))
                iv = buffers.own(material.copyOfRange(32, 48))
                macKey = buffers.own(material.copyOfRange(48, 80))
            } else macKey = byteArrayOf()
            val plaintext = if (!encrypted) privateBlob else {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding", BouncyCastleProvider())
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(iv))
                buffers.own(cipher.doFinal(privateBlob))
            }
            val mac = HMac(if (version == 2) SHA1Digest() else SHA256Digest())
            val key = KeyParameter(macKey)
            try { mac.init(key) } finally { key.key.fill(0) }
            fun macField(bytes: ByteArray) {
                val size = bytes.size
                for (shift in 24 downTo 0 step 8) mac.update((size ushr shift).toByte())
                mac.update(bytes, 0, bytes.size)
            }
            macField(algorithm.toByteArray(Charsets.US_ASCII))
            macField(encryption.toByteArray(Charsets.US_ASCII))
            // The comment participates in the MAC exactly as stored; never trim or normalize it.
            val commentChars = comment.toCharArray()
            try { Secret(commentChars).use { it.useUtf8(::macField) } } finally { commentChars.fill('\u0000') }
            macField(publicBlob)
            macField(plaintext)
            val actualMac = buffers.own(ByteArray(mac.macSize)).also { mac.doFinal(it, 0) }
            require(MessageDigest.isEqual(expectedMac, actualMac))
            decode(algorithm, publicBlob, plaintext, encrypted, buffers)
        }
    }

    private fun decode(algorithm: String, publicBlob: ByteArray, privateBlob: ByteArray,
                       encrypted: Boolean, buffers: Buffers): KeyPair {
        // Bound provider key construction as well as file parsing: a huge RSA modulus could
        // otherwise trigger expensive provider validation before the supported-size check.
        ByteArrayInputStream(publicBlob).use { input ->
            require(readData(input, 32, buffers).contentEquals(algorithm.toByteArray(Charsets.US_ASCII)))
            if (algorithm == "ssh-ed25519") require(readData(input, 32, buffers).size == 32)
            else {
                val exponent = BigInteger(readData(input, 5, buffers))
                val modulus = BigInteger(readData(input, 513, buffers))
                require(exponent >= BigInteger.valueOf(3) && exponent.bitLength() <= 32 && exponent.testBit(0))
                require(modulus.signum() > 0 && modulus.bitLength() == 4096)
            }
            require(input.available() == 0)
        }
        val text = "$algorithm ${Base64.getEncoder().encodeToString(publicBlob)}"
        val publicKey = PublicKeyEntry.parsePublicKeyEntry(text).resolvePublicKey(null, emptyMap(), null)
        val canonical = PublicKeyEntry.toString(publicKey)
        require(canonical.substringBefore(' ') == algorithm)
        require(Base64.getDecoder().decode(canonical.substringAfter(' ')).contentEquals(publicBlob))
        return ByteArrayInputStream(privateBlob).use { input ->
            fun data(max: Int) = readData(input, max, buffers)
            val privateKey = if (algorithm == "ssh-ed25519") {
                val seed = data(32)
                require(seed.size == 32)
                validatePadding(input, encrypted)
                SecurityUtils.generateEDDSAPrivateKey(algorithm, seed)
            } else {
                val rsa = publicKey as RSAPublicKey
                require(rsa.modulus.signum() > 0 && rsa.modulus.bitLength() == 4096)
                require(rsa.publicExponent >= BigInteger.valueOf(3) && rsa.publicExponent.bitLength() <= 32 && rsa.publicExponent.testBit(0))
                fun integer(): BigInteger {
                    val bytes = data(513)
                    require(bytes.isNotEmpty() && (bytes.size == 1 || bytes[0] != 0.toByte() || bytes[1] < 0))
                    return BigInteger(bytes).also { require(it.signum() > 0 && it.bitLength() <= 4096) }
                }
                val d = integer(); val p = integer(); val q = integer(); val inverse = integer()
                validatePadding(input, encrypted)
                require(p > BigInteger.ONE && q > BigInteger.ONE && p * q == rsa.modulus)
                SecurityUtils.getKeyFactory("RSA").generatePrivate(RSAPrivateCrtKeySpec(rsa.modulus, rsa.publicExponent,
                    d, p, q, d.mod(p - BigInteger.ONE), d.mod(q - BigInteger.ONE), inverse))
            }
            KeyPair(publicKey, privateKey)
        }
    }

    private fun validatePadding(input: ByteArrayInputStream, encrypted: Boolean) {
        require(input.available() in 0..if (encrypted) 15 else 0)
    }

    private fun readData(input: ByteArrayInputStream, maximum: Int, buffers: Buffers): ByteArray {
        val size = KeyEntryResolver.decodeInt(input)
        require(size in 1..maximum && size <= input.available())
        val bytes = buffers.own(ByteArray(size))
        require(input.read(bytes) == size)
        return bytes
    }

    private fun decimal(value: String): Int {
        require(value.isNotEmpty() && value.length <= 9 && value.all { it in '0'..'9' })
        return value.toInt()
    }

    private class Buffers : AutoCloseable {
        private val arrays = mutableListOf<ByteArray>()
        fun own(bytes: ByteArray): ByteArray = bytes.also { arrays += it }
        override fun close() { arrays.forEach { it.fill(0) }; arrays.clear() }
    }
}
