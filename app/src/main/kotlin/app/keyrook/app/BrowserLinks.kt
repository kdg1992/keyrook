// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import java.net.URI
import java.util.Locale

/** Validate without DNS or network access before handing an explicit user action to the browser. */
internal object BrowserLinks {
    fun parse(value: String): URI {
        require(value.length in 1..8192 && value.none { it.isISOControl() || it == '\\' }) {
            "Invalid browser address"
        }
        val uri = URI(value)
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.isOpaque)
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) {
            "Browser address must have a host and no embedded credentials"
        }
        require(uri.port == -1 || uri.port in 1..65535)
        // Encoded controls can be interpreted differently by browser handlers and servers.
        require(!Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE).containsMatchIn(value))
        return uri
    }
}
