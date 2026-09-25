// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.storage

import java.security.MessageDigest
import java.util.HexFormat

/** Common file-name limit: 255 bytes (ext4, APFS) or 255 UTF-16 code units (NTFS); UTF-8 bytes bound both. */
internal const val MAX_FILE_NAME_BYTES = 255

/**
 * The name of a file derived from the file [name]: `prefix + name + suffix` when that has at most [MAX_FILE_NAME_BYTES]
 * UTF-8 bytes. Otherwise [name] is cut at a character boundary and marked with `~` and the first eight hexadecimal
 * digits of its SHA-256, so the result stays recognisable, is the same every time and differs between long names with
 * a common beginning. [prefix] and [suffix] must leave room for at least the marker.
 */
internal fun boundedFileName(name: String, suffix: String, prefix: String = ""): String {
    val available = MAX_FILE_NAME_BYTES - utf8Size(prefix) - utf8Size(suffix)
    if (utf8Size(name) <= available) return prefix + name + suffix
    val digest = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8))
    val marker = "~" + HexFormat.of().formatHex(digest, 0, 4)
    require(available >= marker.length) { "File name affixes too long" }
    val stem = StringBuilder()
    var bytes = 0
    for (codePoint in name.codePoints()) {
        val size = utf8Size(String(Character.toChars(codePoint)))
        if (bytes + size > available - marker.length) break
        stem.appendCodePoint(codePoint)
        bytes += size
    }
    return "$prefix$stem$marker$suffix"
}

private fun utf8Size(value: String): Int = value.toByteArray(Charsets.UTF_8).size
