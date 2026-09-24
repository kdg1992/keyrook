// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.model

import app.keyrook.core.crypto.Secret
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Entry types a template can preset; serialized with the same names as the `data.type` discriminator. */
@Serializable
enum class TemplateType {
    @SerialName("web") WEB, @SerialName("transfer") TRANSFER, @SerialName("email") EMAIL,
    @SerialName("panel") PANEL, @SerialName("server") SERVER, @SerialName("ssh") SSH,
    @SerialName("domain") DOMAIN, @SerialName("custom") CUSTOM;

    /** Stable field names of this type in display order; null for [CUSTOM], whose names are free labels. */
    val fieldNames: List<String>? get() = when (this) {
        WEB -> listOf("url", "username", "password", "totp")
        TRANSFER -> listOf("host", "username", "password", "directory")
        EMAIL -> listOf("address", "username", "password", "imap", "pop3", "smtp")
        PANEL -> listOf("url", "username", "password", "role")
        SERVER -> listOf("host", "username", "password", "operatingSystem", "role")
        SSH -> listOf("privateKey", "publicKey", "passphrase", "fingerprint")
        DOMAIN -> listOf("name", "registrar", "dnsNotes")
        CUSTOM -> null
    }

    /** Fields a template of this type may leave out: the TOTP secret and the mail endpoints. */
    val optionalFields: Set<String> get() = when (this) {
        WEB -> setOf("totp")
        EMAIL -> setOf("imap", "pop3", "smtp")
        else -> emptySet()
    }

    companion object {
        fun of(data: EntryData): TemplateType = when (data) {
            is EntryData.Web -> WEB
            is EntryData.Transfer -> TRANSFER
            is EntryData.Email -> EMAIL
            is EntryData.Panel -> PANEL
            is EntryData.Server -> SERVER
            is EntryData.Ssh -> SSH
            is EntryData.Domain -> DOMAIN
            is EntryData.Custom -> CUSTOM
        }
    }
}

/** The layout of one templated field: its stable name (or custom label), display visibility and kind. No value. */
@Serializable
data class TemplateField(val name: String, val hidden: Boolean = true, val kind: FieldKind = FieldKind.TEXT)

/**
 * A preset for new entries (schema 2): entry [type], field layout, default [tags] and an optional customer and
 * project. A template never contains a field value, notes, ports or any other secret; [newData] creates empty fields.
 */
@Serializable
data class EntryTemplate(
    val id: String,
    val name: String,
    val type: TemplateType,
    val fields: List<TemplateField> = emptyList(),
    val tags: List<String> = emptyList(),
    val customerId: String? = null,
    val projectId: String? = null,
) {
    /** Checks everything but whether the customer and project exist, which [Vault.validate] resolves. */
    internal fun validate() {
        Vault.uuid(id)
        require(name.isNotBlank()) { "Template name required" }
        Vault.text(name)
        require(fields.size <= 100 && tags.size <= 100)
        val names = fields.map { it.name }
        require(names.toSet().size == names.size) { "Duplicate template field" }
        names.forEach { Vault.text(it, 256) }
        tags.forEach { Vault.text(it, 256); require(!ReservedTags.isReserved(it)) }
        type.fieldNames?.let { known ->
            require(names.all { it in known }) { "Unknown template field" }
            require(known.filterNot { it in type.optionalFields }.all { it in names }) { "Missing template field" }
        }
        customerId?.let(Vault::uuid)
        projectId?.let(Vault::uuid)
    }

    /**
     * New, empty entry data with this layout; the caller owns the created secrets. Ports, protocols and key types
     * take the same defaults as a blank entry: 22, SFTP, Ed25519 and the standard TLS mail ports.
     */
    fun newData(): EntryData {
        val byName = fields.associateBy { it.name }
        val created = mutableListOf<Secret>()
        fun field(name: String): Field {
            val layout = byName[name] ?: TemplateField(name)
            return Field(Secret(charArrayOf()).also(created::add), layout.hidden, layout.kind)
        }
        fun optional(name: String): Field? = if (name in byName) field(name) else null
        fun endpoint(name: String, port: Int): MailEndpoint? =
            optional(name)?.let { MailEndpoint(it, port, MailEncryption.TLS) }
        return try {
            when (type) {
                TemplateType.WEB -> EntryData.Web(field("url"), field("username"), field("password"), optional("totp"))
                TemplateType.TRANSFER -> EntryData.Transfer(field("host"), 22, TransferProtocol.SFTP, field("username"),
                    field("password"), field("directory"))
                TemplateType.EMAIL -> EntryData.Email(field("address"), field("username"), field("password"),
                    endpoint("imap", 993), endpoint("pop3", 995), endpoint("smtp", 465))
                TemplateType.PANEL -> EntryData.Panel(field("url"), field("username"), field("password"), field("role"))
                TemplateType.SERVER -> EntryData.Server(field("host"), 22, field("username"), field("password"),
                    field("operatingSystem"), field("role"))
                TemplateType.SSH -> EntryData.Ssh(SshKeyType.ED25519, field("privateKey"), field("publicKey"),
                    field("passphrase"), field("fingerprint"))
                TemplateType.DOMAIN -> EntryData.Domain(field("name"), field("registrar"), field("dnsNotes"))
                TemplateType.CUSTOM -> EntryData.Custom(fields.associate { it.name to field(it.name) })
            }
        } catch (failure: Throwable) {
            created.forEach(Secret::close)
            throw failure
        }
    }

    companion object {
        /**
         * The layout of [entry] as a template named [name]: its type, field names with visibility and kind, its tags
         * and its customer and project. Field values, notes, ports and references to other entries are not copied.
         */
        fun of(id: String, name: String, entry: Entry): EntryTemplate {
            val type = TemplateType.of(entry.data)
            val names = when (val data = entry.data) {
                is EntryData.Custom -> data.values.keys.toList()
                is EntryData.Web -> listOfNotNull("url", "username", "password", data.totp?.let { "totp" })
                is EntryData.Email -> listOfNotNull("address", "username", "password",
                    data.imap?.let { "imap" }, data.pop3?.let { "pop3" }, data.smtp?.let { "smtp" })
                else -> requireNotNull(type.fieldNames)
            }
            val layout = names.zip(entry.data.fields()) { field, value -> TemplateField(field, value.hidden, value.kind) }
            return EntryTemplate(id, name.trim(), type, layout, entry.tags.filterNot(ReservedTags::isReserved),
                entry.customerId, entry.projectId)
        }
    }
}
