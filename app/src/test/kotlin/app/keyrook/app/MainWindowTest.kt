// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import app.keyrook.core.settings.ScreenArea
import app.keyrook.core.settings.WindowGeometry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MainWindowTest {
    private val previous = WindowGeometry(100, 50, 1000, 700, false)

    @Test fun `floating windows record their rounded position and size`() {
        val state = WindowState(position = WindowPosition(-20.4.dp, 30.6.dp), size = DpSize(1234.5.dp, 876.2.dp))
        assertEquals(WindowGeometry(-20, 31, 1235, 876, false), windowGeometryOf(state, previous))
        val small = WindowState(position = WindowPosition(10.dp, 10.dp), size = DpSize(300.dp, 200.dp))
        assertEquals(WindowGeometry(10, 10, WindowGeometry.MIN_WIDTH, WindowGeometry.MIN_HEIGHT, false), windowGeometryOf(small, null))
        val unplaced = WindowState(position = WindowPosition(Alignment.Center), size = DpSize(900.dp, 600.dp))
        assertEquals(WindowGeometry(100, 50, 900, 600, false), windowGeometryOf(unplaced, previous))
        assertEquals(WindowGeometry(null, null, 900, 600, false), windowGeometryOf(unplaced, null))
        assertEquals(previous, windowGeometryOf(WindowState(size = DpSize.Unspecified), previous))
    }

    @Test fun `maximized minimized and fullscreen windows keep the previous floating bounds`() {
        val maximized = WindowState(placement = WindowPlacement.Maximized, position = WindowPosition(0.dp, 0.dp), size = DpSize(1920.dp, 1040.dp))
        assertEquals(previous.copy(maximized = true), windowGeometryOf(maximized, previous))
        assertEquals(WindowGeometry.DEFAULT.copy(maximized = true), windowGeometryOf(maximized, null))
        assertEquals(previous, windowGeometryOf(WindowState(isMinimized = true, size = DpSize(10.dp, 10.dp)), previous))
        assertEquals(previous, windowGeometryOf(WindowState(placement = WindowPlacement.Fullscreen), previous))
        assertNull(windowGeometryOf(WindowState(placement = WindowPlacement.Fullscreen), null))
    }

    @Test fun `saved geometry on a disconnected screen starts centered`() {
        val screen = ScreenArea(0, 0, 1920, 1040)
        assertEquals(WindowGeometry(460, 170, 1000, 700, true),
            initialWindowGeometry(WindowGeometry(5000, 5000, 1000, 700, true), listOf(screen)))
        assertEquals(WindowGeometry(410, 140, WindowGeometry.DEFAULT_WIDTH, WindowGeometry.DEFAULT_HEIGHT, false),
            initialWindowGeometry(null, listOf(screen)))
        assertEquals(WindowGeometry.DEFAULT, initialWindowGeometry(null, emptyList()))
        assertEquals(WindowGeometry.MIN_WIDTH to WindowGeometry.MIN_HEIGHT, minimumWindowSize().let { it.width to it.height })
    }

    @Test fun `saving the window geometry keeps the other preferences`() {
        val settings = SettingsStore(null)
        settings.update { it.copy(theme = ThemeMode.DARK) }
        saveWindowGeometry(settings, WindowState(position = WindowPosition(40.dp, 60.dp), size = DpSize(1000.dp, 700.dp)))
        assertEquals(AppSettings(theme = ThemeMode.DARK, window = WindowGeometry(40, 60, 1000, 700, false)), settings.current())
        saveWindowGeometry(settings, WindowState(placement = WindowPlacement.Maximized))
        assertEquals(WindowGeometry(40, 60, 1000, 700, true), settings.current().window)
    }
}
