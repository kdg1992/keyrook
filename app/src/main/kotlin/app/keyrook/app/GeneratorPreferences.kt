// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.generator.PasswordGenerator
import app.keyrook.core.generator.PasswordOptions
import app.keyrook.core.generator.PasswordPreset
import app.keyrook.core.settings.GeneratorSettingsDocument
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal val PASSPHRASE_WORD_COUNTS = 5..20

/**
 * Last password generator choices. Non-secret: only the preset, options and the path of a user-chosen word list are kept.
 * The word list itself is read and validated again from [wordListPath] each time it is used.
 */
internal data class GeneratorPreferences(
    val preset: PasswordPreset = PasswordPreset.STANDARD,
    val length: Int = DEFAULT_LENGTH,
    val lowercase: Boolean = true,
    val uppercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val excludeAmbiguous: Boolean = false,
    val wordListPath: Path? = null,
    val wordCount: Int = 6,
    val separator: Char = '-',
) {
    fun passwordOptions() = PasswordOptions(length, lowercase, uppercase, digits, symbols, preset, excludeAmbiguous)

    /** Keeps the successfully used password options and leaves the passphrase choices untouched. */
    fun withPasswordOptions(options: PasswordOptions) = copy(preset = options.preset, length = options.length,
        lowercase = options.lowercase, uppercase = options.uppercase, digits = options.digits, symbols = options.symbols,
        excludeAmbiguous = options.excludeAmbiguous)

    fun toDocument() = GeneratorSettingsDocument(
        preset = preset.name, length = length, lowercase = lowercase, uppercase = uppercase, digits = digits, symbols = symbols,
        excludeAmbiguous = excludeAmbiguous, wordListPath = wordListPath?.toString(), wordCount = wordCount,
        separator = separator.toString(),
    )

    companion object {
        const val DEFAULT_LENGTH = 24

        /** Suggested length when switching to [preset]: the current one if the preset allows it, otherwise the preset's maximum. */
        fun lengthFor(preset: PasswordPreset, current: Int?): Int =
            current?.takeIf { it in PasswordGenerator.MIN_LENGTH..preset.maxLength } ?: minOf(DEFAULT_LENGTH, preset.maxLength)

        /**
         * Every value is checked against the UI's choices; anything else falls back to its default. [wordListPath] was already
         * checked like other stored paths. A configuration without any character class falls back to all classes.
         */
        fun fromDocument(document: GeneratorSettingsDocument?, wordListPath: Path?): GeneratorPreferences {
            val defaults = GeneratorPreferences()
            if (document == null) return defaults.copy(wordListPath = wordListPath)
            val preset = PasswordPreset.entries.find { it.name == document.preset } ?: defaults.preset
            val classes = listOf(document.lowercase, document.uppercase, document.digits, document.symbols)
                .map { it ?: true }.takeIf { selected -> selected.any { it } } ?: listOf(true, true, true, true)
            return GeneratorPreferences(
                preset = preset,
                length = lengthFor(preset, document.length),
                lowercase = classes[0], uppercase = classes[1], digits = classes[2], symbols = classes[3],
                excludeAmbiguous = document.excludeAmbiguous == true,
                wordListPath = wordListPath,
                wordCount = document.wordCount?.takeIf { it in PASSPHRASE_WORD_COUNTS } ?: defaults.wordCount,
                separator = when (document.separator) { "-" -> '-'; " " -> ' '; else -> defaults.separator },
            )
        }
    }
}

/**
 * The remembered word list if it still looks usable: an existing regular file, not a symbolic link, within the size limit.
 * Anything else yields null so the generator quietly offers the normal file selection; its content is checked again when used.
 */
internal fun rememberedWordList(path: Path?): Path? = path?.takeIf {
    try {
        Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && Files.size(it) <= MAX_WORD_LIST_BYTES
    } catch (_: Exception) { false }
}
