// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.material.*
import androidx.compose.runtime.*

@Composable
internal fun CommandCopyButton(protocol: String, busy: Boolean, command: () -> String) {
    var message by remember { mutableStateOf("") }
    TextButton(enabled = !busy, onClick = {
        message = if (runCatching { SecretClipboard.copy(command()) }.isSuccess)
            "$protocol-Befehl für PowerShell/sh kopiert. Passwort wird nicht mitkopiert."
        else "Kopieren fehlgeschlagen. Host, Benutzername, Port und Zwischenablage prüfen."
    }) { Text("$protocol-Befehl kopieren (PowerShell/sh)") }
    if (message.isNotEmpty()) Text(message)
}
