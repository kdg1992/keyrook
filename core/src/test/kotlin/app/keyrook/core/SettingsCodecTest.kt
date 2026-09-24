// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.settings.BackupSettingsDocument
import app.keyrook.core.settings.SettingsCodec
import app.keyrook.core.settings.SettingsDocument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SettingsCodecTest {
    @Test fun `settings round trip with an explicit version`() {
        val document = SettingsDocument(theme = "DARK", inactivityMinutes = 10, clipboardSeconds = 30,
            lastVaultPath = "/vaults/a.keyrook",
            backups = mapOf("/vaults/a.keyrook" to BackupSettingsDocument("/backups", 5, 7, true)))
        val bytes = SettingsCodec.encode(document)
        assertTrue(String(bytes, Charsets.UTF_8).contains("\"version\": ${SettingsCodec.VERSION}"))
        assertEquals(document, SettingsCodec.decode(bytes))
        assertEquals(SettingsDocument(), SettingsCodec.decode("{}".toByteArray()))
    }

    @Test fun `corrupt unknown and oversized input is rejected without exceptions`() {
        listOf("", "{", "null", "[]", "{\"version\":2}", "{\"version\":0}", "{\"theme\":1}",
            "{\"unexpected\":true}", "{\"lastKeyFilePath\":\"/keys/a.key\"}", "{\"backups\":{\"/a\":{\"folder\":\"/b\"}}}").forEach {
            assertNull(SettingsCodec.decode(it.toByteArray()), it)
        }
        assertNull(SettingsCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)))
        assertNull(SettingsCodec.decode(ByteArray(SettingsCodec.MAX_BYTES + 1) { ' '.code.toByte() }))
    }
}
