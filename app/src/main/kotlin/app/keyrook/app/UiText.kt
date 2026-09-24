// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.ResourceBundle

/** SYSTEM uses German for a German operating-system locale and English for every other one. */
internal enum class AppLanguage {
    SYSTEM, GERMAN, ENGLISH;

    fun locale(system: Locale = Locale.getDefault(Locale.Category.DISPLAY)): Locale = when (this) {
        SYSTEM -> if (system.language == Locale.GERMAN.language) Locale.GERMAN else Locale.ENGLISH
        GERMAN -> Locale.GERMAN
        ENGLISH -> Locale.ENGLISH
    }
}

/**
 * German is the product default until a language is selected; bundle keys remain independent of persisted field names.
 * The selected locale is Compose state, so every composition reading [text] re-renders when the language changes.
 */
internal object UiText {
    private var selected by mutableStateOf<Locale>(Locale.GERMAN)

    val locale: Locale get() = selected

    fun select(language: AppLanguage, system: Locale = Locale.getDefault(Locale.Category.DISPLAY)) {
        selected = language.locale(system)
    }

    fun text(key: String, vararg arguments: Any): String = localized(selected, key, *arguments)

    fun localized(locale: Locale, key: String, vararg arguments: Any): String {
        val supported = if (locale.language == "en") Locale.ENGLISH else Locale.ROOT
        val bundle = ResourceBundle.getBundle("app.keyrook.app.messages", supported,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES))
        return String.format(locale, bundle.getString(key), *arguments)
    }
}
