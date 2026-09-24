// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import app.keyrook.core.settings.ScreenArea
import app.keyrook.core.settings.WindowGeometry
import app.keyrook.core.settings.WindowSettingsDocument
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import kotlin.math.roundToInt

/** Window changes are written once the geometry has been unchanged this long, so dragging does not write repeatedly. */
internal const val WINDOW_SAVE_DELAY_MILLIS = 750L

/** A new minimum-size value for the main window in dp, matching the smallest geometry the settings accept. */
internal fun minimumWindowSize() = Dimension(WindowGeometry.MIN_WIDTH, WindowGeometry.MIN_HEIGHT)

/**
 * Usable bounds (without task bars or docks) of every connected screen, the default screen first.
 * Empty when headless or when the window system cannot report its screens.
 */
internal fun connectedScreens(): List<ScreenArea> = try {
    if (GraphicsEnvironment.isHeadless()) emptyList() else {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val primary = environment.defaultScreenDevice
        (listOf(primary) + environment.screenDevices.filter { it != primary }).mapNotNull { device ->
            val configuration = device.defaultConfiguration
            val bounds = configuration.bounds
            val insets = try { Toolkit.getDefaultToolkit().getScreenInsets(configuration) } catch (_: Exception) { null }
            val width = bounds.width - (insets?.let { it.left + it.right } ?: 0)
            val height = bounds.height - (insets?.let { it.top + it.bottom } ?: 0)
            if (width > 0 && height > 0) ScreenArea(bounds.x + (insets?.left ?: 0), bounds.y + (insets?.top ?: 0), width, height)
            else null
        }
    }
} catch (_: Exception) { emptyList() }

/** The saved geometry, or the default size, fitted to [screens]: off-screen positions are centered, oversized windows reduced. */
internal fun initialWindowGeometry(saved: WindowGeometry?, screens: List<ScreenArea>): WindowGeometry =
    (saved ?: WindowGeometry.DEFAULT).fitTo(screens)

/**
 * The geometry to remember for [state]. Position and size are taken only from a floating window; while maximized only the
 * flag changes and the previous floating bounds stay, and minimized or fullscreen windows keep [previous] unchanged.
 */
internal fun windowGeometryOf(state: WindowState, previous: WindowGeometry?): WindowGeometry? {
    if (state.isMinimized) return previous
    return when (state.placement) {
        WindowPlacement.Maximized -> (previous ?: WindowGeometry.DEFAULT).copy(maximized = true)
        WindowPlacement.Fullscreen -> previous
        WindowPlacement.Floating -> {
            val size = state.size
            if (!size.width.isSpecified || !size.height.isSpecified) return previous
            val position = state.position
            val placed = position.isSpecified && position.x.isSpecified && position.y.isSpecified
            WindowGeometry.fromDocument(WindowSettingsDocument(
                x = if (placed) position.x.value.roundToInt() else previous?.x,
                y = if (placed) position.y.value.roundToInt() else previous?.y,
                width = size.width.value.roundToInt(), height = size.height.value.roundToInt(), maximized = false,
            ))
        }
    }
}

/** Saves the current geometry; a failed write is ignored because the geometry is only a convenience. */
internal fun saveWindowGeometry(settings: SettingsStore, state: WindowState) {
    val geometry = windowGeometryOf(state, settings.current().window) ?: return
    settings.update { it.copy(window = geometry) }
}

/** Creates the main window state from the saved geometry, validated against the currently connected screens. */
@Composable
internal fun rememberMainWindowState(settings: SettingsStore): WindowState {
    val geometry = remember { initialWindowGeometry(settings.current().window, connectedScreens()) }
    val x = geometry.x
    val y = geometry.y
    return rememberWindowState(
        placement = if (geometry.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = if (x != null && y != null) WindowPosition(x.dp, y.dp) else WindowPosition(Alignment.Center),
        size = DpSize(geometry.width.dp, geometry.height.dp),
    )
}

/** Writes geometry changes once they have settled for [WINDOW_SAVE_DELAY_MILLIS]; closing the window saves immediately. */
@Composable
internal fun PersistWindowGeometry(state: WindowState, settings: SettingsStore) {
    LaunchedEffect(state) {
        snapshotFlow { windowGeometryOf(state, settings.current().window) }.collectLatest { geometry ->
            if (geometry == null || geometry == settings.current().window) return@collectLatest
            delay(WINDOW_SAVE_DELAY_MILLIS)
            settings.update { it.copy(window = geometry) }
        }
    }
}
