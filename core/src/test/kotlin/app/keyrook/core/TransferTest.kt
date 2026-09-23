// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.model.EntryData
import app.keyrook.core.transfer.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TransferTest {
    private val transfer = VaultTransfer()
    private val consent = PlaintextConsent(true, true)

    @Test fun `plaintext export requires both confirmations`() {
        assertThrows(IllegalArgumentException::class.java) { PlaintextConsent(false, true) }
        assertThrows(IllegalArgumentException::class.java) { PlaintextConsent(true, false) }
    }

    @Test fun `JSON and CSV roundtrips preserve every type and history`() {
        sampleVault().use { original ->
            val json = transfer.exportJson(original, consent)
            val csv = transfer.exportCsv(original, consent)
            try {
                transfer.importJson(json).use { decoded ->
                    assertArrayEquals(json, transfer.exportJson(decoded, consent))
                }
                transfer.importCsv(csv).use { decoded ->
                    assertArrayEquals(json, transfer.exportJson(decoded, consent))
                    assertEquals(8, decoded.entries.size)
                    assertEquals(1, decoded.entries[0].history.size)
                }
            } finally { json.fill(0); csv.fill(0) }
        }
    }

    @Test fun `generic CSV handles quoted delimiters newlines and selected columns`() {
        val bytes = "name,site,user,secret,notes\r\n\"Test, account\",https://example.invalid,u,\"p\"\"w\",\"line1\nline2\"\r\n".toByteArray()
        transfer.importCsv(bytes, CsvMapping("name", "site", "user", "secret", "notes")).use { vault ->
            assertEquals("Test, account", vault.entries.single().title)
            val web = vault.entries.single().data as EntryData.Web
            web.password.value.useChars { assertEquals("p\"w", String(it)) }
            vault.entries.single().notes.useChars { assertEquals("line1\nline2", String(it)) }
        }
    }

    @Test fun `invalid imports give no secret-bearing diagnostic`() {
        for (content in listOf("\"unterminated secret-SENTINEL", "name,name\na,b", "name\n\"a\"bad")) {
            val failure = assertThrows(InvalidImportException::class.java) {
                transfer.importCsv(content.toByteArray(), CsvMapping("name"))
            }
            assertFalse(failure.message!!.contains("SENTINEL"))
            assertNull(failure.cause)
        }
        assertThrows(InvalidImportException::class.java) {
            transfer.importJson("{\"id\":\"one\",\"id\":\"two\"}".toByteArray())
        }
        assertThrows(InvalidImportException::class.java) {
            transfer.importCsv(byteArrayOf(0xc0.toByte(), 0xaf.toByte()))
        }
    }

    @Test fun `Bitwarden imports multiple URIs and custom hidden fields`() {
        val bytes = """{"encrypted":false,"items":[{"type":1,"name":"test","notes":"memo",
            "login":{"username":"user","password":"secret","totp":"totp", "uris":[{"uri":"https://a.invalid"},{"uri":"https://b.invalid"}]},
            "fields":[{"name":"extra","value":"private","type":1}]}]}""".toByteArray()
        transfer.importBitwarden(bytes).use { vault ->
            val data = vault.entries.single().data as EntryData.Custom
            assertEquals(6, data.values.size)
            assertFalse(data.values.getValue("url1").hidden)
            assertTrue(data.values.getValue("extra").hidden)
        }
        assertThrows(InvalidImportException::class.java) {
            transfer.importBitwarden("""{"items":[{"type":3,"name":"card"}]}""".toByteArray())
        }
    }

    @Test fun `KeePass XML accepts strings but rejects entities and attachments`() {
        val xml = """<KeePassFile><Root><Group><Entry><String><Key>Title</Key><Value>test</Value></String>
            <String><Key>Password</Key><Value ProtectInMemory="True">test-only</Value></String></Entry></Group></Root></KeePassFile>"""
        transfer.importKeePassXml(xml.toByteArray()).use { vault ->
            assertEquals("test", vault.entries.single().title)
            (vault.entries.single().data as EntryData.Custom).values.getValue("Password").value.useChars {
                assertEquals("test-only", String(it))
            }
        }
        val xxe = """<!DOCTYPE KeePassFile [<!ENTITY secret SYSTEM "file:///never-read">]><KeePassFile>&secret;</KeePassFile>"""
        assertThrows(InvalidImportException::class.java) { transfer.importKeePassXml(xxe.toByteArray()) }
        assertThrows(InvalidImportException::class.java) {
            transfer.importKeePassXml(xml.replace("</Entry>", "<Binary/></Entry>").toByteArray())
        }
    }

    @Test fun `Bitwarden preserves folders dates trash favorite and password history`() {
        val bytes = """{"folders":[{"id":"old-folder","name":"Accounts"}],"items":[{
            "type":1,"name":"Account","folderId":"old-folder","favorite":true,
            "creationDate":"2026-01-01T00:00:00Z","revisionDate":"2026-03-01T00:00:00Z",
            "deletedDate":"2026-03-02T00:00:00Z","login":{"password":"current"},
            "passwordHistory":[{"password":"old","lastUsedDate":"2026-02-01T00:00:00Z"}]}]}""".toByteArray()
        transfer.importBitwarden(bytes).use { vault ->
            val entry = vault.entries.single()
            assertEquals("Accounts", vault.projects.single().name)
            assertEquals(vault.projects.single().id, entry.projectId)
            assertEquals("2026-01-01T00:00:00Z", entry.createdAt)
            assertEquals("2026-03-01T00:00:00Z", entry.modifiedAt)
            assertEquals("2026-03-02T00:00:00Z", entry.deletedAt)
            assertEquals(listOf("favorite"), entry.tags)
            val history = entry.history.single()
            assertEquals("2026-02-01T00:00:00Z", history.changedAt)
            (history.data as EntryData.Custom).values.getValue("password").value.useChars {
                assertEquals("old", String(it))
            }
        }
    }

    @Test fun `Bitwarden rejects lost folder references attachments and password reprompt`() {
        for (extra in listOf("\"folderId\":\"missing\"", "\"attachments\":[{}]", "\"reprompt\":1")) {
            val bytes = """{"items":[{"type":1,"name":"Account",$extra}]}""".toByteArray()
            assertThrows(InvalidImportException::class.java) { transfer.importBitwarden(bytes) }
        }
    }

    @Test fun `KeePass preserves group path dates expiry tags and historical strings`() {
        val xml = """<KeePassFile><Root><Group><Name>Root</Name><Group><Name>Accounts</Name><Entry>
            <String><Key>Title</Key><Value>Current title</Value></String>
            <String><Key>Password</Key><Value>current</Value></String><Tags>one;two</Tags>
            <Times><CreationTime>2026-01-01T00:00:00Z</CreationTime><LastModificationTime>2026-03-01T00:00:00Z</LastModificationTime>
            <Expires>True</Expires><ExpiryTime>2027-01-01T00:00:00Z</ExpiryTime></Times>
            <History><Entry><String><Key>Title</Key><Value>Old title</Value></String>
            <String><Key>Notes</Key><Value>Old note</Value></String>
            <String><Key>Password</Key><Value>old</Value></String>
            <Times><LastModificationTime>2026-02-01T00:00:00Z</LastModificationTime></Times></Entry></History>
            </Entry></Group></Group></Root></KeePassFile>""".toByteArray()
        transfer.importKeePassXml(xml).use { vault ->
            val entry = vault.entries.single()
            assertEquals("Root / Accounts", vault.projects.single().name)
            assertEquals(vault.projects.single().id, entry.projectId)
            assertEquals("2026-01-01T00:00:00Z", entry.createdAt)
            assertEquals("2026-03-01T00:00:00Z", entry.modifiedAt)
            assertEquals("2027-01-01", entry.expiresOn)
            assertEquals(listOf("one", "two"), entry.tags)
            val old = entry.history.single().data as EntryData.Custom
            old.values.getValue("Password").value.useChars { assertEquals("old", String(it)) }
            old.values.getValue("Title").value.useChars { assertEquals("Old title", String(it)) }
            old.values.getValue("Notes").value.useChars { assertEquals("Old note", String(it)) }
        }
    }

    @Test fun `KeePass refuses protected values duplicate strings and excessive history`() {
        val base = """<KeePassFile><Root><Group><Entry>%s</Entry></Group></Root></KeePassFile>"""
        for (content in listOf(
            """<String><Key>Password</Key><Value Protected="True">ciphertext</Value></String>""",
            """<String><Key>Title</Key><Value>a</Value></String><String><Key>Title</Key><Value>b</Value></String>""",
            "<History>" + "<Entry/>".repeat(101) + "</History>",
        )) {
            assertThrows(InvalidImportException::class.java) { transfer.importKeePassXml(base.format(content).toByteArray()) }
        }
    }

    @Test fun `KeePass recycle bin and nested groups remain in trash`() {
        val entry = """<Entry><String><Key>Title</Key><Value>%s</Value></String>
            <Times><CreationTime>2026-01-01T00:00:00Z</CreationTime>
            <LastModificationTime>2026-02-01T00:00:00Z</LastModificationTime></Times></Entry>"""
        val xml = """<KeePassFile><Meta><RecycleBinUUID>AQEBAQEBAQEBAQEBAQEBAQ==</RecycleBinUUID></Meta>
            <Root><Group><Name>Root</Name>${entry.format("Active")}
            <Group><Name>Recycle Bin</Name><UUID>AQEBAQEBAQEBAQEBAQEBAQ==</UUID>${entry.format("Deleted")}
            <Group><Name>Nested</Name>${entry.format("Deleted nested")}</Group></Group></Group></Root></KeePassFile>"""
        transfer.importKeePassXml(xml.toByteArray()).use { vault ->
            assertEquals(3, vault.entries.size)
            assertNull(vault.entries.single { it.title == "Active" }.deletedAt)
            vault.entries.filter { it.title.startsWith("Deleted") }.forEach {
                assertEquals("2026-02-01T00:00:00Z", it.deletedAt)
            }
        }
    }

    @Test fun `KeePass all-zero or omitted recycle bin does not mark active groups deleted`() {
        for (meta in listOf("", "<Meta><RecycleBinUUID>AAAAAAAAAAAAAAAAAAAAAA==</RecycleBinUUID></Meta>")) {
            val xml = """<KeePassFile>$meta<Root><Group><Name>Recycle Bin</Name>
                <UUID>AQEBAQEBAQEBAQEBAQEBAQ==</UUID><Entry><String><Key>Title</Key><Value>Active</Value></String>
                </Entry></Group></Root></KeePassFile>"""
            transfer.importKeePassXml(xml.toByteArray()).use { assertNull(it.entries.single().deletedAt) }
        }
    }

    @Test fun `KeePass ambiguous recycle metadata is rejected rather than resurrected`() {
        val id = "<RecycleBinUUID>AQEBAQEBAQEBAQEBAQEBAQ==</RecycleBinUUID>"
        val group = """<Group><UUID>AQEBAQEBAQEBAQEBAQEBAQ==</UUID><Entry>
            <String><Key>Title</Key><Value>Deleted</Value></String></Entry></Group>"""
        for (xml in listOf(
            "<KeePassFile><Meta>$id$id</Meta><Root>$group</Root></KeePassFile>",
            "<KeePassFile><Meta>$id</Meta><Root>$group$group</Root></KeePassFile>",
            "<KeePassFile><Meta><RecycleBinUUID>not-an-id</RecycleBinUUID></Meta><Root>$group</Root></KeePassFile>",
        )) assertThrows(InvalidImportException::class.java) { transfer.importKeePassXml(xml.toByteArray()) }
    }

    @Test fun `KeePass malformed or duplicate expiry flags cannot disable expiry silently`() {
        for (flags in listOf("<Expires>True</Expires><Expires>False</Expires>", "<Expires>invalid</Expires>")) {
            val xml = """<KeePassFile><Root><Group><Entry><String><Key>Title</Key><Value>Account</Value></String>
                <Times>$flags<ExpiryTime>2027-01-01T00:00:00Z</ExpiryTime></Times></Entry></Group></Root></KeePassFile>"""
            assertThrows(InvalidImportException::class.java) { transfer.importKeePassXml(xml.toByteArray()) }
        }
    }
}
