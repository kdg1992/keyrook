// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.crypto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Owns a copy; callers still own and must erase their input. Never reveals contents in toString. */
@Serializable(with = SecretSerializer::class)
class Secret(chars: CharArray) : AutoCloseable {
    private val value = chars.copyOf()
    private var closed = false

    @Synchronized
    fun <T> useChars(block: (CharArray) -> T): T {
        check(!closed) { "Secret is closed" }
        val copy = value.copyOf()
        return try { block(copy) } finally { copy.fill('\u0000') }
    }

    fun <T> useUtf8(block: (ByteArray) -> T): T = useChars { chars ->
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(encoded.remaining())
        try {
            encoded.get(bytes)
            block(bytes)
        } finally {
            bytes.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    fun copy(): Secret = useChars { Secret(it) }
    @Synchronized
    override fun close() { value.fill('\u0000'); closed = true }
    override fun toString(): String = "Secret([redacted])"
}

/** JSON requires a temporary immutable String. See SECURITY.md for the JVM limitation. */
object SecretSerializer : KSerializer<Secret> {
    private val decoding = ThreadLocal<MutableList<Secret>?>()
    internal fun <T> trackDecoding(block: () -> T): T {
        check(decoding.get() == null)
        val secrets = mutableListOf<Secret>()
        decoding.set(secrets)
        return try { block() }
        catch (e: Throwable) { secrets.forEach { it.close() }; throw e }
        finally { decoding.remove() }
    }
    override val descriptor = PrimitiveSerialDescriptor("Secret", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Secret) = value.useChars {
        encoder.encodeString(String(it))
    }
    override fun deserialize(decoder: Decoder): Secret {
        val chars = decoder.decodeString().toCharArray()
        return try { Secret(chars).also { decoding.get()?.add(it) } } finally { chars.fill('\u0000') }
    }
}
