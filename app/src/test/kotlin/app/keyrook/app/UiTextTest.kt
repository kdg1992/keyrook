// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.runtime.snapshots.Snapshot
import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.EntryData
import app.keyrook.core.model.Field
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.Properties

class UiTextTest {
    @AfterEach fun restoreGerman() { UiText.select(AppLanguage.GERMAN) }

    @Test fun `German is deterministic default and English uses explicit locale`() {
        UiText.select(AppLanguage.GERMAN)
        assertEquals("Speichern", UiText.text("common.save"))
        assertEquals("Save", UiText.localized(Locale.ENGLISH, "common.save"))
        assertEquals("Speichern", UiText.localized(Locale.FRENCH, "common.save"))
        assertEquals("Verlauf (3)", UiText.text("editor.history", 3))
    }

    @Test fun `selected language switches every text lookup`() {
        UiText.select(AppLanguage.ENGLISH)
        assertEquals(Locale.ENGLISH, UiText.locale)
        assertEquals("Save", UiText.text("common.save"))
        assertEquals("History (3)", UiText.text("editor.history", 3))
        assertEquals("Web login", EntryType.WEB.label)
        assertEquals("Ctrl+", UiText.text("shell.ctrlPrefix"))
        UiText.select(AppLanguage.GERMAN)
        assertEquals(Locale.GERMAN, UiText.locale)
        assertEquals("Speichern", UiText.text("common.save"))
        assertEquals("Web-Login", EntryType.WEB.label)
        assertEquals("Strg+", UiText.text("shell.ctrlPrefix"))
    }

    @Test fun `text lookups read observable state so compositions re-render after a switch`() {
        UiText.select(AppLanguage.ENGLISH)
        var reads = 0
        assertEquals("Save", Snapshot.observe(readObserver = { reads++ }) { UiText.text("common.save") })
        assertTrue(reads > 0)
    }

    @Test fun `system language is German only for German operating system locales`() {
        assertEquals(Locale.GERMAN, AppLanguage.SYSTEM.locale(Locale.GERMANY))
        assertEquals(Locale.GERMAN, AppLanguage.SYSTEM.locale(Locale.forLanguageTag("de-AT")))
        assertEquals(Locale.ENGLISH, AppLanguage.SYSTEM.locale(Locale.US))
        assertEquals(Locale.ENGLISH, AppLanguage.SYSTEM.locale(Locale.FRANCE))
        assertEquals(Locale.ENGLISH, AppLanguage.SYSTEM.locale(Locale.ROOT))
        assertEquals(Locale.GERMAN, AppLanguage.GERMAN.locale(Locale.US))
        assertEquals(Locale.ENGLISH, AppLanguage.ENGLISH.locale(Locale.GERMANY))
        UiText.select(AppLanguage.SYSTEM, Locale.forLanguageTag("de-CH"))
        assertEquals("Speichern", UiText.text("common.save"))
        UiText.select(AppLanguage.SYSTEM, Locale.JAPAN)
        assertEquals("Save", UiText.text("common.save"))
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

    @Test fun `every window lock policy has a label in both languages`() {
        listOf(Locale.GERMAN, Locale.ENGLISH).forEach { locale ->
            assertTrue(UiText.localized(locale, "shell.windowLock").isNotBlank())
            assertEquals(WindowLockPolicy.entries.size,
                WindowLockPolicy.entries.map { UiText.localized(locale, "shell.windowLock.${it.name.lowercase()}") }.toSet().size)
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
