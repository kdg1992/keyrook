// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import java.nio.file.Path

/**
 * A refused operation whose reason may be shown: [messageKey] names a message of the UI bundle and [arguments] are its
 * values. Arguments are limits and counts only, never paths, names or other input, because the text is displayed
 * as is. It is an [IllegalArgumentException], so callers that only care about refusal treat it like any other check.
 */
internal class UserFacingException(val messageKey: String, vararg arguments: Any) : IllegalArgumentException(messageKey) {
    val arguments: List<Any> = arguments.toList()

    /** The message in the selected language. */
    fun text(): String = UiText.text(messageKey, *arguments.toTypedArray())
}

/** Throws a [UserFacingException] for [messageKey] unless [condition] holds. */
internal fun requireUserFacing(condition: Boolean, messageKey: String, vararg arguments: Any) {
    if (!condition) throw UserFacingException(messageKey, *arguments)
}

/**
 * The message shown for a failed operation: the specific reason of a [UserFacingException], otherwise the generic
 * text. Other exceptions can contain paths or decrypted input, so their messages are never displayed.
 */
internal fun failureMessage(failure: Throwable?): String =
    (failure as? UserFacingException)?.text() ?: UiText.text("shell.failed")

/** A customer, project or template name as stored: trimmed, not blank and at most [MAX_NAME_CHARS] characters. */
internal fun organizationName(name: String): String {
    val trimmed = name.trim()
    requireUserFacing(trimmed.isNotEmpty(), "error.nameRequired")
    requireUserFacing(trimmed.length <= MAX_NAME_CHARS, "error.nameTooLong", MAX_NAME_CHARS)
    return trimmed
}

/** Length of a key file, the optional second factor. */
internal const val KEY_FILE_BYTES = 32

/** Reads a key file; a missing, unreadable or wrongly sized file is refused with a message that says so. */
internal fun readKeyFile(file: Path): ByteArray {
    val bytes = try { readTransfer(file, KEY_FILE_BYTES) } catch (failure: Exception) {
        throw UserFacingException("error.keyFileInvalid", KEY_FILE_BYTES).apply { initCause(failure) }
    }
    if (bytes.size != KEY_FILE_BYTES) {
        bytes.fill(0)
        throw UserFacingException("error.keyFileInvalid", KEY_FILE_BYTES)
    }
    return bytes
}
