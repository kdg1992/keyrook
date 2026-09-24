// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.generator.PasswordOptions
import app.keyrook.core.generator.PasswordPreset
import app.keyrook.core.settings.GeneratorSettingsDocument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

class GeneratorPreferencesTest {
    @TempDir lateinit var directory: Path

    @Test fun `generator choices and word list path survive a restart`() {
        val root = directory.toRealPath()
        val config = root.resolve("config")
        val list = Files.writeString(root.resolve("words.txt"), "unused")
        val store = SettingsStore(config)
        assertEquals(GeneratorPreferences(), store.current().generator)
        val options = PasswordOptions(14, true, false, true, true, PasswordPreset.MAX_16, excludeAmbiguous = true)
        store.update { it.copy(generator = it.generator.withPasswordOptions(options)) }
        assertTrue(store.flush())
        store.update { it.copy(generator = it.generator.copy(wordListPath = list, wordCount = 8, separator = ' ')) }
        assertTrue(store.flush())
        val reloaded = SettingsStore(config).current().generator
        assertEquals(GeneratorPreferences(PasswordPreset.MAX_16, 14, true, false, true, true, true, list, 8, ' '), reloaded)
        assertEquals(options, reloaded.passwordOptions())
        val text = Files.readString(config.resolve(SettingsStore.FILE_NAME))
        assertTrue(text.contains("\"preset\": \"MAX_16\""))
        assertFalse(text.contains("unused"))
    }

    @Test fun `settings files without generator choices load the built in defaults`() {
        val config = Files.createDirectory(directory.toRealPath().resolve("config"))
        Files.writeString(config.resolve(SettingsStore.FILE_NAME), """{"version":1,"theme":"DARK","window":null}""")
        assertEquals(AppSettings(theme = ThemeMode.DARK), SettingsStore(config).current())
        assertEquals(PasswordOptions(), GeneratorPreferences().passwordOptions())
    }

    @Test fun `unknown or out of range values fall back to defaults`() {
        val defaults = GeneratorPreferences()
        assertEquals(defaults, GeneratorPreferences.fromDocument(null, null))
        assertEquals(defaults, GeneratorPreferences.fromDocument(GeneratorSettingsDocument(), null))
        assertEquals(defaults, GeneratorPreferences.fromDocument(GeneratorSettingsDocument(preset = "WEAK", length = 11,
            wordCount = 4, separator = "_", excludeAmbiguous = null), null))
        assertEquals(defaults, GeneratorPreferences.fromDocument(GeneratorSettingsDocument(length = 257, wordCount = 21, separator = "--"), null))
        assertEquals(defaults, GeneratorPreferences.fromDocument(
            GeneratorSettingsDocument(lowercase = false, uppercase = false, digits = false, symbols = false), null))
        val capped = GeneratorPreferences.fromDocument(GeneratorSettingsDocument(preset = "MAX_16", length = 24), null)
        assertEquals(PasswordPreset.MAX_16, capped.preset)
        assertEquals(16, capped.length)
        assertEquals(16, GeneratorPreferences.lengthFor(PasswordPreset.MAX_16, 40))
        assertEquals(14, GeneratorPreferences.lengthFor(PasswordPreset.MAX_16, 14))
        assertEquals(40, GeneratorPreferences.lengthFor(PasswordPreset.SHELL_SAFE, 40))
        assertEquals(24, GeneratorPreferences.lengthFor(PasswordPreset.STANDARD, null))
        val config = Files.createDirectory(directory.toRealPath().resolve("config"))
        Files.writeString(config.resolve(SettingsStore.FILE_NAME),
            """{"version":1,"generator":{"preset":"SHELL_SAFE","wordListPath":"relative/words.txt","separator":" "}}""")
        val loaded = SettingsStore(config).current().generator
        assertEquals(GeneratorPreferences(preset = PasswordPreset.SHELL_SAFE, separator = ' '), loaded)
        assertNull(loaded.wordListPath)
    }

    @Test fun `a missing or unusable remembered word list is ignored quietly`() {
        val root = directory.toRealPath()
        val list = Files.writeString(root.resolve("words.txt"), "words")
        assertEquals(list, rememberedWordList(list))
        assertNull(rememberedWordList(null))
        assertNull(rememberedWordList(root.resolve("missing.txt")))
        assertNull(rememberedWordList(root))
        val link = root.resolve("link.txt")
        val linked = runCatching { Files.createSymbolicLink(link, list) }.isSuccess
        if (linked) assertNull(rememberedWordList(link))
        val large = root.resolve("large.txt")
        Files.write(large, ByteArray(MAX_WORD_LIST_BYTES + 1))
        assertNull(rememberedWordList(large))
    }

    @Test fun `every preset has a label and help text in both languages`() {
        listOf(Locale.GERMAN, Locale.ENGLISH).forEach { locale ->
            assertEquals(PasswordPreset.entries.size,
                PasswordPreset.entries.map { UiText.localized(locale, "generator.preset.${it.name}") }.toSet().size)
            PasswordPreset.entries.forEach { assertTrue(UiText.localized(locale, "generator.presetHelp.${it.name}").isNotBlank()) }
            assertTrue(UiText.localized(locale, "generator.length", 12, 16).contains("16"))
        }
    }
}
