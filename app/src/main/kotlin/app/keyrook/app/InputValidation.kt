// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.EntryData
import app.keyrook.core.model.ReservedTags
import app.keyrook.core.model.Vault
import app.keyrook.core.otp.Totp
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

/** Specific, translatable reasons why one editor input cannot be saved. Keys never depend on the displayed language. */
internal enum class InputProblem(val key: String) {
    TITLE_REQUIRED("validation.titleRequired"),
    TOO_LONG("validation.tooLong"),
    INVALID_DATE("validation.date"),
    PORT_REQUIRED("validation.portRequired"),
    INVALID_PORT("validation.port"),
    TAG_TOO_LONG("validation.tagTooLong"),
    TOO_MANY_TAGS("validation.tagCount"),
    TAG_REQUIRED("validation.tagRequired"),
    TAG_COMMA("validation.tagComma"),
    TAG_RESERVED("validation.tagReserved"),
    FIELD_EXISTS("validation.fieldExists"),
    INVALID_TOTP("validation.totp"),
}

/** A field-level problem; [limit] is the maximum that was exceeded, where the message names one. */
internal data class InputError(val problem: InputProblem, val limit: Int? = null) {
    fun message(locale: Locale = UiText.locale): String =
        if (limit == null) UiText.localized(locale, problem.key) else UiText.localized(locale, problem.key, limit)
}

/** Result of reading the optional expiry text; only [Valid] dates are stored, always in ISO form. */
internal sealed interface ExpiryInput {
    data object Empty : ExpiryInput
    data object Invalid : ExpiryInput
    data class Valid(val date: LocalDate) : ExpiryInput {
        val normalized: String get() = date.toString()
    }
}

/**
 * Accepts ISO dates (2026-12-31) and German dates (31.12.2026, 1.2.2026, 31.12.26). Two-digit years are
 * always 2000–2099, because expiry dates lie ahead. Impossible calendar dates such as 29.02.2027 are invalid.
 */
internal object ExpiryDates {
    private const val MAX_INPUT = 10
    private val iso = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})")
    private val german = Regex("([0-9]{1,2})\\.([0-9]{1,2})\\.([0-9]{2}|[0-9]{4})")

    fun parse(text: String): ExpiryInput {
        val input = text.trim()
        if (input.isEmpty()) return ExpiryInput.Empty
        if (input.length > MAX_INPUT) return ExpiryInput.Invalid
        iso.matchEntire(input)?.let { match ->
            val (year, month, day) = match.destructured
            return date(year.toInt(), month.toInt(), day.toInt())
        }
        german.matchEntire(input)?.let { match ->
            val (day, month, year) = match.destructured
            val fullYear = if (year.length == 2) 2000 + year.toInt() else year.toInt()
            return date(fullYear, month.toInt(), day.toInt())
        }
        return ExpiryInput.Invalid
    }

    /** The stored ISO value, null for no expiry; invalid input is refused instead of being guessed. */
    fun normalize(text: String): String? = when (val parsed = parse(text)) {
        ExpiryInput.Empty -> null
        ExpiryInput.Invalid -> throw IllegalArgumentException("Invalid expiry date")
        is ExpiryInput.Valid -> parsed.normalized
    }

    /** One year after the entered date, or after [today] when no valid date is entered; 29 February becomes 28 February. */
    fun plusOneYear(text: String, today: LocalDate): String =
        ((parse(text) as? ExpiryInput.Valid)?.date ?: today).plusYears(1).toString()

    private fun date(year: Int, month: Int, day: Int): ExpiryInput =
        if (year < 1) ExpiryInput.Invalid
        else try { ExpiryInput.Valid(LocalDate.of(year, month, day)) } catch (_: DateTimeException) { ExpiryInput.Invalid }
}

/** Editable port inputs of the entry types that have one. */
internal enum class PortSlot { TRANSFER, SERVER, IMAP, POP3, SMTP }

/** Digits only, 1–65535; the model has no protocol default, so an empty port is required. */
internal fun parsePort(text: String): Int? {
    val input = text.trim()
    if (!input.matches(Regex("[0-9]{1,5}"))) return null
    return input.toInt().takeIf { it in 1..65535 }
}

internal fun portError(text: String): InputError? = when {
    text.isBlank() -> InputError(InputProblem.PORT_REQUIRED)
    parsePort(text) == null -> InputError(InputProblem.INVALID_PORT)
    else -> null
}

/** Port texts currently shown: unsaved drafts where edited, otherwise the model value. Hidden endpoints are excluded. */
internal fun editorPorts(data: EntryData, drafts: Map<PortSlot, String>): Map<PortSlot, String> {
    fun shown(slot: PortSlot, port: Int) = slot to (drafts[slot] ?: port.toString())
    return when (data) {
        is EntryData.Transfer -> mapOf(shown(PortSlot.TRANSFER, data.port))
        is EntryData.Server -> mapOf(shown(PortSlot.SERVER, data.port))
        is EntryData.Email -> listOfNotNull(data.imap?.let { shown(PortSlot.IMAP, it.port) },
            data.pop3?.let { shown(PortSlot.POP3, it.port) }, data.smtp?.let { shown(PortSlot.SMTP, it.port) }).toMap()
        else -> emptyMap()
    }
}

internal fun expiryError(text: String): InputError? =
    if (ExpiryDates.parse(text) == ExpiryInput.Invalid) InputError(InputProblem.INVALID_DATE) else null

