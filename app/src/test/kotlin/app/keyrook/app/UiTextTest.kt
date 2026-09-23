// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.EntryData
import app.keyrook.core.model.Field
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.Properties

class UiTextTest {
    @Test fun `German is deterministic default and English uses explicit locale`() {
        assertEquals("Speichern", UiText.text("common.save"))
        assertEquals("Save", UiText.localized(Locale.ENGLISH, "common.save"))
        assertEquals("Speichern", UiText.localized(Locale.FRENCH, "common.save"))
        assertEquals("Verlauf (3)", UiText.text("editor.history", 3))
    }

    @Test fun `English bundle translates every key with matching placeholders`() {
        fun bundle(name: String) = Properties().apply {
            UiTextTest::class.java.getResourceAsStream("/app/keyrook/app/$name.properties")!!.reader(Charsets.UTF_8).use(::load)
        }
        val german = bundle("messages")
        val english = bundle("messages_en")
        assertEquals(german.stringPropertyNames(), english.stringPropertyNames())
        german.stringPropertyNames().forEach { key ->
            assertNotNull(english.getProperty(key), key)
            assertEquals(Regex("%s").findAll(german.getProperty(key)).count(), Regex("%s").findAll(english.getProperty(key)).count(), key)
        }
    }

    @Test fun `translated field labels preserve custom names and do not control generation`() {
        EntryType.entries.forEach { type ->
            val data = blankData(type)
            try {
                assertEquals(data.fields().size, data.labels(Locale.ENGLISH).size)
                assertTrue(type.label(Locale.ENGLISH).isNotBlank())
            } finally { data.fields().forEach { it.value.close() } }
        }
        Secret("synthetic".toCharArray()).use { secret ->
            val custom = EntryData.Custom(mapOf("Persönlicher Feldname" to Field(secret)))
            assertEquals(listOf("Persönlicher Feldname"), custom.labels(Locale.ENGLISH))
            assertTrue(custom.canGenerateSecret(0))
            assertFalse(custom.canGenerateSecret(1))
        }
    }
}
