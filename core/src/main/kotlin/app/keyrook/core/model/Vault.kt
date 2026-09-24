// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.model

import app.keyrook.core.crypto.Secret
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

@Serializable
data class Field(val value: Secret, val hidden: Boolean = true, val kind: FieldKind = FieldKind.TEXT)
@Serializable enum class FieldKind { TEXT, URL }
@Serializable enum class TransferProtocol { FTP, SFTP, FTPS }
@Serializable enum class MailEncryption { NONE, STARTTLS, TLS }
@Serializable enum class SshKeyType { ED25519, RSA4096 }

@Serializable
sealed class EntryData {
    abstract fun fields(): List<Field>

    @Serializable @SerialName("web")
    data class Web(val url: Field, val username: Field, val password: Field, val totp: Field? = null) : EntryData() {
        override fun fields() = listOfNotNull(url, username, password, totp)
    }
    @Serializable @SerialName("transfer")
    data class Transfer(val host: Field, val port: Int, val protocol: TransferProtocol, val username: Field,
                        val password: Field, val directory: Field) : EntryData() {
        override fun fields() = listOf(host, username, password, directory)
    }
    @Serializable @SerialName("email")
    data class Email(val address: Field, val username: Field, val password: Field,
                     val imap: MailEndpoint? = null, val pop3: MailEndpoint? = null,
                     val smtp: MailEndpoint? = null) : EntryData() {
        override fun fields() = listOfNotNull(address, username, password, imap?.host, pop3?.host, smtp?.host)
    }
    @Serializable @SerialName("panel")
    data class Panel(val url: Field, val username: Field, val password: Field, val role: Field) : EntryData() {
        override fun fields() = listOf(url, username, password, role)
    }
    @Serializable @SerialName("server")
    data class Server(val host: Field, val port: Int, val username: Field, val password: Field,
                      val operatingSystem: Field, val role: Field) : EntryData() {
        override fun fields() = listOf(host, username, password, operatingSystem, role)
    }
    @Serializable @SerialName("ssh")
    data class Ssh(val keyType: SshKeyType, val privateKey: Field, val publicKey: Field,
                   val passphrase: Field, val fingerprint: Field, val serverIds: List<String> = emptyList()) : EntryData() {
        override fun fields() = listOf(privateKey, publicKey, passphrase, fingerprint)
    }
    @Serializable @SerialName("domain")
    data class Domain(val name: Field, val registrar: Field, val dnsNotes: Field,
                      val registrarLoginId: String? = null) : EntryData() {
        override fun fields() = listOf(name, registrar, dnsNotes)
    }
    @Serializable @SerialName("custom")
    data class Custom(val values: Map<String, Field>) : EntryData() {
        override fun fields() = values.values.toList()
    }
}

@Serializable data class MailEndpoint(val host: Field, val port: Int, val encryption: MailEncryption)
@Serializable data class Customer(val id: String, val name: String)
@Serializable data class Project(val id: String, val name: String, val customerId: String? = null)
@Serializable data class HistoryItem(val changedAt: String, val data: EntryData)

@Serializable
data class Entry(
    val id: String,
    val title: String,
    val data: EntryData,
    val createdAt: String,
    val modifiedAt: String,
    val customerId: String? = null,
    val projectId: String? = null,
    val tags: List<String> = emptyList(),
    val notes: Secret = Secret(charArrayOf()),
    val expiresOn: String? = null,
    val deletedAt: String? = null,
    val history: List<HistoryItem> = emptyList(),
)

