// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.settings.BackupSettingsDocument
import app.keyrook.core.settings.SettingsCodec
import app.keyrook.core.settings.SettingsDocument
import app.keyrook.core.settings.WindowSettingsDocument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SettingsCodecTest {
    @Test fun `settings round trip with an explicit version`() {
        val document = SettingsDocument(theme = "DARK", language = "ENGLISH", inactivityMinutes = 10, clipboardSeconds = 30,
            windowLock = "FOCUS_LOSS", lastVaultPath = "/vaults/a.keyrook",
            backups = mapOf("/vaults/a.keyrook" to BackupSettingsDocument("/backups", 5, 7, true)), updateCheck = "ON_START",
            window = WindowSettingsDocument(x = -40, y = 25, width = 1200, height = 800, maximized = true))
        val bytes = SettingsCodec.encode(document)
        assertTrue(String(bytes, Charsets.UTF_8).contains("\"version\": ${SettingsCodec.VERSION}"))
        assertEquals(document, SettingsCodec.decode(bytes))
        assertEquals(SettingsDocument(), SettingsCodec.decode("{}".toByteArray()))
    }

    @Test fun `settings written before the language preference still decode`() {
        val written = """{
            "version": 1,
            "theme": "DARK",
            "inactivityMinutes": 10,
            "clipboardSeconds": 30,
            "lastVaultPath": "/vaults/a.keyrook",
            "backups": {
                "/vaults/a.keyrook": {
                    "folder": "/backups",
                    "latest": 5,
                    "daily": 7,
                    "enabled": true
                }
            }
        }"""
        val expected = SettingsDocument(theme = "DARK", inactivityMinutes = 10, clipboardSeconds = 30,
            lastVaultPath = "/vaults/a.keyrook",
            backups = mapOf("/vaults/a.keyrook" to BackupSettingsDocument("/backups", 5, 7, true)))
        val decoded = SettingsCodec.decode(written.toByteArray())
        assertEquals(expected, decoded)
        assertNull(decoded!!.language)
        assertNull(decoded.windowLock)
        assertNull(decoded.updateCheck)
        assertNull(decoded.window)
        assertEquals(expected, SettingsCodec.decode(SettingsCodec.encode(expected)))
    }

    @Test fun `partial window entries decode with missing fields left empty`() {
        assertEquals(SettingsDocument(window = WindowSettingsDocument()), SettingsCodec.decode("{\"window\":{}}".toByteArray()))
        assertEquals(SettingsDocument(window = WindowSettingsDocument(width = 900, maximized = false)),
            SettingsCodec.decode("{\"window\":{\"width\":900,\"maximized\":false}}".toByteArray()))
        assertEquals(SettingsDocument(), SettingsCodec.decode("{\"window\":null}".toByteArray()))
    }

    @Test fun `corrupt unknown and oversized input is rejected without exceptions`() {
        listOf("", "{", "null", "[]", "{\"version\":2}", "{\"version\":0}", "{\"theme\":1}", "{\"language\":1}", "{\"windowLock\":1}", "{\"updateCheck\":true}",
            "{\"window\":1}", "{\"window\":{\"width\":\"wide\"}}", "{\"window\":{\"depth\":3}}",
            "{\"unexpected\":true}", "{\"lastKeyFilePath\":\"/keys/a.key\"}", "{\"backups\":{\"/a\":{\"folder\":\"/b\"}}}").forEach {
            assertNull(SettingsCodec.decode(it.toByteArray()), it)
        }
        assertNull(SettingsCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)))
        assertNull(SettingsCodec.decode(ByteArray(SettingsCodec.MAX_BYTES + 1) { ' '.code.toByte() }))
    }
}
