// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.ssh

import app.keyrook.core.crypto.Secret
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.KeyEntryResolver
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyEncryptionContext
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter
import org.apache.sshd.common.digest.BuiltinDigests
import org.apache.sshd.common.util.security.SecurityUtils
import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo
import org.bouncycastle.asn1.pkcs.PBES2Parameters
import org.bouncycastle.asn1.pkcs.PBKDF2Params
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.CharArrayReader
import java.security.KeyPair
import java.security.SecureRandom
import java.security.spec.NamedParameterSpec
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.math.BigInteger
import java.util.Base64

enum class SshKeyType { ED25519, RSA4096 }

/** Owns its encrypted private-key text. The caller owns passphrases supplied to the service. */
class SshKeyMaterial(
    val type: SshKeyType,
    val privateKey: Secret,
    val publicKey: String,
    val fingerprint: String,
) : AutoCloseable {
    override fun close() = privateKey.close()
    override fun toString(): String = "SshKeyMaterial(type=$type, privateKey=[redacted])"
}

/** Offline SSH formats use Apache MINA; no external process or plaintext temporary file is used. */
class SshKeyService {
    fun generate(type: SshKeyType, passphrase: Secret, comment: String = ""): SshKeyMaterial {
        validateOutput(passphrase, comment)
        val generator = SecurityUtils.getKeyPairGenerator(if (type == SshKeyType.ED25519) SecurityUtils.EDDSA else "RSA")
        if (type == SshKeyType.RSA4096) generator.initialize(4096, SecureRandom())
        else generator.initialize(NamedParameterSpec.ED25519, SecureRandom())
        val pair = generator.generateKeyPair()
        return try { encode(pair, passphrase, comment) } finally { destroy(pair) }
    }

    /** Imports one OpenSSH/PEM key and re-encrypts it with the supplied output passphrase. */
    fun importKey(encoded: Secret, passphrase: Secret, outputPassphrase: Secret, comment: String = ""): SshKeyMaterial {
        validateOutput(outputPassphrase, comment)
        return encoded.useChars { chars ->
            require(chars.size in 32..65536) { "SSH key must contain at most 64 KiB of text" }
            // MINA needs immutable strings internally; never include its exceptions in UI or logs.
            try {
                preflight(chars)
                passphrase.useChars { password ->
                    require(password.size <= 1024)
                    val provider = FilePasswordProvider { _, _, retry ->
                        check(retry == 0) { "SSH password retry is not allowed" }
                        String(password)
                    }
                    val pairs = CharArrayReader(chars).use { reader ->
                        SecurityUtils.getKeyPairResourceParser().loadKeyPairs(null, NamedResource { "private-key" }, provider, reader)
                    }.orEmpty()
                    try {
                        require(pairs.size == 1)
                        val pair = pairs.single()
                        validatePair(pair)
                        encode(pair, outputPassphrase, comment)
                    } finally { pairs.forEach(::destroy) }
                }
            } catch (_: Exception) {
                throw IllegalArgumentException("SSH import failed: unsupported or damaged key, excessive derivation cost, or incorrect passphrase. Use OpenSSH or PEM; PuTTY PPK requires conversion to OpenSSH.")
            }
        }
    }

    private fun validateOutput(passphrase: Secret, comment: String) {
        passphrase.useChars { require(it.size in 12..1024) { "SSH output passphrase must contain 12 to 1024 characters" } }
        require(comment.length <= 256 && comment.all { it.code in 32..126 }) { "SSH comment must be one printable ASCII line" }
    }

    private fun typeOf(pair: KeyPair): SshKeyType = when (KeyUtils.getKeyType(pair)) {
        "ssh-ed25519" -> SshKeyType.ED25519
        "ssh-rsa" -> {
            require(KeyUtils.getKeySize(pair.public) == 4096) { "Only RSA-4096 keys are supported" }
            SshKeyType.RSA4096
        }
        else -> throw IllegalArgumentException("Only Ed25519 and RSA-4096 keys are supported")
    }

    private fun validatePair(pair: KeyPair) {
        val type = typeOf(pair)
        if (type == SshKeyType.RSA4096) validateRsaParameters(pair)
        val algorithm = if (type == SshKeyType.ED25519) "Ed25519" else "SHA256withRSA"
        val challenge = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signer = SecurityUtils.getSignature(algorithm)
        signer.initSign(pair.private)
        signer.update(challenge)
        val signature = signer.sign()
        try {
            val verifier = SecurityUtils.getSignature(algorithm)
            verifier.initVerify(pair.public)
            verifier.update(challenge)
            require(verifier.verify(signature)) { "SSH public and private keys do not match" }
        } finally { challenge.fill(0); signature.fill(0) }
    }

    /** Bound attacker-controlled RSA arithmetic before asking a provider to sign or verify. */
    internal fun validateRsaParameters(pair: KeyPair) {
        val public = pair.public as? RSAPublicKey ?: throw IllegalArgumentException("Invalid RSA public key")
        val private = pair.private as? RSAPrivateCrtKey ?: throw IllegalArgumentException("RSA CRT parameters are required")
        require(public.modulus.signum() > 0 && public.modulus.bitLength() == 4096)
        require(private.modulus == public.modulus)
        require(public.publicExponent >= BigInteger.valueOf(3) && public.publicExponent.bitLength() <= 32 && public.publicExponent.testBit(0))
        require(private.publicExponent == public.publicExponent)
        listOf(private.privateExponent, private.primeP, private.primeQ, private.primeExponentP, private.primeExponentQ, private.crtCoefficient).forEach {
            require(it.signum() > 0 && it.bitLength() <= 4096) { "RSA parameters exceed supported arithmetic limits" }
        }
    }

