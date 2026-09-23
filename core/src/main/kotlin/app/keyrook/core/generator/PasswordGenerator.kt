// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.generator

import app.keyrook.core.crypto.Secret
import java.security.SecureRandom
import kotlin.math.log2

data class PasswordOptions(
    val length: Int = 24,
    val lowercase: Boolean = true,
    val uppercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
)

/** Uses rejection sampling so every password satisfying the selected classes is equally likely. */
class PasswordGenerator(private val random: SecureRandom = SecureRandom()) {
    fun generate(options: PasswordOptions = PasswordOptions()): Secret {
        require(options.length in 12..256) { "Password length must be between 12 and 256" }
        val classes = buildList {
            if (options.lowercase) add("abcdefghijklmnopqrstuvwxyz")
            if (options.uppercase) add("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            if (options.digits) add("0123456789")
            if (options.symbols) add("!@#$%^&*()-_=+[]{}:,.?")
        }
        require(classes.isNotEmpty()) { "Select at least one character class" }
        val alphabet = classes.joinToString("")
        val result = CharArray(options.length)
        try {
            do {
                for (index in result.indices) result[index] = alphabet[random.nextInt(alphabet.length)]
            } while (classes.any { group -> result.none { it in group } })
            return Secret(result)
        } finally { result.fill('\u0000') }
    }

    /** Words are public vocabulary, not secrets. The caller supplies a reviewed natural-language list. */
    fun passphrase(words: List<String>, wordCount: Int = 6, separator: Char = '-'): Secret {
        require(words.size in 1024..65536 && words.toSet().size == words.size) { "Use 1024 to 65536 distinct words" }
        require(separator == '-' || separator == ' ') { "Use a hyphen or space separator" }
        require(words.all { word -> word.length in 2..32 && word.all { it.isLetter() } }) { "Words must contain only letters" }
        require(wordCount in 5..20 && wordCount * log2(words.size.toDouble()) >= 60.0) { "Passphrase must provide at least 60 bits of entropy" }
        val chosen = IntArray(wordCount) { random.nextInt(words.size) }
        val result = CharArray(chosen.sumOf { words[it].length } + wordCount - 1)
        try {
            var offset = 0
            chosen.forEachIndexed { index, word ->
                if (index > 0) result[offset++] = separator
                words[word].forEach { result[offset++] = it }
            }
            return Secret(result)
        } finally { result.fill('\u0000'); chosen.fill(0) }
    }
}
