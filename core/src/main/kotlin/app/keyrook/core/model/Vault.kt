// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.model

import app.keyrook.core.crypto.Secret
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import java.time.Instant
import java.time.LocalDate
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

    override fun close() {
        entries.forEach { entry ->
            entry.notes.close()
            entry.data.fields().forEach { it.value.close() }
            entry.history.forEach { item -> item.data.fields().forEach { it.value.close() } }
        }
    }

    companion object {
        /** Current decrypted document schema; older schemas are only accepted through registered migrations. */
        const val SCHEMA_VERSION = 1
        const val MAX_FIELD_CHARS = 262_144
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
