// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.generator.PasswordGenerator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PassphraseWordListTest {
    private fun words(count: Int) = List(count) { index ->
        "word" + (0..3).map { position ->
            var value = index
            repeat(position) { value /= 26 }
            ('a'.code + value % 26).toChar()
        }.joinToString("")
    }

    @Test fun `UTF8 BOM blank lines and line endings preserve vocabulary and erase input`() {
        val expected = words(1024).toMutableList().apply { this[0] = "Bäume" }
        val bytes = ("\uFEFF\r\n" + expected.joinToString("\r\n") { " $it " } + "\n").toByteArray()
        assertEquals(expected, parsePassphraseWordList(bytes))
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun `malformed UTF8 is rejected without leaking its content and erased`() {
        val bytes = words(1024).joinToString("\n").toByteArray() + byteArrayOf(0xc0.toByte(), 0xaf.toByte())
        val failure = assertThrows(InvalidWordListException::class.java) { parsePassphraseWordList(bytes) }
        assertNull(failure.cause)
        assertFalse(failure.message!!.contains("wordaaaa"))
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun `duplicates invalid characters lengths and word counts fail closed`() {
        val valid = words(1024)
        val invalid = listOf(valid + valid.first(), valid.drop(1), words(65537),
            valid.drop(1) + "one-two", valid.drop(1) + "digit1", valid.drop(1) + "two words",
            valid.drop(1) + "a", valid.drop(1) + "a".repeat(33), valid.drop(1) + "word\uFEFF")
        for (list in invalid) {
            val bytes = list.joinToString("\n").toByteArray()
            assertThrows(InvalidWordListException::class.java) { parsePassphraseWordList(bytes) }
            assertTrue(bytes.all { it == 0.toByte() })
        }
    }

    @Test fun `byte bound is enforced before decoding and oversized input erased`() {
        val bytes = ByteArray(MAX_WORD_LIST_BYTES + 1) { 'a'.code.toByte() }
        assertThrows(InvalidWordListException::class.java) { parsePassphraseWordList(bytes) }
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test fun `minimum word count matches core entropy gate and both separators`() {
        val generator = PasswordGenerator()
        for (size in listOf(1024, 4095, 4096, 65536)) {
            val list = parsePassphraseWordList(words(size).joinToString("\n").toByteArray())
            val minimum = minimumPassphraseWords(size)
            assertEquals(if (size < 4096) 6 else 5, minimum)
            assertThrows(IllegalArgumentException::class.java) { generator.passphrase(list, minimum - 1) }
            for (separator in listOf('-', ' ')) {
                generator.passphrase(list, minimum, separator).use { secret ->
                    secret.useChars {
                        val chosen = String(it).split(separator)
                        assertEquals(minimum, chosen.size)
                        assertTrue(chosen.all(list.toSet()::contains))
                    }
                }
            }
        }
    }

    @Test fun `generated secrets close on insertion cancellation disposal and failure`() {
        val success = Secret("synthetic".toCharArray())
        deliverGeneratedSecret(success, { true }) { assertEquals("synthetic", it) }
        assertThrows(IllegalStateException::class.java) { success.useChars {} }
        val cancelled = Secret("synthetic".toCharArray())
        deliverGeneratedSecret(cancelled, { false }) { fail("Disposed editor must not receive a secret") }
        assertThrows(IllegalStateException::class.java) { cancelled.useChars {} }
        val failed = Secret("synthetic".toCharArray())
        assertThrows(IllegalStateException::class.java) {
            deliverGeneratedSecret(failed, { true }) { error("Insertion failed") }
        }
        assertThrows(IllegalStateException::class.java) { failed.useChars {} }
    }
}