/** Mirrors the limits of [Vault.validate] and [editedEntry] so problems are named before saving. */
internal fun titleError(text: String): InputError? = when {
    text.isBlank() -> InputError(InputProblem.TITLE_REQUIRED)
    text.length > MAX_TITLE_CHARS -> InputError(InputProblem.TOO_LONG, MAX_TITLE_CHARS)
    else -> null
}

internal fun tagsError(text: String): InputError? {
    val tags = text.split(',').map(String::trim).filter(String::isNotEmpty)
    return when {
        tags.size > MAX_TAGS -> InputError(InputProblem.TOO_MANY_TAGS, MAX_TAGS)
        tags.any { it.length > MAX_TAG_CHARS } -> InputError(InputProblem.TAG_TOO_LONG, MAX_TAG_CHARS)
        else -> null
    }
}

/** One tag for a bulk action, already trimmed; mirrors the checks of the core tag change. */
internal fun bulkTagError(tag: String): InputError? = when {
    tag.isBlank() -> InputError(InputProblem.TAG_REQUIRED)
    tag.length > MAX_TAG_CHARS -> InputError(InputProblem.TAG_TOO_LONG, MAX_TAG_CHARS)
    ',' in tag -> InputError(InputProblem.TAG_COMMA)
    ReservedTags.isReserved(tag) -> InputError(InputProblem.TAG_RESERVED)
    else -> null
}

internal fun lengthError(text: String, maximum: Int = Vault.MAX_FIELD_CHARS): InputError? =
    if (text.length > maximum) InputError(InputProblem.TOO_LONG, maximum) else null

/** A new custom field name: blank means "not yet entered", which disables adding without showing an error. */
internal fun customFieldNameError(name: String, existing: Collection<String>): InputError? = when {
    name.length > MAX_TAG_CHARS -> InputError(InputProblem.TOO_LONG, MAX_TAG_CHARS)
    name in existing -> InputError(InputProblem.FIELD_EXISTS)
    else -> null
}

/** Position of the web login's TOTP secret among the editor values, or null without such a field. */
internal fun EntryData.totpIndex(): Int? =
    if (this is EntryData.Web && totp != null) fields().indexOfFirst { it === totp } else null

/**
 * An empty TOTP field stays allowed; anything else must be accepted by the core parser. The temporary array is
 * erased; the editor text itself is an immutable UI string (see SECURITY.md).
 */
internal fun totpError(text: String): InputError? {
    if (text.isBlank()) return null
    val chars = text.toCharArray()
    return try {
        if (Totp.isValid(chars)) null else InputError(InputProblem.INVALID_TOTP)
    } finally { chars.fill('\u0000') }
}

/**
 * The editor's TOTP field: its position and the value stored before this edit (null when the field is new). An
 * invalid value the user has not changed in this edit is only a warning, so entries imported with such a value stay
 * editable; a changed value must be empty or valid.
 */
internal data class TotpSlot(val index: Int, val stored: String?) {
    /** The stored value is a TOTP seed; debug output only says whether one exists. */
    override fun toString(): String = "TotpSlot(index=$index, stored=${if (stored == null) "null" else "[redacted]"})"
}

/** The TOTP slot of the edited [EntryData] given the entry's data and values when the editor opened. */
internal fun EntryData.totpSlot(initial: EntryData, initialValues: List<String>): TotpSlot? =
    totpIndex()?.let { index -> TotpSlot(index, initial.totpIndex()?.let(initialValues::getOrNull)) }

internal data class EditorValidation(
    val title: InputError? = null,
    val tags: InputError? = null,
    val notes: InputError? = null,
    val expiry: InputError? = null,
    val values: Map<Int, InputError> = emptyMap(),
    val ports: Map<PortSlot, InputError> = emptyMap(),
    /** Shown below their field like errors, but do not prevent saving. */
    val warnings: Map<Int, InputError> = emptyMap(),
) {
    val valid: Boolean get() = title == null && tags == null && notes == null && expiry == null && values.isEmpty() && ports.isEmpty()

    /** The message below value [index]: a blocking error first, otherwise a warning. */
    fun valueMessage(index: Int): InputError? = values[index] ?: warnings[index]
}

internal fun validateEditor(title: String, tags: String, notes: String, expires: String, values: List<String>,
                            ports: Map<PortSlot, String>, totp: TotpSlot? = null): EditorValidation {
    val totpProblem = totp?.let { slot -> values.getOrNull(slot.index)?.let(::totpError) }
    val untouched = totp != null && values.getOrNull(totp.index) == totp.stored
    return EditorValidation(
        title = titleError(title),
        tags = tagsError(tags),
        notes = lengthError(notes),
        expiry = expiryError(expires),
        values = values.withIndex().mapNotNull { (index, value) ->
            (lengthError(value) ?: if (index == totp?.index && !untouched) totpProblem else null)?.let { index to it }
        }.toMap(),
        ports = ports.mapNotNull { (slot, text) -> portError(text)?.let { slot to it } }.toMap(),
        warnings = if (totp != null && untouched && totpProblem != null) mapOf(totp.index to totpProblem) else emptyMap(),
    )
}

internal const val MAX_TITLE_CHARS = 4096
internal const val MAX_TAGS = 100
internal const val MAX_TAG_CHARS = 256
