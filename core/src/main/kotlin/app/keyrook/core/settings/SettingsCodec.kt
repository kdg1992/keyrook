// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** Plaintext application preferences. Holds vault and backup paths and choices only, never credentials, key-file paths or vault content. */
@Serializable
data class SettingsDocument(
    val version: Int = SettingsCodec.VERSION,
    val theme: String? = null,
    val inactivityMinutes: Int? = null,
    val clipboardSeconds: Long? = null,
    val lastVaultPath: String? = null,
    val backups: Map<String, BackupSettingsDocument> = emptyMap(),
)

@Serializable
data class BackupSettingsDocument(val folder: String, val latest: Int, val daily: Int, val enabled: Boolean)

/** Structural decoding only; callers validate every value against the choices they offer. */
object SettingsCodec {
    const val VERSION = 1
    const val MAX_BYTES = 256 * 1024
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = true
    }

    fun encode(document: SettingsDocument): ByteArray {
        require(document.version == VERSION)
        return json.encodeToString(SettingsDocument.serializer(), document).toByteArray(Charsets.UTF_8)
            .also { require(it.size <= MAX_BYTES) { "Settings exceed size limit" } }
    }

    /** Returns null for oversized, malformed, unknown-version or otherwise unreadable input. */
    fun decode(bytes: ByteArray): SettingsDocument? {
        if (bytes.size > MAX_BYTES) return null
        val text = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: CharacterCodingException) { return null }
        val document = try { json.decodeFromString(SettingsDocument.serializer(), text) } catch (_: Exception) { return null }
        return document.takeIf { it.version == VERSION }
    }
}
