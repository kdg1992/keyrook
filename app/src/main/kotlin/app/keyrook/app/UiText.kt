// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import java.util.Locale
import java.util.ResourceBundle

/** German is the product default; bundle keys remain independent of persisted field names. */
internal object UiText {
    fun text(key: String, vararg arguments: Any): String = localized(Locale.GERMAN, key, *arguments)

    fun localized(locale: Locale, key: String, vararg arguments: Any): String {
        val supported = if (locale.language == "en") Locale.ENGLISH else Locale.ROOT
        val bundle = ResourceBundle.getBundle("app.keyrook.app.messages", supported,
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES))
        return String.format(locale, bundle.getString(key), *arguments)
    }
}
