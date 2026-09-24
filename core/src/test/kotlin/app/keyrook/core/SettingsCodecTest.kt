// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.settings.BackupSettingsDocument
import app.keyrook.core.settings.GeneratorSettingsDocument
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
            window = WindowSettingsDocument(x = -40, y = 25, width = 1200, height = 800, maximized = true),
            generator = GeneratorSettingsDocument(preset = "SHELL_SAFE", length = 20, lowercase = true, uppercase = false, digits = true,
                symbols = true, excludeAmbiguous = true, wordListPath = "/lists/words.txt", wordCount = 7, separator = " "),
            uiScale = 150, contrast = "HIGH")
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
        assertNull(decoded.generator)
        assertNull(decoded.uiScale)
        assertNull(decoded.contrast)
        assertEquals(expected, SettingsCodec.decode(SettingsCodec.encode(expected)))
    }

    @Test fun `settings written before the scale and contrast preferences still decode and round trip`() {
        val written = """{
            "version": 1,
            "theme": "SYSTEM",
            "language": "GERMAN",
            "windowLock": "MINIMIZE",
            "updateCheck": "MANUAL",
            "window": { "x": 10, "y": 20, "width": 1100, "height": 760, "maximized": false },
            "generator": { "preset": "MAX_16", "excludeAmbiguous": true }
        }"""
        val decoded = SettingsCodec.decode(written.toByteArray())!!
        assertNull(decoded.uiScale)
        assertNull(decoded.contrast)
        assertEquals(GeneratorSettingsDocument(preset = "MAX_16", excludeAmbiguous = true), decoded.generator)
        val updated = decoded.copy(uiScale = 130, contrast = "HIGH")
        val text = String(SettingsCodec.encode(updated), Charsets.UTF_8)
        assertTrue(text.contains("\"uiScale\": 130") && text.contains("\"contrast\": \"HIGH\""), text)
        assertTrue(text.contains("\"preset\": \"MAX_16\""), text)
        assertEquals(updated, SettingsCodec.decode(SettingsCodec.encode(updated)))
        assertEquals(SettingsDocument(uiScale = 90), SettingsCodec.decode("{\"uiScale\":90}".toByteArray()))
        assertEquals(SettingsDocument(), SettingsCodec.decode("{\"uiScale\":null,\"contrast\":null}".toByteArray()))
    }

    @Test fun `partial window entries decode with missing fields left empty`() {
        assertEquals(SettingsDocument(window = WindowSettingsDocument()), SettingsCodec.decode("{\"window\":{}}".toByteArray()))
        assertEquals(SettingsDocument(window = WindowSettingsDocument(width = 900, maximized = false)),
            SettingsCodec.decode("{\"window\":{\"width\":900,\"maximized\":false}}".toByteArray()))
        assertEquals(SettingsDocument(), SettingsCodec.decode("{\"window\":null}".toByteArray()))
    }

    @Test fun `partial generator entries decode with missing fields left empty`() {
        assertEquals(SettingsDocument(generator = GeneratorSettingsDocument()), SettingsCodec.decode("{\"generator\":{}}".toByteArray()))
        assertEquals(SettingsDocument(generator = GeneratorSettingsDocument(preset = "MAX_16", excludeAmbiguous = true)),
            SettingsCodec.decode("{\"generator\":{\"preset\":\"MAX_16\",\"excludeAmbiguous\":true}}".toByteArray()))
        assertEquals(SettingsDocument(), SettingsCodec.decode("{\"generator\":null}".toByteArray()))
    }

    @Test fun `corrupt unknown and oversized input is rejected without exceptions`() {
        listOf("", "{", "null", "[]", "{\"version\":2}", "{\"version\":0}", "{\"theme\":1}", "{\"language\":1}", "{\"windowLock\":1}", "{\"updateCheck\":true}",
            "{\"window\":1}", "{\"generator\":1}", "{\"generator\":{\"length\":\"long\"}}", "{\"generator\":{\"words\":[]}}",
            "{\"window\":{\"width\":\"wide\"}}", "{\"window\":{\"depth\":3}}",
            "{\"uiScale\":\"large\"}", "{\"uiScale\":1.5}", "{\"contrast\":true}",
            "{\"unexpected\":true}", "{\"lastKeyFilePath\":\"/keys/a.key\"}", "{\"backups\":{\"/a\":{\"folder\":\"/b\"}}}").forEach {
            assertNull(SettingsCodec.decode(it.toByteArray()), it)
        }
        assertNull(SettingsCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)))
        assertNull(SettingsCodec.decode(ByteArray(SettingsCodec.MAX_BYTES + 1) { ' '.code.toByte() }))
    }
}
