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
    // Settings are written in the background; any other way the process ends still writes the latest ones.
    Runtime.getRuntime().addShutdownHook(Thread({ settings.flush() }, "settings-flush"))
    val icon = loadWindowIcon()
    application {
        val windowState = rememberMainWindowState(settings)
        var closeRequested by remember { mutableStateOf(false) }
        Window(
            onCloseRequest = { closeRequested = true },
            state = windowState, title = "Keyrook", icon = icon,
        ) {
            PersistWindowGeometry(windowState, settings)
            KeyrookApp(window, settings, closeRequested) { quit ->
                closeRequested = false
                if (quit) { saveWindowGeometry(settings, windowState); settings.flush(); exitApplication() }
            }
        }
    }
}
