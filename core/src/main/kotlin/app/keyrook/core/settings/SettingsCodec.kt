// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/**
 * Plaintext application preferences. Holds vault, backup and word-list paths and choices only, never credentials, key-file paths,
 * generated secrets, word-list content or vault content.
 *
 * Compatibility: a new preference is added as an optional field with a default, so files written before it existed still decode
 * within the same [SettingsCodec.VERSION]. Unknown keys stay rejected. A change that alters or removes an existing field needs a
 * version bump instead. [language], [windowLock], [updateCheck], [window] and [generator] were added this way.
 */
@Serializable
data class SettingsDocument(
    val version: Int = SettingsCodec.VERSION,
    val theme: String? = null,
    val language: String? = null,
    val inactivityMinutes: Int? = null,
    val clipboardSeconds: Long? = null,
    val windowLock: String? = null,
    val lastVaultPath: String? = null,
    val backups: Map<String, BackupSettingsDocument> = emptyMap(),
    /** Update-check mode chosen in the UI; absent or unknown values mean no automatic check. */
    val updateCheck: String? = null,
    /** Last main-window size, position and maximized state; validated by [WindowGeometry.fromDocument] and again against the screens. */
    val window: WindowSettingsDocument? = null,
    /** Last password generator choices and passphrase word-list path; validated field by field, falling back to defaults. */
    val generator: GeneratorSettingsDocument? = null,
)

/**
 * Password generator choices. Only names, numbers and the path of a user-chosen word list are kept; the list itself is read again
 * from that path when used. Every field is optional so partial or older entries decode and fall back to defaults.
 */
@Serializable
data class GeneratorSettingsDocument(
    val preset: String? = null,
    val length: Int? = null,
    val lowercase: Boolean? = null,
    val uppercase: Boolean? = null,
    val digits: Boolean? = null,
    val symbols: Boolean? = null,
    val excludeAmbiguous: Boolean? = null,
    val wordListPath: String? = null,
    val wordCount: Int? = null,
    val separator: String? = null,
)

/** Window geometry in window-system units. Every field is optional so partial or older entries decode and fall back to defaults. */
@Serializable
data class WindowSettingsDocument(
    val x: Int? = null,
    val y: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val maximized: Boolean? = null,
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
