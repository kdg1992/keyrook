// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EntryCardsTest {
    private val today = LocalDate.of(2026, 12, 15)
    private fun field(value: String, hidden: Boolean = false, kind: FieldKind = FieldKind.TEXT) =
        Field(Secret(value.toCharArray()), hidden, kind)
    private fun entry(data: EntryData, customer: String? = null, project: String? = null, expires: LocalDate? = null) =
        Entry("id", "Title", data, "2025-01-01T00:00:00Z", "2026-01-01T00:00:00Z", customerId = customer,
            projectId = project, expiresOn = expires?.toString())
    private fun card(data: EntryData, vault: Vault = Vault()) = entryCardInfo(entry(data), vault, today)

    @AfterEach fun restoreGerman() { UiText.select(AppLanguage.GERMAN) }

    @Test fun `cards show visible username and address per entry type and never hidden values`() {
        UiText.select(AppLanguage.ENGLISH)
        val web = card(EntryData.Web(field("https://example.invalid", kind = FieldKind.URL), field("admin"),
            field("synthetic-secret", true), field("synthetic-totp", true)))
        assertEquals(CardValue("Username", "admin"), web.username)
        assertEquals(CardValue("URL", "https://example.invalid"), web.address)
        val transfer = card(EntryData.Transfer(field("files.example.invalid"), 22, TransferProtocol.SFTP, field("deploy"),
            field("synthetic-secret", true), field("/srv")))
        assertEquals(CardValue("Host", "files.example.invalid"), transfer.address)
        assertEquals("deploy", transfer.username?.value)
        val email = card(EntryData.Email(field("mail@example.invalid"), field("mailbox"), field("synthetic-secret", true),
            imap = MailEndpoint(field("imap.example.invalid"), 993, MailEncryption.TLS),
            smtp = MailEndpoint(field("smtp.example.invalid"), 465, MailEncryption.TLS)))
        assertEquals(CardValue("IMAP host", "imap.example.invalid"), email.address)
        assertEquals("mailbox", email.username?.value)
        val panel = card(EntryData.Panel(field("https://panel.example.invalid", kind = FieldKind.URL), field("owner"),
            field("synthetic-secret", true), field("admin")))
        assertEquals("https://panel.example.invalid", panel.address?.value)
        val server = card(EntryData.Server(field("10.0.0.1"), 22, field("root"), field("synthetic-secret", true), field("Linux"), field("db")))
        assertEquals(CardValue("Host", "10.0.0.1"), server.address)
        assertEquals("root", server.username?.value)
        val ssh = card(EntryData.Ssh(SshKeyType.ED25519, field("synthetic-private", true), field("ssh-ed25519 AAAA"),
            field("synthetic-passphrase", true), field("SHA256:abc")))
        assertNull(ssh.username)
        assertNull(ssh.address)
        val domain = card(EntryData.Domain(field("example.invalid"), field("Registrar"), field("notes")))
        assertEquals(CardValue("Domain", "example.invalid"), domain.address)
        assertNull(domain.username)
        val custom = card(EntryData.Custom(linkedMapOf("Secret portal" to field("https://hidden.example.invalid", true, FieldKind.URL),
            "Portal" to field("https://portal.example.invalid", kind = FieldKind.URL), "Token" to field("synthetic-token", true))))
        assertEquals(CardValue("Portal", "https://portal.example.invalid"), custom.address)
        assertNull(custom.username)
        listOf(web, transfer, email, panel, server, ssh, domain, custom).forEach { info ->
            val shown = listOfNotNull(info.username?.value, info.address?.value).joinToString(" ")
            assertFalse("synthetic" in shown, shown)
            assertFalse("hidden" in shown, shown)
        }
    }

    @Test fun `hidden usernames and hosts stay hidden and empty or control values are omitted`() {
        val hidden = card(EntryData.Web(field("https://example.invalid", true, FieldKind.URL), field("private-user", true),
            field("synthetic-secret", true)))
        assertNull(hidden.username)
        assertNull(hidden.address)
        val empty = card(EntryData.Server(field(" "), 22, field(""), field("", true), field(""), field("")))
        assertNull(empty.username)
        assertNull(empty.address)
        val control = card(EntryData.Server(field("host\nsecond"), 22, field("x".repeat(1000)), field("", true), field(""), field("")))
        assertEquals("host second", control.address?.value)
        assertEquals(256, control.username?.value?.length)
        EntryType.entries.forEach { type ->
            val data = blankData(type)
            try {
                val info = card(data)
                assertNull(info.username, type.name)
                assertNull(info.address, type.name)
            } finally { data.fields().forEach { it.value.close() } }
        }
        val closed = EntryData.Web(field("https://example.invalid", kind = FieldKind.URL), field("admin"), field("s", true))
        closed.fields().forEach { it.value.close() }
        assertNull(card(closed).username)
    }

    @Test fun `customer is inherited from the project and names come from the vault`() {
        val vault = Vault(customers = listOf(Customer("c", "ACME")), projects = listOf(Project("p", "Relaunch", "c"), Project("free", "Free")))
        val data = EntryData.Domain(field("example.invalid"), field(""), field(""))
        val inherited = entryCardInfo(entry(data, project = "p"), vault, today)
        assertEquals("ACME", inherited.customer)
        assertEquals("Relaunch", inherited.project)
        val direct = entryCardInfo(entry(data, customer = "c", project = "free"), vault, today)
        assertEquals("ACME", direct.customer)
        assertEquals("Free", direct.project)
        val none = entryCardInfo(entry(data), vault, today)
        assertNull(none.customer)
        assertNull(none.project)
    }

    @Test fun `expiry marker uses the vault health warning window`() {
        assertEquals(ExpiryState.EXPIRED, expiryState(today.minusDays(1), today))
        assertEquals(ExpiryState.EXPIRING_SOON, expiryState(today, today))
        assertEquals(ExpiryState.EXPIRING_SOON, expiryState(today.plusDays(30), today))
        assertEquals(ExpiryState.VALID, expiryState(today.plusDays(31), today))
        val data = EntryData.Domain(field("example.invalid"), field(""), field(""))
        val info = entryCardInfo(entry(data, expires = today.plusDays(3)), Vault(), today)
        assertEquals(today.plusDays(3), info.expiresOn)
        assertEquals(ExpiryState.EXPIRING_SOON, info.expiry)
        val undated = entryCardInfo(entry(data), Vault(), today)
        assertNull(undated.expiresOn)
        assertNull(undated.expiry)
    }
}
