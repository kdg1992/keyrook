// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.generator

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PasswordGeneratorTest {
    private val generator = PasswordGenerator()

    @Test fun `passwords meet every selected class and do not repeat`() {
        val values = HashSet<String>()
        repeat(100) {
            generator.generate().use { secret -> secret.useChars { chars ->
                assertEquals(24, chars.size)
                assertTrue(chars.any { it.isLowerCase() })
                assertTrue(chars.any { it.isUpperCase() })
                assertTrue(chars.any { it.isDigit() })
                assertTrue(chars.any { !it.isLetterOrDigit() })
                assertTrue(values.add(String(chars)))
            } }
        }
    }

    @Test fun `disabled character classes never occur`() {
        generator.generate(PasswordOptions(32, false, false, true, false)).use { secret ->
            secret.useChars { assertTrue(it.all(Char::isDigit)); assertEquals(32, it.size) }
        }
    }

    @Test fun `unsafe or unbounded settings are rejected`() {
        listOf(0, 11, 257, Int.MAX_VALUE).forEach { length ->
            assertThrows(IllegalArgumentException::class.java) { generator.generate(PasswordOptions(length)) }
        }
        assertThrows(IllegalArgumentException::class.java) { generator.generate(PasswordOptions(24, false, false, false, false)) }
    }

    private fun samples(options: PasswordOptions, count: Int = 500): List<String> = List(count) {
        generator.generate(options).use { secret -> secret.useChars { String(it) } }
    }

    @Test fun `shell safe preset never produces characters that need quoting`() {
        val forbidden = "\"'`\\$!&;|<>(){}[]*?~#%=+/:@,^ "
        val values = samples(PasswordOptions(preset = PasswordPreset.SHELL_SAFE))
        values.forEach { value ->
            assertEquals(24, value.length)
            assertTrue(value.none { it in forbidden }, value)
            assertTrue(value.all { it.isLetterOrDigit() || it in "-_." }, value)
            assertTrue(value.any { it.isLowerCase() } && value.any { it.isUpperCase() } && value.any { it.isDigit() }, value)
            assertTrue(value.any { it in "-_." }, value)
        }
        assertEquals("-_.".toSet(), values.flatMap { it.filterNot(Char::isLetterOrDigit).toList() }.toSet())
    }

    @Test fun `ambiguous characters never occur while every class stays represented`() {
        PasswordPreset.entries.forEach { preset ->
            val options = PasswordOptions(length = 16, preset = preset, excludeAmbiguous = true)
            val values = samples(options)
            values.forEach { value ->
                assertTrue(value.none { it in PasswordGenerator.AMBIGUOUS }, value)
                options.characterClasses().forEach { group -> assertTrue(value.any { it in group }, value) }
            }
            val seen = values.joinToString("").toSet()
            assertEquals(options.characterClasses().joinToString("").toSet(), seen, preset.name)
            assertTrue("0Oo1lI|5S2Z8B".none { it in seen })
            assertTrue("3479acxyzAXY".all { it in seen })
        }
        generator.generate(PasswordOptions(12, false, false, true, false, excludeAmbiguous = true)).use { secret ->
            secret.useChars { chars -> assertTrue(chars.all { it in "34679" }) }
        }
    }

    @Test fun `standard preset keeps the full symbol set and default length`() {
        val symbols = samples(PasswordOptions(), 2000).flatMap { it.filterNot(Char::isLetterOrDigit).toList() }.toSet()
        assertEquals(PasswordGenerator.STANDARD_SYMBOLS.toSet(), symbols)
        assertEquals(PasswordPreset.STANDARD, PasswordOptions().preset)
        assertFalse(PasswordOptions().excludeAmbiguous)
    }

    @Test fun `length limits follow the preset`() {
        assertEquals(16, PasswordPreset.MAX_16.maxLength)
        (12..16).forEach { length ->
            generator.generate(PasswordOptions(length, preset = PasswordPreset.MAX_16)).use { secret ->
                secret.useChars { assertEquals(length, it.size) }
            }
        }
        generator.generate(PasswordOptions(256, preset = PasswordPreset.SHELL_SAFE)).use { secret ->
            secret.useChars { assertEquals(256, it.size) }
        }
        listOf(11, 17, 24, 256).forEach { length ->
            assertThrows(IllegalArgumentException::class.java) { generator.generate(PasswordOptions(length, preset = PasswordPreset.MAX_16)) }
        }
        PasswordPreset.entries.forEach { preset ->
            listOf(0, 11, 257).forEach { length ->
                assertThrows(IllegalArgumentException::class.java) { generator.generate(PasswordOptions(length, preset = preset)) }
            }
        }
    }

    @Test fun `impossible configurations are rejected for every preset`() {
        PasswordPreset.entries.forEach { preset ->
            listOf(false, true).forEach { ambiguous ->
                assertThrows(IllegalArgumentException::class.java) {
                    generator.generate(PasswordOptions(16, false, false, false, false, preset, ambiguous))
                }
                assertTrue(PasswordOptions(preset = preset, excludeAmbiguous = ambiguous).characterClasses().all { it.isNotEmpty() })
            }
        }
    }

    private fun vocabulary(): List<String> = (0 until 1024).map { n ->
        "word" + ('a' + (n / 676)) + ('a' + (n / 26 % 26)) + ('a' + (n % 26))
    }

    @Test fun `passphrases use six independent words from supplied vocabulary`() {
        val vocabulary = vocabulary()
        generator.passphrase(vocabulary).use { secret -> secret.useChars {
            val words = String(it).split('-')
            assertEquals(6, words.size)
            assertTrue(words.all { word -> word in vocabulary })
        } }
    }

    @Test fun `passphrase entropy and unique word requirements cannot be bypassed`() {
        assertThrows(IllegalArgumentException::class.java) { generator.passphrase(List(1024) { "same" }) }
        assertThrows(IllegalArgumentException::class.java) { generator.passphrase(vocabulary(), 5) }
        assertThrows(IllegalArgumentException::class.java) { generator.passphrase(vocabulary().take(100)) }
        assertThrows(IllegalArgumentException::class.java) { generator.passphrase(vocabulary(), separator = '\n') }
    }
}
