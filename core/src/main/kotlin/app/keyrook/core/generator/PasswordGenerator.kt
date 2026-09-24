// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.generator

import app.keyrook.core.crypto.Secret
import java.security.SecureRandom
import kotlin.math.log2

/**
 * Named restrictions for systems that reject or misinterpret some passwords. A preset limits the symbol set and the
 * maximum length; the selected character classes and [PasswordOptions.excludeAmbiguous] still apply on top of it.
 */
enum class PasswordPreset(val symbols: String, val maxLength: Int) {
    /** The full symbol set and lengths up to [PasswordGenerator.MAX_LENGTH]. */
    STANDARD(PasswordGenerator.STANDARD_SYMBOLS, PasswordGenerator.MAX_LENGTH),

    /**
     * Only symbols that need no quoting or escaping in POSIX shells, Windows command lines, URLs, FTP/SFTP clients or typical
     * configuration files. Quotes, backslash, `$`, backtick, `!`, `&`, `;`, `|`, `<`, `>`, brackets and braces, `*`, `?`, `~`,
     * `#`, `%`, space, `=`, `+`, `/`, `:`, `@`, `,` and `^` never occur.
     */
    SHELL_SAFE("-_.", PasswordGenerator.MAX_LENGTH),

    /** The full symbol set for legacy panels that silently truncate or reject passwords longer than 16 characters. */
    MAX_16(PasswordGenerator.STANDARD_SYMBOLS, 16),
}

data class PasswordOptions(
    val length: Int = 24,
    val lowercase: Boolean = true,
    val uppercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val preset: PasswordPreset = PasswordPreset.STANDARD,
    /** Leaves out characters that are easily confused when read aloud or typed from paper, see [PasswordGenerator.AMBIGUOUS]. */
    val excludeAmbiguous: Boolean = false,
) {
    /** The character classes this configuration draws from, each already reduced by the preset and ambiguity rules. */
    fun characterClasses(): List<String> = buildList {
        if (lowercase) add("abcdefghijklmnopqrstuvwxyz")
        if (uppercase) add("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        if (digits) add("0123456789")
        if (symbols) add(preset.symbols)
    }.map { group -> if (excludeAmbiguous) group.filterNot { it in PasswordGenerator.AMBIGUOUS } else group }
}

/** Uses rejection sampling so every password satisfying the selected classes is equally likely. */
class PasswordGenerator(private val random: SecureRandom = SecureRandom()) {
    fun generate(options: PasswordOptions = PasswordOptions()): Secret {
        require(options.length in MIN_LENGTH..options.preset.maxLength) {
            "Password length must be between $MIN_LENGTH and ${options.preset.maxLength}"
        }
        val classes = options.characterClasses()
        require(classes.isNotEmpty()) { "Select at least one character class" }
        require(classes.all { it.isNotEmpty() }) { "A selected character class has no characters left" }
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

    companion object {
        const val MIN_LENGTH = 12
        const val MAX_LENGTH = 256
        const val STANDARD_SYMBOLS = "!@#$%^&*()-_=+[]{}:,.?"

        /** Look-alike groups 0/O/o, 1/l/I/|, 5/S, 2/Z and 8/B; every member of a group is left out. */
        const val AMBIGUOUS = "0Oo1lI|5S2Z8B"
    }
}
