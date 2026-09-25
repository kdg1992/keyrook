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
 * A document does not fit the file format: its encoding exceeds [VaultCodec.MAX_FILE_BYTES] or another structural
 * limit of the JSON guard. Raised on encoding only, when saving or exporting; the message never contains content.
 */
class VaultTooLargeException : Exception("Vault exceeds the size limits of the file format")

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

    /**
     * Raises [VaultTooLargeException] when [vault] could not be saved because its encoding exceeds the file format's
     * limits, and [InvalidVaultException] when it fails validation. Imports use it so that what they accept can be saved.
     */
    internal fun requireStorable(vault: Vault) {
        validate(vault)
        encode(vault).fill(0)
    }

    private fun decodeCurrent(bytes: ByteArray): Vault = SecretSerializer.trackDecoding {
        val vault = json.decodeFromStream<Vault>(ByteArrayInputStream(bytes))
        validate(vault)
        vault
    }

    /**
     * Only reached for an older schema with a registered contiguous chain; newer or unbridged versions are
     * rejected before a tree is built. The tree holds immutable strings until garbage collection, and the
     * re-encoded bytes are wiped. The result passes the same guard, decoder and validation as a stored document,
     * with room for [MIGRATION_GROWTH_BYTES] of added fields, so a file near the size limit still opens; saving it
     * then raises [VaultTooLargeException] until entries are removed.
     */
    private fun migrate(plaintext: ByteArray, version: Int): Vault {
        if (migrations.chain(version) == null) throw InvalidVaultException()
        val document = migrations.migrate(json.decodeFromStream<JsonObject>(ByteArrayInputStream(plaintext)))
        val bytes = SecretSerializer.trackDecoding { json.decodeFromJsonElement<Vault>(document) }.use { migrated ->
            validate(migrated)
            encode(migrated, MAX_DOCUMENT_BYTES + MIGRATION_GROWTH_BYTES)
        }
        return try { decodeCurrent(bytes) } finally { bytes.fill(0) }
    }

    private fun validate(vault: Vault) {
        try { vault.validate() } catch (_: Exception) { throw InvalidVaultException() }
    }
    /** A validated model always passes the JSON guard unless it is too large, so any guard failure means size. */
    private fun encode(vault: Vault, limit: Int = MAX_DOCUMENT_BYTES): ByteArray {
        val stream = WipingOutput(limit)
        return try {
            json.encodeToStream(vault, stream)
            val bytes = stream.toByteArray()
            try { checkJsonLimits(bytes, limit); bytes } catch (_: Exception) { bytes.fill(0); throw VaultTooLargeException() }
        } catch (e: VaultTooLargeException) { throw e }
        catch (_: Exception) { throw InvalidVaultException() }
        finally { stream.close() }
    }

    companion object {
        const val MAX_FILE_BYTES = 64 * 1024 * 1024
        /** Largest decrypted document: the file without header and authentication tag. */
        private const val MAX_DOCUMENT_BYTES = MAX_FILE_BYTES - VaultHeader.SIZE - 16

        /**
         * Growth allowed when an older schema is re-encoded in memory. Schema 1 to 2 adds at most 75 bytes per
         * customer (`,"contactName":null,"contactEmail":null,"phone":null,"website":null,"notes":""`), 32 per project,
         * 15 per entry (`,"pinned":false`) and 15 for `,"templates":[]`: about 1.2 MB for 10,000 of each.
         */
        internal const val MIGRATION_GROWTH_BYTES = 4 * 1024 * 1024

        /**
         * Bytes per structural separator (`,` or `:`) that every document of the model needs at least, which bounds
         * the separators of a document of `n` bytes to `n / 3`. In the compact encoding each object member
         * `"key":value,` spends at least seven bytes on its two separators (field names have at least two
         * characters; custom field labels may be empty, but then the value is a whole field object), and each further
         * list element spends at least three bytes on its comma (`"",` for an empty tag). Model maximums allow far
         * more separators than fit (10,000 entries of 100 history items with 100 fields each), so the file size is
         * the binding limit: a valid vault never reaches the bound, and a hostile document still needs three bytes
         * for each tree node the parser would create.
         */
        private const val MIN_BYTES_PER_SEPARATOR = 3

        /**
         * Encoded bytes of the longest JSON string, including its closing quote: [Vault.MAX_FIELD_CHARS] UTF-16 code
         * units, each at most six bytes as a `\uXXXX` escape.
         */
        private const val MAX_STRING_BYTES = Vault.MAX_FIELD_CHARS * 6 + 1

        /**
         * Limits and duplicate-key checks precede deserialization; JSON syntax is checked by kotlinx. [limit] is the
         * largest document size the caller accepts; it sets the separator bound (see [MIN_BYTES_PER_SEPARATOR]).
         */
        internal fun checkJsonLimits(bytes: ByteArray, limit: Int = MAX_FILE_BYTES) {
            val maxSeparators = limit / MIN_BYTES_PER_SEPARATOR
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
                    if (++stringBytes > MAX_STRING_BYTES) throw InvalidVaultException()
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
                        if (++members > maxSeparators) throw InvalidVaultException()
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
        if (length < 0) throw InvalidVaultException()
        if (length > limit - count) throw VaultTooLargeException()
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
