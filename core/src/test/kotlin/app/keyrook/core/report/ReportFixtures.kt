// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.report

import app.keyrook.core.DATE
import app.keyrook.core.crypto.Secret
import app.keyrook.core.id
import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.assertFalse

internal val reportKeys = ReportText { it }

/** Values that must never appear in a report, whatever their field's hidden flag. */
internal val SECRET_SENTINELS = listOf("Password-SENTINEL", "Private-Key-SENTINEL", "Passphrase-SENTINEL",
    "Totp-SENTINEL", "Notes-SENTINEL", "Custom-SENTINEL", "Dns-SENTINEL", "History-SENTINEL", "Hidden-SENTINEL",
    "JBSWY3DPEHPK3PXP", "transfer-secret", "mail-secret", "panel-secret", "server-secret", "key-passphrase",
    "old-password", "DNS notes")

internal fun assertNoSentinel(text: String) = SECRET_SENTINELS.forEach { assertFalse(text.contains(it), "$it in $text") }

/**
 * Closes every hidden secret and all notes, including those of customers and projects, so a report that reads one
 * fails instead of passing silently.
 */
internal fun closeHiddenSecrets(vault: Vault) {
    vault.customers.forEach { it.notes.close() }
    vault.projects.forEach { it.notes.close() }
    vault.entries.forEach { entry ->
        entry.notes.close()
        (entry.data.fields() + entry.history.flatMap { it.data.fields() }).filter { it.hidden }.forEach { it.value.close() }
    }
}

internal val SENTINEL_CUSTOMER = Customer("4f7b8c1e-2d3a-4b5c-9d6e-7f8091a2b3c4", "Kunde & <Co> \"Test\"",
    contactName = "Erika <Kontakt>", contactEmail = "kontakt@kunde.invalid", phone = "+49 30 555", website = "https://kunde.invalid")

/**
 * Every entry type twice for [SENTINEL_CUSTOMER]: once with all secret fields wrongly marked visible, once with every
 * non-secret field hidden. Secrets, notes, history and custom values carry sentinels that no report may contain.
 */
internal fun sentinelVault(): Vault {
    fun f(value: String, hidden: Boolean) = Field(Secret(value.toCharArray()), hidden)
    val serverIds = listOf(id(), id())
    fun data(hide: Boolean, suffix: String): List<Pair<String?, EntryData>> {
        fun plain(value: String) = f(if (hide) "Hidden-SENTINEL-$value" else "$value$suffix", hide)
        fun secret(value: String) = f("$value-SENTINEL$suffix", false)
        return listOf(
            null to EntryData.Web(plain("https://web.invalid/"), plain("web-user"), secret("Password"), secret("Totp")),
            null to EntryData.Transfer(plain("ftp.invalid"), 2121, TransferProtocol.FTPS, plain("ftp-user"), secret("Password"),
                plain("/srv/www")),
            null to EntryData.Email(plain("info@mail.invalid"), plain("mail-user"), secret("Password"),
                imap = MailEndpoint(plain("imap.invalid"), 993, MailEncryption.TLS),
                pop3 = MailEndpoint(plain("pop.invalid"), 995, MailEncryption.TLS),
                smtp = MailEndpoint(plain("smtp.invalid"), 587, MailEncryption.STARTTLS)),
            null to EntryData.Panel(plain("https://panel.invalid/"), plain("panel-user"), secret("Password"), plain("reseller")),
            serverIds[if (hide) 1 else 0] to EntryData.Server(plain("srv.invalid"), 2222, plain("root-user"), secret("Password"),
                plain("Debian"), plain("web")),
            null to EntryData.Ssh(SshKeyType.ED25519, secret("Private-Key"), plain("ssh-ed25519 AAAA"), secret("Passphrase"),
                plain("SHA256:fingerprint"), listOf(serverIds[if (hide) 1 else 0])),
            null to EntryData.Domain(plain("example.invalid"), plain("Registrar GmbH"), secret("Dns")),
            null to EntryData.Custom(mapOf("password" to secret("Custom"), "note" to f("Custom-SENTINEL-plain$suffix", false),
                "hidden" to f("Hidden-SENTINEL-custom", true))),
        )
    }
    val entries = (data(false, "") + data(true, "")).mapIndexed { index, (fixedId, value) ->
        Entry(fixedId ?: id(), "Titel $index <b>", value, DATE, DATE, SENTINEL_CUSTOMER.id, tags = listOf("tag,$index"),
            notes = Secret("Notes-SENTINEL-$index".toCharArray()), expiresOn = "2027-0${index % 9 + 1}-15",
            history = listOf(HistoryItem(DATE, EntryData.Web(f("History-SENTINEL-url", false),
                f("History-SENTINEL-user", false), f("History-SENTINEL-password", false)))))
    }
    // Fresh notes per vault: closing one vault must not erase the notes of the shared customer constant.
    val customer = SENTINEL_CUSTOMER.copy(notes = Secret("Customer-Notes-SENTINEL".toCharArray()))
    val project = Project(id(), "Projekt <P>", customer.id, description = "Beschreibung <D>",
        notes = Secret("Project-Notes-SENTINEL".toCharArray()))
    return Vault(customers = listOf(customer), projects = listOf(project), entries = entries).also { it.validate() }
}
