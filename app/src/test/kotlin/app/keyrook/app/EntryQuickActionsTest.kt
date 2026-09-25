// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.net.URI
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EntryQuickActionsTest {
    private fun field(value: String, hidden: Boolean = false, kind: FieldKind = FieldKind.TEXT) =
        Field(Secret(value.toCharArray()), hidden, kind)

    @Test fun `imported custom entries copy the password and user name by their labels`() {
        val bitwarden = """{"items":[{"type":1,"name":"Shop","login":{"username":"shop-user","password":"shop-password",
            "uris":[{"uri":"https://shop.invalid"}]}}]}""".toByteArray()
        val keePass = """<KeePassFile><Root><Group><Entry><String><Key>Title</Key><Value>Mail</Value></String>
            <String><Key>UserName</Key><Value>mail-user</Value></String>
            <String><Key>Password</Key><Value>mail-password</Value></String>
            <String><Key>URL</Key><Value>https://mail.invalid</Value></String></Entry></Group></Root></KeePassFile>""".toByteArray()
        // Entries imported by earlier versions kept the user name hidden and before the password.
        val earlier = EntryData.Custom(linkedMapOf("username" to field("old-user", true), "password" to field("old-password", true)))
        val expected = listOf(Triple("shop-user", "shop-password", "https://shop.invalid"),
            Triple("mail-user", "mail-password", "https://mail.invalid"), Triple("old-user", "old-password", null))
        val imported = listOf(
            importTransfer(ImportFormat.BITWARDEN_JSON, bitwarden, selectMapping = { null }, guard = {})!!,
            importTransfer(ImportFormat.KEEPASS_XML, keePass, selectMapping = { null }, guard = {})!!)
        try {
            (imported.map { it.entries.single().data } + earlier).zip(expected).forEach { (data, values) ->
                val copied = mutableListOf<String>()
                listOf(QuickField.USERNAME, QuickField.SECRET).forEach { kind ->
                    EntryQuickActions.copy(data.quickField(kind)!!) { copied += it }
                }
                assertEquals(listOf(values.first, values.second), copied)
                assertEquals(values.third, data.quickField(QuickField.URL)?.value?.useChars { String(it) })
            }
        } finally { imported.forEach { it.close() }; earlier.fields().forEach { it.value.close() } }
    }

    @Test fun `quick fields follow field semantics for every type`() {
        EntryType.entries.forEach { type ->
            val data = blankData(type)
            try {
                data.quickField(QuickField.SECRET)?.let { assertTrue(it.hidden, type.name) }
                data.quickField(QuickField.URL)?.let { assertEquals(FieldKind.URL, it.kind, type.name) }
                QuickField.entries.forEach { kind ->
                    data.quickField(kind)?.let { selected -> assertTrue(data.fields().any { it === selected }, type.name) }
                }
            } finally { data.fields().forEach { it.value.close() } }
        }
        val web = EntryData.Web(field("https://example.invalid", kind = FieldKind.URL), field("sample"), field("synthetic-secret", true))
        assertSame(web.username, web.quickField(QuickField.USERNAME))
        assertSame(web.password, web.quickField(QuickField.SECRET))
        assertSame(web.url, web.quickField(QuickField.URL))
        assertEquals(UiText.text("field.password"), web.quickLabel(web.password))
        val ssh = blankData(EntryType.SSH) as EntryData.Ssh
        assertSame(ssh.passphrase, ssh.quickField(QuickField.SECRET))
        assertNull(ssh.quickField(QuickField.USERNAME))
        val custom = EntryData.Custom(linkedMapOf("Note" to field("visible"), "Token" to field("private", true),
            "Portal" to field("https://portal.example.invalid", kind = FieldKind.URL)))
        assertEquals("Token", custom.quickLabel(custom.quickField(QuickField.SECRET)!!))
        assertEquals("Portal", custom.quickLabel(custom.quickField(QuickField.URL)!!))
        val domain = blankData(EntryType.DOMAIN)
        assertNull(domain.quickField(QuickField.SECRET))
        assertNull(domain.quickField(QuickField.USERNAME))
        assertFalse(EntryQuickActions.available(ssh.passphrase))
        listOf(web, ssh, custom, domain).forEach { data -> data.fields().forEach { it.value.close() } }
        assertFalse(EntryQuickActions.available(web.password))
    }

    @Test fun `quick copy goes through the owned expiring clipboard and keeps the stored secret`() {
        val clipboard = Clipboard("synthetic-only")
        val password = field("synthetic-secret", true)
        ClipboardGuard(clipboard).use { guard ->
            EntryQuickActions.copy(password, guard::copy)
            val contents = clipboard.getContents(null)!!
            assertEquals("synthetic-secret", contents.getTransferData(DataFlavor.stringFlavor))
            assertTrue(contents.transferDataFlavors.any { it != DataFlavor.stringFlavor }, "ownership marker")
            guard.clear()
            assertEquals("", clipboard.getContents(null)!!.getTransferData(DataFlavor.stringFlavor))
            assertTrue((contents.getTransferData(DataFlavor.stringFlavor) as String).all { it == '\u0000' })
        }
        assertEquals("synthetic-secret", password.value.useChars { String(it) })
        password.value.close()
        assertThrows(IllegalStateException::class.java) { EntryQuickActions.copy(password) { fail("closed secret copied") } }
    }

    @Test fun `quick open validates the address before handing it to the browser`() {
        val opened = mutableListOf<URI>()
        field("https://example.invalid/login", kind = FieldKind.URL).let { url ->
            EntryQuickActions.open(url) { opened += it }
            url.value.close()
        }
        assertEquals(listOf(URI("https://example.invalid/login")), opened)
        listOf("javascript:alert(1)", "https://user:secret@example.invalid", "file:///etc/passwd", "").forEach { address ->
            val url = field(address, kind = FieldKind.URL)
            assertThrows(Exception::class.java, { EntryQuickActions.open(url) { fail("browser reached: $address") } }, address)
            url.value.close()
        }
    }
}