    private fun encode(pair: KeyPair, passphrase: Secret, comment: String): SshKeyMaterial {
        val type = typeOf(pair)
        val writer = OpenSSHKeyPairResourceWriter.INSTANCE
        val publicText = ByteArrayOutputStream().use { out ->
            writer.writePublicKey(pair.public, comment, out)
            out.toString(Charsets.US_ASCII).trimEnd()
        }
        val fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, pair.public)
        return passphrase.useChars { password ->
            val context = OpenSSHKeyEncryptionContext().apply {
                cipherType = "256"
                cipherMode = "CTR"
                kdfRounds = 64
                this.password = String(password)
            }
            try {
                ErasableOutput().use { out ->
                    writer.writePrivateKey(pair, comment, context, out)
                    val bytes = out.toByteArray()
                    val chars = CharArray(bytes.size) { bytes[it].toInt().toChar() }
                    try { SshKeyMaterial(type, Secret(chars), publicText, fingerprint) }
                    finally { bytes.fill(0); chars.fill('\u0000') }
                }
            } finally { context.password = null }
        }
    }

    private fun preflight(chars: CharArray) {
        val lines = String(chars).trim().lines().map { it.trim() }
        require(lines.size <= 2048 && lines.all { it.length <= 4096 })
        val supported = setOf("OPENSSH PRIVATE KEY", "RSA PRIVATE KEY", "PRIVATE KEY", "ENCRYPTED PRIVATE KEY")
        val label = lines.first().removePrefix("-----BEGIN ").removeSuffix("-----")
        require(label in supported && lines.first() == "-----BEGIN $label-----" && lines.last() == "-----END $label-----")
        require(lines.count { it.startsWith("-----BEGIN ") } == 1 && lines.count { it.startsWith("-----END ") } == 1)
        if (label == "OPENSSH PRIVATE KEY") {
            val bytes = Base64.getDecoder().decode(lines.subList(1, lines.lastIndex).joinToString(""))
            try {
                ByteArrayInputStream(bytes).use { input ->
                    require(input.readNBytes(15).contentEquals("openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)))
                    val cipher = KeyEntryResolver.decodeString(input, 64)
                    val kdf = KeyEntryResolver.decodeString(input, 64)
                    val options = KeyEntryResolver.readRLEBytes(input, 256)
                    if (cipher == "none") require(kdf == "none" && options.isEmpty())
                    else {
                        require(kdf == "bcrypt")
                        ByteArrayInputStream(options).use { parameters ->
                            require(KeyEntryResolver.readRLEBytes(parameters, 64).size in 8..64)
                            require(KeyEntryResolver.decodeInt(parameters) in 1..255)
                            require(parameters.available() == 0)
                        }
                    }
                    require(KeyEntryResolver.decodeInt(input) == 1)
                    val publicBytes = KeyEntryResolver.readRLEBytes(input, 8192)
                    val privateBytes = KeyEntryResolver.readRLEBytes(input, 32768)
                    try { require(publicBytes.isNotEmpty() && privateBytes.isNotEmpty() && input.available() == 0) }
                    finally { privateBytes.fill(0) }
                }
            } finally { bytes.fill(0) }
        }
        if (label == "ENCRYPTED PRIVATE KEY") {
            val bytes = Base64.getDecoder().decode(lines.subList(1, lines.lastIndex).joinToString(""))
            try {
                val info = EncryptedPrivateKeyInfo.getInstance(bytes)
                require(info.encryptionAlgorithm.algorithm == PKCSObjectIdentifiers.id_PBES2)
                val pbes = PBES2Parameters.getInstance(info.encryptionAlgorithm.parameters)
                require(pbes.keyDerivationFunc.algorithm == PKCSObjectIdentifiers.id_PBKDF2)
                val kdf = PBKDF2Params.getInstance(pbes.keyDerivationFunc.parameters)
                require(kdf.iterationCount.signum() > 0 && kdf.iterationCount.bitLength() <= 31 && kdf.iterationCount.toInt() <= 1000000)
                require(kdf.salt.size in 8..64)
                require(kdf.keyLength == null || kdf.keyLength.toInt() in 16..64 && kdf.keyLength.bitLength() <= 7)
            } finally { bytes.fill(0) }
        }
        // Bound OpenSSH work independently of MINA's process-wide configuration. PPK is rejected:
        // its current parser does not verify Private-MAC. No partial authenticity check substitutes for that MAC.
    }

    private fun destroy(pair: KeyPair) {
        try { pair.private.destroy() } catch (_: javax.security.auth.DestroyFailedException) { /* Provider may retain key material until GC. */ }
    }

    private class ErasableOutput : ByteArrayOutputStream() {
        override fun close() { buf.fill(0); reset() }
    }
}
