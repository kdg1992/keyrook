// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.report

import app.keyrook.core.model.EntryData
import app.keyrook.core.model.Vault
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The expiry date of one active entry. The vault model has a single expiry date per entry
 * ([app.keyrook.core.model.Entry.expiresOn]), used for domains, certificates, contracts and any other entry type
 * alike. [domain] is the domain name of a domain entry when that field is not hidden. No secret is included.
 */
data class ExpiryItem(val entryId: String, val date: LocalDate, val title: String, val typeKey: String,
                      val customer: String?, val project: String?, val domain: String?)

/** Active entries with an expiry date, sorted by date and title. */
fun expiryItems(vault: Vault): List<ExpiryItem> {
    val customers = vault.customers.associate { it.id to it.name }
    val projects = vault.projects.associate { it.id to it.name }
    return vault.entries.filter { it.deletedAt == null && it.expiresOn != null }.sortedWith(titleOrder).map { entry ->
        ExpiryItem(entry.id, LocalDate.parse(entry.expiresOn), entry.title, entry.data.typeKey(),
            vault.customerIdOf(entry)?.let { customers[it] }, entry.projectId?.let { projects[it] },
            (entry.data as? EntryData.Domain)?.let { visible(it.name) })
    }.sortedBy { it.date }
}

/** Longest reminder offer, in days before the expiry date. */
const val MAX_REMINDER_DAYS = 365

/**
 * An RFC 5545 calendar with one all-day event per item: CRLF line ends, lines folded at 75 octets, escaped text
 * values and a UID derived from the entry ID, so importing a later export updates the same events. [reminderDays]
 * adds a display alarm that many days before the date; null adds none.
 */
fun expiryIcs(items: List<ExpiryItem>, text: ReportText, stamp: Instant, reminderDays: Int? = null): String {
    require(reminderDays == null || reminderDays in 1..MAX_REMINDER_DAYS)
    val date = DateTimeFormatter.BASIC_ISO_DATE
    val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Keyrook//Expiry export//EN",
        "CALSCALE:GREGORIAN", "METHOD:PUBLISH")
    items.forEach { item ->
        val summary = "${text.text("report.expires")}: ${item.title}" + (item.domain?.let { " ($it)" } ?: "")
        val description = listOfNotNull("${text.text("report.type")}: ${text.text("entry.type.${item.typeKey}")}",
            item.customer?.let { "${text.text("common.customer")}: $it" },
            item.project?.let { "${text.text("common.project")}: $it" },
            item.domain?.let { "${text.text("field.domain")}: $it" }).joinToString("\n")
        lines += listOf("BEGIN:VEVENT", "UID:${item.entryId}-expiry@keyrook",
            "DTSTAMP:${timestamp.format(stamp.truncatedTo(ChronoUnit.SECONDS))}", "DTSTART;VALUE=DATE:${item.date.format(date)}", "DTEND;VALUE=DATE:${item.date.plusDays(1).format(date)}",
            "SUMMARY:${icsText(summary)}", "DESCRIPTION:${icsText(description)}", "TRANSP:TRANSPARENT")
        if (reminderDays != null) lines += listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:${icsText(summary)}",
            "TRIGGER:-P${reminderDays}D", "END:VALARM")
        lines += "END:VEVENT"
    }
    lines += "END:VCALENDAR"
    return lines.joinToString("") { foldIcsLine(it) + "\r\n" }
}

/**
 * Escapes an RFC 5545 TEXT value: backslash, semicolon and comma get a backslash, every line break becomes `\n`.
 * Other control characters, which TEXT does not allow, become spaces; unpaired surrogates become U+FFFD.
 */
fun icsText(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        val char = value[index]
        when {
            char == '\r' && value.getOrNull(index + 1) == '\n' -> { append("\\n"); index++ }
            char == '\r' || char == '\n' -> append("\\n")
            char == '\\' || char == ';' || char == ',' -> append('\\').append(char)
            char == '\t' -> append(char)
            char < ' ' || char == '\u007f' -> append(' ')
            char.isHighSurrogate() && value.getOrNull(index + 1)?.isLowSurrogate() == true -> { append(char).append(value[index + 1]); index++ }
            char.isSurrogate() -> append('�')
            else -> append(char)
        }
        index++
    }
}

/**
 * Folds one content line (without its line end) into physical lines of at most 75 octets of UTF-8, joined by CRLF
 * and a space as RFC 5545 section 3.1 requires. Multi-octet characters are never split.
 */
fun foldIcsLine(line: String): String = buildString(line.length + line.length / 70 * 3) {
    var used = 0
    var index = 0
    while (index < line.length) {
        val codePoint = line.codePointAt(index)
        val size = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (used + size > 75) { append("\r\n "); used = 1 }
        appendCodePoint(codePoint)
        used += size
        index += Character.charCount(codePoint)
    }
}

/**
 * An RFC 4180 table (CRLF record ends, every field quoted, quotes doubled) with a header row. Cells that a
 * spreadsheet could read as a formula, starting with `=`, `+`, `-`, `@`, tab or carriage return, get a leading `'`.
 */
fun expiryCsv(items: List<ExpiryItem>, text: ReportText): String {
    val header = listOf(text.text("report.expires"), text.text("report.title"), text.text("report.type"),
        text.text("common.customer"), text.text("common.project"), text.text("field.domain"))
    val rows = items.map {
        listOf(it.date.toString(), it.title, text.text("entry.type.${it.typeKey}"), it.customer.orEmpty(),
            it.project.orEmpty(), it.domain.orEmpty())
    }
    return (listOf(header) + rows).joinToString("") { row -> row.joinToString(",") { csvCell(it) } + "\r\n" }
}

/** One quoted CSV field with formula injection neutralized; see [expiryCsv]. */
fun csvCell(value: String): String {
    val safe = if (value.isNotEmpty() && value[0] in "=+-@\t\r") "'$value" else value
    return "\"" + safe.replace("\"", "\"\"") + "\""
}
