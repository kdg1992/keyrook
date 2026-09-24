// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.format

import app.keyrook.core.crypto.*
import app.keyrook.core.model.Vault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * No plaintext leaves this codec except a validated, authenticated in-memory model.
 * Documents with an older schema are migrated through [migrations] in memory only; encryption always
 * writes the current envelope and schema.
 */
@OptIn(ExperimentalSerializationApi::class)
class VaultCodec internal constructor(private val migrations: SchemaMigrations) {
    constructor() : this(SchemaMigrations.PRODUCTION)

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        classDiscriminator = "type"
    }
    private val probe = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun encrypt(vault: Vault, credentials: Credentials, parameters: KdfParameters = KdfParameters(),
                allowExpensive: Boolean = false): ByteArray {
        parameters.validate(allowExpensive)
        validate(vault)
        val plaintext = encode(vault)
        try {
            val header = VaultHeader(parameters, credentials.hasKeyFile(), VaultCrypto.randomBytes(32), VaultCrypto.randomBytes(12))
            val aad = header.encode()
            val key = credentials.derive(header.salt, header.kdf)
            try { return aad + VaultCrypto.aesGcm(true, key, header.nonce, aad, plaintext) }
            finally { key.fill(0) }
        } finally { plaintext.fill(0) }
    }

    fun decrypt(bytes: ByteArray, credentials: Credentials, allowExpensive: Boolean = false): Vault =
        decryptDocument(bytes, credentials, allowExpensive).vault

    /** Like [decrypt], and also reports the schema version stored in the file before any migration. */
    internal fun decryptDocument(bytes: ByteArray, credentials: Credentials, allowExpensive: Boolean = false): DecryptedDocument {
        if (bytes.size > MAX_FILE_BYTES) throw InvalidVaultException()
        val header = VaultHeader.parse(bytes, allowExpensive)
        if (header.keyFile != credentials.hasKeyFile()) throw AuthenticationException()
        val key = credentials.derive(header.salt, header.kdf)
        val plaintext = try {
            VaultCrypto.aesGcm(false, key, header.nonce, bytes.copyOfRange(0, VaultHeader.SIZE),
                bytes.copyOfRange(VaultHeader.SIZE, bytes.size))
        } finally { key.fill(0) }
        return try {
            checkJsonLimits(plaintext)
            val version = probe.decodeFromStream<SchemaProbe>(ByteArrayInputStream(plaintext)).schemaVersion
            DecryptedDocument(if (version == migrations.current) decodeCurrent(plaintext) else migrate(plaintext, version), version)
        } catch (_: Exception) {
            // Parser messages may include decrypted field contents; never attach the original exception.
            throw InvalidVaultException()
        } finally { plaintext.fill(0) }
    }

    internal fun duplicate(vault: Vault): Vault {
        validate(vault)
        val bytes = encode(vault)
        return try { SecretSerializer.trackDecoding { json.decodeFromStream<Vault>(ByteArrayInputStream(bytes)) } }
        catch (_: Exception) { throw InvalidVaultException() }
        finally { bytes.fill(0) }
    }

    private fun decodeCurrent(bytes: ByteArray): Vault = SecretSerializer.trackDecoding {
        val vault = json.decodeFromStream<Vault>(ByteArrayInputStream(bytes))
        validate(vault)
        vault
    }

    /**
     * Only reached for an older schema with a registered contiguous chain; newer or unbridged versions are
     * rejected before a tree is built. The tree holds immutable strings until garbage collection, and the
     * re-encoded bytes are wiped. The result passes the same guard, decoder and validation as a stored document.
     */
    private fun migrate(plaintext: ByteArray, version: Int): Vault {
        if (migrations.chain(version) == null) throw InvalidVaultException()
        val document = migrations.migrate(json.decodeFromStream<JsonObject>(ByteArrayInputStream(plaintext)))
        val bytes = SecretSerializer.trackDecoding { json.decodeFromJsonElement<Vault>(document) }.use { migrated ->
            validate(migrated)
            encode(migrated)
        }
        return try { decodeCurrent(bytes) } finally { bytes.fill(0) }
    }

    private fun validate(vault: Vault) {
        try { vault.validate() } catch (_: Exception) { throw InvalidVaultException() }
    }
    private fun encode(vault: Vault): ByteArray {
        val stream = WipingOutput(MAX_FILE_BYTES - VaultHeader.SIZE - 16)
        return try {
            json.encodeToStream(vault, stream)
            val bytes = stream.toByteArray()
            try { checkJsonLimits(bytes); bytes } catch (e: Exception) { bytes.fill(0); throw e }
        } catch (_: Exception) { throw InvalidVaultException() }
        finally { stream.close() }
    }

    companion object {
        const val MAX_FILE_BYTES = 64 * 1024 * 1024
        /** Limits and duplicate-key checks precede deserialization; JSON syntax is checked by kotlinx. */
        internal fun checkJsonLimits(bytes: ByteArray) {
            checkUtf8(bytes)
            data class Frame(val opener: Char, val keys: MutableSet<String> = mutableSetOf(), var commas: Int = 0)
            val stack = ArrayDeque<Frame>()
            var quoted = false
            var escape = false
            var stringBytes = 0
            var start = 0
            var members = 0
            for (index in bytes.indices) {
                val c = bytes[index].toInt().toChar()
                if (quoted) {
                    if (++stringBytes > 1_572_864) throw InvalidVaultException()
                    if (escape) escape = false
                    else when (c) {
                        '\\' -> escape = true
                        '"' -> {
                            quoted = false
                            var next = index + 1
                            while (next < bytes.size && bytes[next].toInt() in listOf(9, 10, 13, 32)) next++
                            if (next < bytes.size && bytes[next].toInt() == 58) {
                                val frame = stack.lastOrNull() ?: throw InvalidVaultException()
                                val key = Json.decodeFromString<String>(String(bytes, start, index - start + 1, Charsets.UTF_8))
                                if (frame.opener != '{' || !frame.keys.add(key) || frame.keys.size > 10_000)
                                    throw InvalidVaultException()
                            }
                        }
                    }
                } else when (c) {
                    '"' -> { quoted = true; stringBytes = 0; start = index }
                    '{', '[' -> {
                        stack.addLast(Frame(c))
                        if (stack.size > 32) throw InvalidVaultException()
                    }
                    '}', ']' -> {
                        val frame = stack.removeLastOrNull() ?: throw InvalidVaultException()
                        if ((c == '}' && frame.opener != '{') || (c == ']' && frame.opener != '[')) throw InvalidVaultException()
                    }
                    ',', ':' -> {
                        if (++members > 500_000) throw InvalidVaultException()
                        if (c == ',') {
                            val frame = stack.lastOrNull() ?: throw InvalidVaultException()
                            if (++frame.commas >= 10_000) throw InvalidVaultException()
                        }
                    }
                }
            }
            if (stack.isNotEmpty() || quoted) throw InvalidVaultException()
        }

        private fun checkUtf8(bytes: ByteArray) {
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val input = ByteBuffer.wrap(bytes)
            val chars = CharBuffer.allocate(4096)
            try {
                while (true) {
                    val result = decoder.decode(input, chars, true)
                    if (result.isError) result.throwException()
                    chars.array().fill('\u0000'); chars.clear()
                    if (result.isUnderflow) break
                }
            } finally { chars.array().fill('\u0000') }
        }
    }
}

/** A decrypted, current-schema document and the schema version its file stored; [vault] is caller-owned. */
internal class DecryptedDocument(val vault: Vault, val storedSchemaVersion: Int)

/** Reads only the schema version so the matching decoder or migration chain can be chosen. */
@Serializable
private class SchemaProbe(val schemaVersion: Int)

private class WipingOutput(private val limit: Int) : OutputStream() {
    private var buffer = ByteArray(4096)
    private var count = 0
    private fun reserve(length: Int) {
        if (length < 0 || length > limit - count) throw InvalidVaultException()
        if (count + length > buffer.size) {
            val next = buffer.copyOf(maxOf(count + length, minOf(limit, buffer.size * 2)))
            buffer.fill(0)
            buffer = next
        }
    }
    override fun write(b: Int) { reserve(1); buffer[count++] = b.toByte() }
    override fun write(b: ByteArray, off: Int, len: Int) {
        reserve(len)
        b.copyInto(buffer, count, off, off + len)
        count += len
    }
    fun toByteArray(): ByteArray = buffer.copyOf(count)
    override fun close() { buffer.fill(0); count = 0 }
}
