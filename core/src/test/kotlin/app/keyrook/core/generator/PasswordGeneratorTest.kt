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
