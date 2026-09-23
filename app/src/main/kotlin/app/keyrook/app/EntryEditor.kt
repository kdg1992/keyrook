// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import java.time.Instant
import java.util.UUID

enum class EntryType(val label: String) {
    WEB("Web-Login"), TRANSFER("Dateiübertragung"), EMAIL("E-Mail"), PANEL("Webhosting-Panel"),
    SERVER("Server"), SSH("SSH-Schlüssel"), DOMAIN("Domain"), CUSTOM("Freier Eintrag")
}

fun EntryData.type(): EntryType = when (this) {
    is EntryData.Web -> EntryType.WEB
    is EntryData.Transfer -> EntryType.TRANSFER
    is EntryData.Email -> EntryType.EMAIL
    is EntryData.Panel -> EntryType.PANEL
    is EntryData.Server -> EntryType.SERVER
    is EntryData.Ssh -> EntryType.SSH
    is EntryData.Domain -> EntryType.DOMAIN
    is EntryData.Custom -> EntryType.CUSTOM
}

fun blankData(type: EntryType): EntryData {
    fun field(hidden: Boolean = false, url: Boolean = false) =
        Field(Secret(charArrayOf()), hidden, if (url) FieldKind.URL else FieldKind.TEXT)
    return when (type) {
        EntryType.WEB -> EntryData.Web(field(url = true), field(), field(true), field(true))
        EntryType.TRANSFER -> EntryData.Transfer(field(), 22, TransferProtocol.SFTP, field(), field(true), field())
        EntryType.EMAIL -> EntryData.Email(field(), field(), field(true))
        EntryType.PANEL -> EntryData.Panel(field(url = true), field(), field(true), field())
        EntryType.SERVER -> EntryData.Server(field(), 22, field(), field(true), field(), field())
        EntryType.SSH -> EntryData.Ssh(SshKeyType.ED25519, field(true), field(), field(true), field())
        EntryType.DOMAIN -> EntryData.Domain(field(), field(), field())
        EntryType.CUSTOM -> EntryData.Custom(mapOf("Wert" to field(true)))
    }
}

fun EntryData.labels(): List<String> = when (this) {
    is EntryData.Web -> listOf("URL", "Benutzername", "Passwort") + if (totp != null) listOf("TOTP-Secret") else emptyList()
    is EntryData.Transfer -> listOf("Host", "Benutzername", "Passwort", "Startverzeichnis")
    is EntryData.Email -> listOf("Adresse", "Benutzername", "Passwort") +
        listOfNotNull(imap?.let { "IMAP-Host" }, pop3?.let { "POP3-Host" }, smtp?.let { "SMTP-Host" })
    is EntryData.Panel -> listOf("URL", "Benutzername", "Passwort", "Rolle")
    is EntryData.Server -> listOf("Host", "Benutzername", "Passwort", "Betriebssystem", "Rolle")
    is EntryData.Ssh -> listOf("Privater Schlüssel", "Öffentlicher Schlüssel", "Passphrase", "Fingerprint")
    is EntryData.Domain -> listOf("Domain", "Registrar", "DNS-Hinweise")
    is EntryData.Custom -> values.keys.toList()
}

fun EntryData.mapFields(transform: (Field) -> Field): EntryData = when (this) {
    is EntryData.Web -> copy(url = transform(url), username = transform(username), password = transform(password), totp = totp?.let(transform))
    is EntryData.Transfer -> copy(host = transform(host), username = transform(username), password = transform(password), directory = transform(directory))
    is EntryData.Email -> copy(address = transform(address), username = transform(username), password = transform(password),
        imap = imap?.let { it.copy(host = transform(it.host)) }, pop3 = pop3?.let { it.copy(host = transform(it.host)) }, smtp = smtp?.let { it.copy(host = transform(it.host)) })
    is EntryData.Panel -> copy(url = transform(url), username = transform(username), password = transform(password), role = transform(role))
    is EntryData.Server -> copy(host = transform(host), username = transform(username), password = transform(password), operatingSystem = transform(operatingSystem), role = transform(role))
    is EntryData.Ssh -> copy(privateKey = transform(privateKey), publicKey = transform(publicKey), passphrase = transform(passphrase), fingerprint = transform(fingerprint))
    is EntryData.Domain -> copy(name = transform(name), registrar = transform(registrar), dnsNotes = transform(dnsNotes))
    is EntryData.Custom -> copy(values = values.mapValues { transform(it.value) })
}

/** The editor's immutable text values are discarded on cancel/lock; JVM copies cannot be erased. */
fun editedEntry(source: Entry?, data: EntryData, title: String, tags: String, notes: String, expires: String,
                values: List<String>, hidden: List<Boolean>): Entry {
    require(title.isNotBlank())
    require(values.size == data.fields().size && hidden.size == values.size)
    var index = 0
    fun secret(text: String): Secret {
        val chars = text.toCharArray()
        return try { Secret(chars) } finally { chars.fill('\u0000') }
    }
    val changed = data.mapFields { field ->
        val i = index++
        field.copy(value = secret(values[i]), hidden = hidden[i])
    }
    val now = Instant.now().toString()
    val history = source?.history.orEmpty().takeLast(99).map {
        it.copy(data = it.data.mapFields { field -> field.copy(value = field.value.copy()) })
    } + listOfNotNull(source?.let { HistoryItem(now, it.data.mapFields { field -> field.copy(value = field.value.copy()) }) })
    return Entry(source?.id ?: UUID.randomUUID().toString(), title, changed, source?.createdAt ?: now, now,
        source?.customerId, source?.projectId, tags.split(',').map(String::trim).filter(String::isNotEmpty), secret(notes),
        expires.ifBlank { null }, source?.deletedAt, history)
}
