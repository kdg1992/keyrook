// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.model

/**
 * Limits and lenient format checks for the plain customer and project metadata of schema 2. A value of null means
 * "not set"; a set value must be non-blank, trimmed and free of control characters (the description may contain line
 * breaks and tabs). The checks only reject clearly broken input; they do not prove that an address or site exists.
 */
object OrganizationMetadata {
    const val MAX_CONTACT_NAME_CHARS = 256
    const val MAX_EMAIL_CHARS = 254
    const val MAX_PHONE_CHARS = 64
    const val MAX_WEBSITE_CHARS = 2_048
    const val MAX_DESCRIPTION_CHARS = 4_096

    private val phoneCharacters = Regex("[0-9+()./\\- ]+")
    private val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://")

    fun isValidContactName(value: String?): Boolean = value == null || line(value, MAX_CONTACT_NAME_CHARS)

    /** One `@` with a non-empty local part and a domain containing a dot that neither starts nor ends it. */
    fun isValidEmail(value: String?): Boolean {
        if (value == null) return true
        if (!line(value, MAX_EMAIL_CHARS) || value.any(Char::isWhitespace)) return false
        val at = value.indexOf('@')
        if (at <= 0 || at != value.lastIndexOf('@')) return false
        val domain = value.substring(at + 1)
        return domain.length >= 3 && '.' in domain && !domain.startsWith('.') && !domain.endsWith('.')
    }

    /** Digits and the usual separators `+ ( ) . / -` and spaces, with at least one digit. */
    fun isValidPhone(value: String?): Boolean =
        value == null || (line(value, MAX_PHONE_CHARS) && phoneCharacters.matches(value) && value.any(Char::isDigit))

    /** A host or address without whitespace; if it names a scheme, it must be `http` or `https` followed by more. */
    fun isValidWebsite(value: String?): Boolean {
        if (value == null) return true
        if (!line(value, MAX_WEBSITE_CHARS) || value.any(Char::isWhitespace)) return false
        val named = scheme.find(value)?.groupValues?.get(1)?.lowercase() ?: return true
        return (named == "http" || named == "https") && value.length > named.length + 3
    }

    fun isValidDescription(value: String?): Boolean = value == null || (value.isNotBlank() && value == value.trim() &&
        value.length <= MAX_DESCRIPTION_CHARS && value.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' })

    /** The stored form of a typed value: trimmed, and null when nothing is left. */
    fun normalize(value: String): String? = value.trim().takeIf { it.isNotEmpty() }

    private fun line(value: String, max: Int): Boolean =
        value.isNotBlank() && value == value.trim() && value.length <= max && value.none(Char::isISOControl)
}
