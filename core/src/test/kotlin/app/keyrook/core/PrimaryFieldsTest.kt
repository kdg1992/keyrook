// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import app.keyrook.core.security.HealthIssue
import app.keyrook.core.security.VaultHealth
import app.keyrook.core.security.passwordSecrets
import app.keyrook.core.transfer.VaultTransfer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The user name, password and address of typed and imported custom entries. */
class PrimaryFieldsTest {
    private val transfer = VaultTransfer()
    private fun Field?.text(): String? = this?.value?.useChars { String(it) }

    private val bitwarden = """{"encrypted":false,"items":[{"type":1,"name":"Shop","login":{"username":"shop-user",
        "password":"shop-password","totp":"JBSWY3DPEHPK3PXP","uris":[{"uri":"https://shop.invalid"}]},
        "fields":[{"name":"PIN","value":"1234","type":1}]}]}""".toByteArray()

    private val keePass = """<KeePassFile><Root><Group><Entry>
        <String><Key>Title</Key><Value>Mail</Value></String>
        <String><Key>UserName</Key><Value>mail-user</Value></String>
        <String><Key>Password</Key><Value>mail-password</Value></String>
        <String><Key>URL</Key><Value>https://mail.invalid</Value></String>
        <String><Key>Recovery</Key><Value>recovery-code</Value></String>
        <Times><LastModificationTime>2026-03-01T00:00:00Z</LastModificationTime></Times>
        <History><Entry><String><Key>UserName</Key><Value>old-user</Value></String>
        <String><Key>Password</Key><Value>old-password</Value></String>
        <Times><LastModificationTime>2026-02-01T00:00:00Z</LastModificationTime></Times></Entry></History>
        </Entry></Group></Root></KeePassFile>""".toByteArray()

    @Test fun `Bitwarden entries offer the password, not the user name, and show the user name`() {
        transfer.importBitwarden(bitwarden).use { vault ->
            val data = vault.entries.single().data as EntryData.Custom
            assertFalse(data.values.getValue("username").hidden)
            assertTrue(data.values.getValue("password").hidden)
            assertTrue(data.values.getValue("totp").hidden)
            assertEquals("shop-password", data.primarySecret().text())
            assertEquals("shop-user", data.primaryUsername().text())
            assertEquals("https://shop.invalid", data.primaryUrl().text())
        }
    }

    @Test fun `KeePass entries show the user name in current data and history and offer the password`() {
        transfer.importKeePassXml(keePass).use { vault ->
            val entry = vault.entries.single()
            val data = entry.data as EntryData.Custom
            assertFalse(data.values.getValue("UserName").hidden)
            assertFalse(data.values.getValue("URL").hidden)
            assertTrue(data.values.getValue("Password").hidden)
            assertTrue(data.values.getValue("Recovery").hidden)
            assertFalse((entry.history.single().data as EntryData.Custom).values.getValue("UserName").hidden)
            assertEquals("mail-password", data.primarySecret().text())
            assertEquals("mail-user", data.primaryUsername().text())
            assertEquals("https://mail.invalid", data.primaryUrl().text())
        }
    }

    @Test fun `entries imported before user names were visible still offer the password`() {
        // Earlier imports stored the user name hidden and before the password.
        val data = EntryData.Custom(linkedMapOf("username" to field("user"), "password" to field("secret"),
            "url1" to field("https://a.invalid", false)))
        assertEquals("secret", data.primarySecret().text())
        assertEquals("user", data.primaryUsername().text())
        assertEquals("https://a.invalid", data.primaryUrl().text())
        // Without a password label the first other hidden field is the secret; a hidden user name never is.
        assertEquals("1234", EntryData.Custom(linkedMapOf("User" to field("user"), "PIN" to field("1234"))).primarySecret().text())
        assertNull(EntryData.Custom(linkedMapOf("Benutzername" to field("user"), "totp" to field("JBSWY3DP"))).primarySecret())
        // Labels match case-insensitively and prefer a filled field; kind URL is the address fallback.
        val labelled = EntryData.Custom(linkedMapOf("Passwort" to field(""), "PASSWORD" to field("filled"),
            "Website" to Field(Secret("https://w.invalid".toCharArray()), false, FieldKind.URL)))
        assertEquals("filled", labelled.primarySecret().text())
        assertEquals("https://w.invalid", labelled.primaryUrl().text())
        assertNull(labelled.primaryUsername())
    }

    @Test fun `typed entries use their fixed fields`() {
        sampleVault().use { vault ->
            val web = vault.entries[0].data as EntryData.Web
            assertSame(web.password, web.primarySecret())
            assertSame(web.username, web.primaryUsername())
            assertSame(web.url, web.primaryUrl())
            val ssh = vault.entries[5].data as EntryData.Ssh
            assertSame(ssh.passphrase, ssh.primarySecret())
            assertNull(ssh.primaryUsername())
            assertNull(vault.entries[6].data.primarySecret())
            assertNull(vault.entries[1].data.primaryUrl())
        }
    }

    @Test fun `health and breach check read the labelled passwords of imported entries`() {
        transfer.importBitwarden(bitwarden).use { vault ->
            val data = vault.entries.single().data
            assertEquals(listOf("shop-password"), passwordSecrets(data).map { secret -> secret.useChars { String(it) } })
            // Two imports of the same login share address and user name: a possible duplicate with a reused password.
            val twice = vault.copy(entries = vault.entries + vault.entries.single().copy(id = id()))
            val issues = VaultHealth().inspect(twice).flatMap { it.issues }.toSet()
            assertTrue(HealthIssue.DUPLICATE_ENTRY in issues)
            assertTrue(HealthIssue.REUSED_PASSWORD in issues)
        }
    }
}
