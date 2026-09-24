// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException

/** A stable release number. Only the canonical form without leading zeros is accepted, so the tag can be rebuilt exactly. */
data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    init { require(major >= 0 && minor >= 0 && patch >= 0) }

    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val TAG = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$")
        private val PLAIN = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$")

        /** Accepts exactly `v<major>.<minor>.<patch>` with ASCII digits; suffixes such as `-rc1` are refused. */
        fun parseTag(tag: String): ReleaseVersion? = parse(tag, TAG)

        /** Parses the running application's version; development builds such as `dev` return null. */
        fun parseRunning(version: String?): ReleaseVersion? = version?.let { parse(it, PLAIN) }

        private fun parse(value: String, pattern: Regex): ReleaseVersion? {
            if (value.length > 40) return null
            val groups = pattern.matchEntire(value)?.groupValues?.drop(1) ?: return null
            if (groups.any { it.length > 1 && it.startsWith('0') }) return null
            val numbers = groups.map { it.toIntOrNull() ?: return null }
            return ReleaseVersion(numbers[0], numbers[1], numbers[2])
        }
    }
}

/** Latest published stable release as far as the strict checks below can establish it. Notes are plain text only. */
data class LatestRelease(val version: ReleaseVersion, val notes: String)

sealed interface UpdateCheckResult {
    data class UpToDate(val running: ReleaseVersion, val latest: ReleaseVersion) : UpdateCheckResult
    data class UpdateAvailable(val running: ReleaseVersion, val latest: ReleaseVersion, val notes: String) : UpdateCheckResult {
        /** Built from the validated version only; never taken from the response. */
        val releasePage: String get() = ReleaseCheck.releasePage(latest)
    }
    /** The running build has no release version, so nothing was requested or compared. */
    data object DevelopmentBuild : UpdateCheckResult
    /** Any transport, size, status, encoding or validation problem. Deliberately carries no detail. */
    data object Failed : UpdateCheckResult
}

/** Supplies the raw response body of the release query. Implementations perform the only network access; tests inject fakes. */
fun interface ReleaseSource {
    fun latestRelease(): ByteArray
}

/**
 * Network-free evaluation of a release query. The response is untrusted: only the tag, the draft and prerelease flags and an
 * optional plain-text body are read, and no address from it is ever used.
 */
object ReleaseCheck {
    const val REPOSITORY = "kdg1992/keyrook"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val MAX_BODY_BYTES = 256 * 1024
    const val MAX_NOTES_CHARS = 4000

    private val json = Json { isLenient = false }

    fun releasePage(version: ReleaseVersion): String = "https://github.com/$REPOSITORY/releases/tag/v$version"

    /**
     * Returns null unless the body is bounded UTF-8 JSON describing a published, non-draft, non-prerelease canonical tag.
     * Field types are checked exactly: quoted booleans, missing flags and non-string tags or notes are refused.
     */
    fun parseLatest(bytes: ByteArray): LatestRelease? {
        if (bytes.isEmpty() || bytes.size > MAX_BODY_BYTES) return null
        val text = try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: CharacterCodingException) { return null }
        val document = (try { json.parseToJsonElement(text) } catch (_: Exception) { return null }) as? JsonObject ?: return null
        fun flag(name: String): Boolean? = (document[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
        val tag = (document["tag_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        if (flag("draft") != false || flag("prerelease") != false) return null
        val notes = when (val body = document["body"]) {
            null, JsonNull -> ""
            is JsonPrimitive -> if (body.isString) body.content else return null
            else -> return null
        }
        val version = ReleaseVersion.parseTag(tag) ?: return null
        return LatestRelease(version, plainNotes(notes))
    }

    /**
     * Queries [source] only for a release build. Every exception, oversized body and rejected document becomes
     * [UpdateCheckResult.Failed] without detail.
     */
    fun check(runningVersion: String?, source: ReleaseSource): UpdateCheckResult {
        val running = ReleaseVersion.parseRunning(runningVersion) ?: return UpdateCheckResult.DevelopmentBuild
        val bytes = try { source.latestRelease() } catch (_: Exception) { return UpdateCheckResult.Failed }
        val latest = parseLatest(bytes) ?: return UpdateCheckResult.Failed
        return if (latest.version > running) UpdateCheckResult.UpdateAvailable(running, latest.version, latest.notes)
        else UpdateCheckResult.UpToDate(running, latest.version)
    }

    /**
     * Reduces release notes to bounded plain text: line breaks are normalized, tabs become spaces, other control and
     * invisible formatting characters (including bidirectional overrides) are removed, and the length is limited.
     */
    fun plainNotes(text: String): String {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val builder = StringBuilder()
        var index = 0
        while (index < normalized.length && builder.length < MAX_NOTES_CHARS) {
            val codePoint = normalized.codePointAt(index)
            index += Character.charCount(codePoint)
            when {
                codePoint == '\n'.code -> builder.append('\n')
                codePoint == '\t'.code -> builder.append(' ')
                Character.isISOControl(codePoint) -> Unit
                Character.getType(codePoint).toByte() in HIDDEN_TYPES -> Unit
                else -> builder.appendCodePoint(codePoint)
            }
        }
        if (index < normalized.length) builder.append('…')
        return builder.toString().trim()
    }

    private val HIDDEN_TYPES = setOf(Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
        Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR)
}
