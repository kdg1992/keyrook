// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import java.util.HexFormat
import java.util.UUID

internal val testKdf = KdfParameters(iterations = 1)
internal fun credentials(password: String = "test-only master 🗝 ä", key: ByteArray? = null): Credentials =
    Secret(password.toCharArray()).use { Credentials(it, key) }
internal fun field(value: String, hidden: Boolean = true) = Field(Secret(value.toCharArray()), hidden)
internal fun id(): String = UUID.randomUUID().toString()
internal const val DATE = "2026-09-23T12:00:00Z"

internal fun sampleVault(): Vault {
    val customer = Customer(id(), "Customer-SENTINEL-4531", contactName = "Contact-SENTINEL-2291",
        contactEmail = "contact@example.invalid", phone = "+49 30 1234567", website = "https://customer.example.invalid",
        notes = Secret("Customer-Notes-SENTINEL-6612".toCharArray()))
    val project = Project(id(), "Project-SENTINEL-8421", customer.id, description = "Description-SENTINEL-1182",
        notes = Secret("Project-Notes-SENTINEL-7741".toCharArray()))
    val serverId = id()
    val webId = id()
    val data = listOf(
        EntryData.Web(field("https://example.invalid/login", false), field("User-SENTINEL-3412"),
            field("Password-SENTINEL-7751"), field("JBSWY3DPEHPK3PXP")),
        EntryData.Transfer(field("files.example.invalid"), 22, TransferProtocol.SFTP, field("transfer"), field("transfer-secret"), field("/")),
        EntryData.Email(field("mail@example.invalid"), field("mail"), field("mail-secret"),
            imap = MailEndpoint(field("imap.example.invalid"), 993, MailEncryption.TLS),
            smtp = MailEndpoint(field("smtp.example.invalid"), 587, MailEncryption.STARTTLS)),
        EntryData.Panel(field("https://panel.example.invalid"), field("panel"), field("panel-secret"), field("admin")),
        EntryData.Server(field("server.example.invalid"), 22, field("root"), field("server-secret"), field("Linux"), field("root")),
        EntryData.Ssh(SshKeyType.ED25519, field("Private-Key-SENTINEL-5531"), field("public-key"), field("key-passphrase"),
            field("fingerprint"), listOf(serverId)),
        EntryData.Domain(field("example.invalid"), field("registrar"), field("DNS notes"), webId),
        EntryData.Custom(mapOf("custom" to field("Custom-SENTINEL-3951", false))),
    )
    val entries = data.mapIndexed { index, value ->
        Entry(if (index == 0) webId else if (index == 4) serverId else id(), "Title-SENTINEL-$index", value, DATE, DATE,
            customer.id, project.id, listOf("Tag-SENTINEL-9891"), Secret("Notes-SENTINEL-7123".toCharArray()),
            expiresOn = "2027-01-01", deletedAt = if (index == 7) DATE else null,
            history = if (index == 0) listOf(HistoryItem(DATE, EntryData.Custom(mapOf("password" to field("old-password"))))) else emptyList(),
            pinned = index == 1)
    }
    val template = EntryTemplate.of(id(), "Template-SENTINEL-3310", entries[0])
    return Vault(customers = listOf(customer), projects = listOf(project), entries = entries, templates = listOf(template))
}

/** Fixed format regression vector: sequential salt/nonce, one Argon2 iteration, JDK AES-GCM. Password `fixture-password`. */
internal fun frozenV1Fixture(): ByteArray = HexFormat.of().parseHex(
    "4b4559524f4f4b000001004c0000010100000013000100000000000100000004" +
    "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b" +
    "e833639db333bb90708d90bf2938a8b1f8fe233c792159ea8410173a7edec06c34a5eb216675ec684d9f80e1f5fad0979741aa81cb8981d9ca8c9d657fb8a016c2cf5083616eb74f1000ff9eac3ab4ac10e5eb222b3d4738158133c76d50fe0fef503e825d1578599772e55a0c66e01bfb9dd642eb1f05b932f81728aeca814aefe2867f84a3")
