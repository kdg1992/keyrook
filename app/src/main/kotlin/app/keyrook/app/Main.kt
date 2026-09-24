// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.runtime.*
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main(args: Array<String>) {
    if (args.isNotEmpty()) kotlin.system.exitProcess(runRuntimeCheck(args))
    val settings = SettingsStore.platform()
    // Select the language before the first composition so no text is rendered in the wrong language.
    UiText.select(settings.current().language)
    val icon = loadWindowIcon()
    application {
        val windowState = rememberMainWindowState(settings)
        var closeRequested by remember { mutableStateOf(false) }
        Window(
            onCloseRequest = { closeRequested = true },
            state = windowState, title = "Keyrook", icon = icon,
        ) {
            LaunchedEffect(window) { window.minimumSize = minimumWindowSize() }
            PersistWindowGeometry(windowState, settings)
            KeyrookApp(window, settings, closeRequested) { quit ->
                closeRequested = false
                if (quit) { saveWindowGeometry(settings, windowState); exitApplication() }
            }
        }
    }
}
