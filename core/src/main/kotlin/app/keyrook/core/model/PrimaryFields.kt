// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.model

/**
 * Custom field labels, compared case-insensitively, that mark the user name, password and address of a custom entry,
 * as imports from Bitwarden and KeePass create them.
 */
object PrimaryFieldNames {
    val USERNAME: Set<String> = setOf("username", "user", "benutzername")
    val PASSWORD: Set<String> = setOf("password", "passwort", "passphrase")
    val URL: Set<String> = setOf("url", "url1", "uri", "host")

    fun isUsername(label: String): Boolean = label.lowercase() in USERNAME
    fun isPassword(label: String): Boolean = label.lowercase() in PASSWORD
    fun isUrl(label: String): Boolean = label.lowercase() in URL
}

/** The first field whose label matches [matches], preferring one with a non-blank value. */
private fun EntryData.Custom.named(matches: (String) -> Boolean): Field? {
    val candidates = values.filterKeys(matches).values
    return candidates.firstOrNull { field -> field.value.useChars { chars -> chars.any { !it.isWhitespace() } } }
        ?: candidates.firstOrNull()
}

/** The user name of a typed entry, or the custom field labelled as one (see [PrimaryFieldNames.USERNAME]). */
fun EntryData.primaryUsername(): Field? = when (this) {
    is EntryData.Web -> username
    is EntryData.Transfer -> username
    is EntryData.Email -> username
    is EntryData.Panel -> username
    is EntryData.Server -> username
    is EntryData.Ssh, is EntryData.Domain -> null
    is EntryData.Custom -> named(PrimaryFieldNames::isUsername)
}

/**
 * The password, or the passphrase of an SSH key. For a custom entry the field labelled as a password (see
 * [PrimaryFieldNames.PASSWORD]); without one, the first hidden field that is not labelled as a user name, address or
 * TOTP secret, so an import that hides the user name never offers it as the password.
 */
fun EntryData.primarySecret(): Field? = when (this) {
    is EntryData.Web -> password
    is EntryData.Transfer -> password
    is EntryData.Email -> password
    is EntryData.Panel -> password
    is EntryData.Server -> password
    is EntryData.Ssh -> passphrase
    is EntryData.Domain -> null
    is EntryData.Custom -> named(PrimaryFieldNames::isPassword) ?: values.entries.firstOrNull { (label, field) ->
        field.hidden && !PrimaryFieldNames.isUsername(label) && !PrimaryFieldNames.isUrl(label) &&
            label.lowercase() != "totp"
    }?.value
}

/**
 * The address to open: the URL of a web or panel entry; for a custom entry the field labelled as an address (see
 * [PrimaryFieldNames.URL]), or else the first field of kind [FieldKind.URL].
 */
fun EntryData.primaryUrl(): Field? = when (this) {
    is EntryData.Web -> url
    is EntryData.Panel -> url
    is EntryData.Custom -> named(PrimaryFieldNames::isUrl) ?: values.values.firstOrNull { it.kind == FieldKind.URL }
    else -> null
}