@Serializable
data class Vault(
    @Required val schemaVersion: Int = SCHEMA_VERSION,
    @Required val id: String = UUID.randomUUID().toString(),
    @Required val revision: Long = 0,
    @Required val customers: List<Customer> = emptyList(),
    @Required val projects: List<Project> = emptyList(),
    @Required val entries: List<Entry> = emptyList(),
) : AutoCloseable {
    fun validate() {
        require(schemaVersion == SCHEMA_VERSION && revision >= 0) { "Invalid vault version or revision" }
        uuid(id)
        require(customers.size <= 10_000 && projects.size <= 10_000 && entries.size <= 10_000)
        val customerIds = uniqueIds(customers.map { it.id })
        val projectIds = uniqueIds(projects.map { it.id })
        val entryIds = uniqueIds(entries.map { it.id })
        customers.forEach { text(it.name) }
        projects.forEach { text(it.name); require(it.customerId == null || it.customerId in customerIds) }
        val projectById = projects.associateBy { it.id }
        val serverIds = entries.filter { it.data is EntryData.Server }.map { it.id }.toSet()
        entries.forEach { entry ->
            text(entry.title)
            require(Instant.parse(entry.createdAt) <= Instant.parse(entry.modifiedAt))
            entry.expiresOn?.let { LocalDate.parse(it) }
            entry.deletedAt?.let { require(Instant.parse(it) >= Instant.parse(entry.createdAt)) }
            require(entry.customerId == null || entry.customerId in customerIds)
            require(entry.projectId == null || entry.projectId in projectIds)
            val projectCustomer = projectById[entry.projectId]?.customerId
            require(entry.customerId == null || projectCustomer == null || entry.customerId == projectCustomer)
            require(entry.tags.size <= 100 && entry.history.size <= 100)
            entry.tags.forEach { text(it, 256) }
            entry.notes.useChars { require(it.size <= MAX_FIELD_CHARS) }
            validateData(entry.data, entryIds, serverIds)
            entry.history.forEach {
                require(Instant.parse(it.changedAt) in Instant.parse(entry.createdAt)..Instant.parse(entry.modifiedAt))
                validateData(it.data, entryIds, serverIds, checkReferences = false)
            }
        }
    }

    /**
     * Returns the same records as a separate vault: a new random ID, like a newly created vault, and revision zero.
     * Restored and exported copies use it so their backups are never managed, rotated or checked as the original's.
     * The result shares all objects with this vault, so the caller keeps ownership.
     */
    fun independentCopy(): Vault = copy(id = UUID.randomUUID().toString(), revision = 0)

    /**
     * Returns a copy without the given trashed entries and their history. Remaining SSH server and
     * registrar-login references to them are cleared. Secrets owned only by removed entries are erased
     * immediately; the result shares all remaining objects with this vault, so the caller keeps ownership.
     */
    fun purgeEntries(ids: Set<String>, now: Instant = Instant.now()): Vault {
        require(ids.isNotEmpty())
        val byId = entries.associateBy { it.id }
        require(ids.all { byId[it]?.deletedAt != null }) { "Only trashed entries can be purged" }
        val (removed, kept) = entries.partition { it.id in ids }
        val remaining = kept.map { entry ->
            val data = when (val current = entry.data) {
                is EntryData.Ssh -> if (current.serverIds.any { it in ids })
                    current.copy(serverIds = current.serverIds.filterNot { it in ids }) else current
                is EntryData.Domain -> if (current.registrarLoginId?.let { it in ids } == true)
                    current.copy(registrarLoginId = null) else current
                else -> current
            }
            if (data === entry.data) entry
            else entry.copy(data = data, modifiedAt = maxOf(now, Instant.parse(entry.modifiedAt)).toString())
        }
        val retained = Collections.newSetFromMap(IdentityHashMap<Secret, Boolean>())
        remaining.forEach { retained.addAll(secrets(it)) }
        removed.forEach { entry -> secrets(entry).filterNot { it in retained }.forEach(Secret::close) }
        return copy(entries = remaining)
    }

    /**
     * Returns a caller-owned copy in which every [Secret] is a new instance, filled through [Secret.copy] from a
     * temporary character array that is erased afterwards. No immutable string of a secret value is created, unlike a
     * serialization round trip. Non-secret values are immutable and shared. If copying fails, secrets copied so far
     * are erased.
     */
    internal fun independentCopy(): Vault {
        val copied = mutableListOf<Secret>()
        fun secret(value: Secret): Secret = value.copy().also(copied::add)
        fun field(value: Field): Field = value.copy(value = secret(value.value))
        fun endpoint(value: MailEndpoint?): MailEndpoint? = value?.copy(host = field(value.host))
        fun data(value: EntryData): EntryData = when (value) {
            is EntryData.Web -> value.copy(url = field(value.url), username = field(value.username),
                password = field(value.password), totp = value.totp?.let(::field))
            is EntryData.Transfer -> value.copy(host = field(value.host), username = field(value.username),
                password = field(value.password), directory = field(value.directory))
            is EntryData.Email -> value.copy(address = field(value.address), username = field(value.username),
                password = field(value.password), imap = endpoint(value.imap), pop3 = endpoint(value.pop3),
                smtp = endpoint(value.smtp))
            is EntryData.Panel -> value.copy(url = field(value.url), username = field(value.username),
                password = field(value.password), role = field(value.role))
            is EntryData.Server -> value.copy(host = field(value.host), username = field(value.username),
                password = field(value.password), operatingSystem = field(value.operatingSystem), role = field(value.role))
            is EntryData.Ssh -> value.copy(privateKey = field(value.privateKey), publicKey = field(value.publicKey),
                passphrase = field(value.passphrase), fingerprint = field(value.fingerprint))
            is EntryData.Domain -> value.copy(name = field(value.name), registrar = field(value.registrar),
                dnsNotes = field(value.dnsNotes))
            is EntryData.Custom -> value.copy(values = value.values.mapValues { field(it.value) })
        }
        return try {
            copy(entries = entries.map { entry ->
                entry.copy(data = data(entry.data), notes = secret(entry.notes),
                    history = entry.history.map { it.copy(data = data(it.data)) })
            })
        } catch (e: Throwable) {
            copied.forEach(Secret::close)
            throw e
        }
    }

    override fun close() {
        entries.forEach { entry -> secrets(entry).forEach(Secret::close) }
    }

    companion object {
        /** Current decrypted document schema; older schemas are only accepted through registered migrations. */
        const val SCHEMA_VERSION = 1
        const val MAX_FIELD_CHARS = 262_144
        private fun secrets(entry: Entry): List<Secret> = listOf(entry.notes) + entry.data.fields().map { it.value } +
            entry.history.flatMap { item -> item.data.fields().map { it.value } }
        private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
        private fun uniqueIds(ids: List<String>): Set<String> {
            ids.forEach { uuid(it) }
            return ids.toSet().also { require(it.size == ids.size) }
        }
        private fun text(value: String, max: Int = 4096) { require(value.length <= max) }
        private fun port(value: Int) { require(value in 1..65535) }
        private fun validateData(data: EntryData, ids: Set<String>, servers: Set<String>, checkReferences: Boolean = true) {
            data.fields().forEach { field -> field.value.useChars { require(it.size <= MAX_FIELD_CHARS) } }
            when (data) {
                is EntryData.Transfer -> port(data.port)
                is EntryData.Server -> port(data.port)
                is EntryData.Email -> listOfNotNull(data.imap, data.pop3, data.smtp).forEach { port(it.port) }
                is EntryData.Ssh -> {
                    require(data.serverIds.size <= 10_000)
                    data.serverIds.forEach { uuid(it); if (checkReferences) require(it in servers) }
                }
                is EntryData.Domain -> data.registrarLoginId?.let { uuid(it); if (checkReferences) require(it in ids) }
                is EntryData.Custom -> { require(data.values.size <= 100); data.values.keys.forEach { text(it, 256) } }
                else -> Unit
            }
        }
    }
}
