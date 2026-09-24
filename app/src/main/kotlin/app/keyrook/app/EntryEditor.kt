// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import java.time.Instant
import java.util.UUID
import java.util.Locale

enum class EntryType(private val key: String) {
    WEB("web"), TRANSFER("transfer"), EMAIL("email"), PANEL("panel"),
    SERVER("server"), SSH("ssh"), DOMAIN("domain"), CUSTOM("custom");

    val label: String get() = label(UiText.locale)
    fun label(locale: Locale): String = UiText.localized(locale, "entry.type.$key")
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
        EntryType.CUSTOM -> EntryData.Custom(mapOf(UiText.text("field.value") to field(true)))
    }
}

fun EntryData.labels(locale: Locale = UiText.locale): List<String> = when (this) {
    is EntryData.Web -> listOf(UiText.localized(locale, "field.url"), UiText.localized(locale, "field.username"), UiText.localized(locale, "field.password")) + if (totp != null) listOf(UiText.localized(locale, "field.totp")) else emptyList()
    is EntryData.Transfer -> listOf(UiText.localized(locale, "field.host"), UiText.localized(locale, "field.username"), UiText.localized(locale, "field.password"), UiText.localized(locale, "field.directory"))
    is EntryData.Email -> listOf(UiText.localized(locale, "field.address"), UiText.localized(locale, "field.username"), UiText.localized(locale, "field.password")) +
        listOfNotNull(imap?.let { UiText.localized(locale, "field.imap") }, pop3?.let { UiText.localized(locale, "field.pop3") }, smtp?.let { UiText.localized(locale, "field.smtp") })
    is EntryData.Panel -> listOf(UiText.localized(locale, "field.url"), UiText.localized(locale, "field.username"), UiText.localized(locale, "field.password"), UiText.localized(locale, "field.role"))
    is EntryData.Server -> listOf(UiText.localized(locale, "field.host"), UiText.localized(locale, "field.username"), UiText.localized(locale, "field.password"), UiText.localized(locale, "field.os"), UiText.localized(locale, "field.role"))
    is EntryData.Ssh -> listOf(UiText.localized(locale, "field.private"), UiText.localized(locale, "field.public"), UiText.localized(locale, "field.passphrase"), UiText.localized(locale, "field.fingerprint"))
    is EntryData.Domain -> listOf(UiText.localized(locale, "field.domain"), UiText.localized(locale, "field.registrar"), UiText.localized(locale, "field.dns"))
    is EntryData.Custom -> values.keys.toList()
}

/** Password generation is tied to field semantics, never to a translated or custom label. */
internal fun EntryData.canGenerateSecret(index: Int): Boolean = when (this) {
    is EntryData.Web, is EntryData.Email, is EntryData.Panel -> index == 2
    is EntryData.Transfer, is EntryData.Server -> index == 2
    is EntryData.Ssh -> index == 2
    is EntryData.Custom -> values.values.elementAtOrNull(index)?.hidden == true
    is EntryData.Domain -> false
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
    require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS)
    require(values.size == data.fields().size && hidden.size == values.size)
    require(values.all { it.length <= Vault.MAX_FIELD_CHARS } && notes.length <= Vault.MAX_FIELD_CHARS)
    val parsedTags = tags.split(',').map(String::trim).filter(String::isNotEmpty)
    require(parsedTags.size <= MAX_TAGS && parsedTags.all { it.length <= MAX_TAG_CHARS })
    val expiry = ExpiryDates.normalize(expires)
    val owned = mutableListOf<Secret>()
    var index = 0
    fun secret(text: String): Secret {
        val chars = text.toCharArray()
        return try { Secret(chars).also(owned::add) } finally { chars.fill('\u0000') }
    }
    fun copyData(value: EntryData) = value.mapFields { field -> field.copy(value = field.value.copy().also(owned::add)) }
    try {
        val changed = data.mapFields { field ->
            val i = index++
            field.copy(value = secret(values[i]), hidden = hidden[i])
        }
        val now = maxOf(Instant.now(), source?.modifiedAt?.let(Instant::parse) ?: Instant.MIN).toString()
        val history = source?.history.orEmpty().takeLast(99).map {
            it.copy(data = copyData(it.data))
        } + listOfNotNull(source?.let { HistoryItem(now, copyData(it.data)) })
        return Entry(source?.id ?: UUID.randomUUID().toString(), title, changed, source?.createdAt ?: now, now,
            source?.customerId, source?.projectId, parsedTags, secret(notes), expiry, source?.deletedAt, history)
    } catch (failure: Throwable) {
        owned.forEach(Secret::close)
        throw failure
    }
}
