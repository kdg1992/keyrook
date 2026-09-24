// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.format

import app.keyrook.core.crypto.InvalidVaultException
import app.keyrook.core.crypto.KdfParameters
import java.nio.ByteBuffer

/**
 * Binary envelope. Only [ENVELOPE_VERSION] 1 exists. A future envelope version would add its own branch in
 * [parse] mapping that layout onto this in-memory header (including its own header length for the AAD), while
 * [encode] keeps writing only the newest version; unknown versions are always rejected before key derivation.
 */
internal data class VaultHeader(val kdf: KdfParameters, val keyFile: Boolean, val salt: ByteArray, val nonce: ByteArray) {
    fun encode(): ByteArray = ByteBuffer.allocate(SIZE).apply {
        put(MAGIC); putShort(ENVELOPE_VERSION.toShort()); putShort(SIZE.toShort()); putShort(if (keyFile) 1 else 0)
        put(1); put(1); putInt(0x13)
        putInt(kdf.memoryKiB); putInt(kdf.iterations); putInt(kdf.parallelism)
        put(salt); put(nonce)
    }.array()

    companion object {
        const val SIZE = 76
        const val ENVELOPE_VERSION = 1
        private val MAGIC = byteArrayOf(75, 69, 89, 82, 79, 79, 75, 0)
        fun parse(bytes: ByteArray, allowExpensive: Boolean): VaultHeader {
            if (bytes.size < SIZE + 16) throw InvalidVaultException()
            val buffer = ByteBuffer.wrap(bytes, 0, SIZE)
            val magic = ByteArray(8).also(buffer::get)
            if (!magic.contentEquals(MAGIC)) throw InvalidVaultException()
            return when (buffer.short.toInt()) {
                ENVELOPE_VERSION -> parseV1(buffer, allowExpensive)
                else -> throw InvalidVaultException()
            }
        }

        private fun parseV1(buffer: ByteBuffer, allowExpensive: Boolean): VaultHeader {
            if (buffer.short.toInt() != SIZE) throw InvalidVaultException()
            val flags = buffer.short.toInt()
            if (flags !in 0..1 || buffer.get().toInt() != 1 || buffer.get().toInt() != 1 || buffer.int != 0x13)
                throw InvalidVaultException()
            val params = KdfParameters(buffer.int, buffer.int, buffer.int)
            params.validate(allowExpensive)
            return VaultHeader(params, flags == 1, ByteArray(32).also(buffer::get), ByteArray(12).also(buffer::get))
        }
    }
}
