// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.format

import app.keyrook.core.crypto.InvalidVaultException
import app.keyrook.core.crypto.KdfParameters
import java.nio.ByteBuffer

internal data class VaultHeader(val kdf: KdfParameters, val keyFile: Boolean, val salt: ByteArray, val nonce: ByteArray) {
    fun encode(): ByteArray = ByteBuffer.allocate(SIZE).apply {
        put(MAGIC); putShort(1); putShort(SIZE.toShort()); putShort(if (keyFile) 1 else 0)
        put(1); put(1); putInt(0x13)
        putInt(kdf.memoryKiB); putInt(kdf.iterations); putInt(kdf.parallelism)
        put(salt); put(nonce)
    }.array()

    companion object {
        const val SIZE = 76
        private val MAGIC = byteArrayOf(75, 69, 89, 82, 79, 79, 75, 0)
        fun parse(bytes: ByteArray, allowExpensive: Boolean): VaultHeader {
            if (bytes.size < SIZE + 16) throw InvalidVaultException()
            val buffer = ByteBuffer.wrap(bytes, 0, SIZE)
            val magic = ByteArray(8).also(buffer::get)
            if (!magic.contentEquals(MAGIC) || buffer.short.toInt() != 1 || buffer.short.toInt() != SIZE)
                throw InvalidVaultException()
            val flags = buffer.short.toInt()
            if (flags !in 0..1 || buffer.get().toInt() != 1 || buffer.get().toInt() != 1 || buffer.int != 0x13)
                throw InvalidVaultException()
            val params = KdfParameters(buffer.int, buffer.int, buffer.int)
            params.validate(allowExpensive)
            return VaultHeader(params, flags == 1, ByteArray(32).also(buffer::get), ByteArray(12).also(buffer::get))
        }
    }
}
