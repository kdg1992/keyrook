// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.report

import app.keyrook.core.model.Entry
import app.keyrook.core.model.EntryData
import app.keyrook.core.model.MailEndpoint
import app.keyrook.core.model.SshKeyType
import app.keyrook.core.model.Vault
import java.time.LocalDate

/** Escapes text for HTML element content and quoted attribute values. */
fun escapeHtml(value: String): String = buildString(value.length) {
    value.forEach { char ->
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(char)
        }
    }
}

/**
 * The rows of one entry on a handover sheet as (label key, value) pairs. Only fields with a fixed, non-secret meaning
 * are considered, and only when they are not marked hidden. Passwords, TOTP secrets, private keys, passphrases, DNS
 * notes, notes, history and custom field values are never read, whatever their hidden option says.
 */
internal fun handoverRows(entry: Entry, vault: Vault): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String?>>()
    fun endpoint(key: String, value: MailEndpoint?) {
        value?.let { rows += key to visible(it.host)?.let { host -> "$host:${it.port} (${it.encryption.name})" } }
    }
    fun titles(ids: List<String>) = ids.mapNotNull { id -> vault.entries.firstOrNull { it.id == id && it.deletedAt == null }?.title }
    when (val data = entry.data) {
        is EntryData.Web -> { rows += "field.url" to visible(data.url); rows += "field.username" to visible(data.username) }
        is EntryData.Transfer -> {
            rows += "field.host" to visible(data.host); rows += "report.port" to data.port.toString()
            rows += "report.protocol" to data.protocol.name; rows += "field.username" to visible(data.username)
            rows += "field.directory" to visible(data.directory)
        }
        is EntryData.Email -> {
            rows += "field.address" to visible(data.address); rows += "field.username" to visible(data.username)
            endpoint("field.imap", data.imap); endpoint("field.pop3", data.pop3); endpoint("field.smtp", data.smtp)
        }
        is EntryData.Panel -> {
            rows += "field.url" to visible(data.url); rows += "field.username" to visible(data.username)
            rows += "field.role" to visible(data.role)
        }
        is EntryData.Server -> {
            rows += "field.host" to visible(data.host); rows += "report.port" to data.port.toString()
            rows += "field.username" to visible(data.username); rows += "field.os" to visible(data.operatingSystem)
            rows += "field.role" to visible(data.role)
        }
        is EntryData.Ssh -> {
            rows += "report.keyType" to when (data.keyType) { SshKeyType.ED25519 -> "Ed25519"; SshKeyType.RSA4096 -> "RSA-4096" }
            rows += "field.fingerprint" to visible(data.fingerprint)
            rows += "report.linkedServers" to titles(data.serverIds).joinToString(", ")
        }
        is EntryData.Domain -> {
            rows += "field.domain" to visible(data.name); rows += "field.registrar" to visible(data.registrar)
            rows += "report.registrarLogin" to titles(listOfNotNull(data.registrarLoginId)).joinToString(", ")
        }
        is EntryData.Custom -> Unit
    }
    return rows.mapNotNull { (key, value) -> value?.takeIf { it.isNotBlank() }?.let { key to it } }
}

/**
 * A self-contained HTML handover sheet for the active entries of [customerId] (see [activeEntriesOf]), grouped by
 * type and sorted by title. Every value is escaped; the page has no links, scripts or external resources, and its
 * content security policy forbids loading any. [language] is the page's language tag.
 */
fun handoverSheetHtml(vault: Vault, customerId: String, text: ReportText, language: String, generatedOn: LocalDate): String {
    val customer = vault.customers.first { it.id == customerId }
    val projects = vault.projects.associate { it.id to it.name }
    val entries = vault.activeEntriesOf(customerId)
        .sortedWith(compareBy<Entry> { REPORT_TYPE_KEYS.indexOf(it.data.typeKey()) }.then(titleOrder))
    fun e(value: String) = escapeHtml(value)
    fun t(key: String) = e(text.text(key))
    return buildString {
        append("<!DOCTYPE html>\n<html lang=\"").append(e(language)).append("\">\n<head>\n<meta charset=\"utf-8\">\n")
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">\n")
        append("<title>").append(t("report.handoverTitle")).append(": ").append(e(customer.name)).append("</title>\n")
        append("<style>body{font-family:sans-serif;margin:2em;max-width:60em}table{border-collapse:collapse;margin-bottom:1.5em}")
        append("th,td{border:1px solid #999;padding:.25em .5em;text-align:left;vertical-align:top}th{width:14em}</style>\n")
        append("</head>\n<body>\n")
        append("<h1>").append(t("report.handoverTitle")).append(": ").append(e(customer.name)).append("</h1>\n")
        append("<p>").append(t("report.generated")).append(": ").append(generatedOn).append("</p>\n")
        append("<p>").append(t("report.handoverNotice")).append("</p>\n")
        if (entries.isEmpty()) append("<p>").append(t("report.none")).append("</p>\n")
        entries.forEach { entry ->
            append("<h2>").append(e(entry.title)).append("</h2>\n<table>\n")
            val rows = listOf("report.type" to text.text("entry.type.${entry.data.typeKey()}")) +
                listOfNotNull(entry.projectId?.let { projects[it] }?.let { "common.project" to it },
                    entry.tags.takeIf { it.isNotEmpty() }?.let { "report.tags" to it.joinToString(", ") },
                    entry.expiresOn?.let { "report.expires" to it }) +
                handoverRows(entry, vault)
            rows.forEach { (key, value) ->
                append("<tr><th>").append(t(key)).append("</th><td>").append(e(value)).append("</td></tr>\n")
            }
            append("</table>\n")
        }
        append("</body>\n</html>\n")
    }
}
