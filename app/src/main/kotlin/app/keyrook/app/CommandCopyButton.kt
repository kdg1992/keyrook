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
            UiText.text("command.copied", protocol)
        else UiText.text("command.failed")
    }) { Text(UiText.text("command.copy", protocol)) }
    if (message.isNotEmpty()) Text(message)
}
