// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.settings.ScreenArea
import app.keyrook.core.settings.WindowGeometry
import app.keyrook.core.settings.WindowSettingsDocument
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WindowGeometryTest {
    private val primary = ScreenArea(0, 0, 1920, 1040)
    private val right = ScreenArea(1920, -200, 1280, 1024)

    @Test fun `valid stored geometry is kept exactly`() {
        val document = WindowSettingsDocument(100, 80, 1000, 700, true)
        val geometry = WindowGeometry.fromDocument(document)!!
        assertEquals(WindowGeometry(100, 80, 1000, 700, true), geometry)
        assertEquals(document, geometry.toDocument())
        assertNull(WindowGeometry.fromDocument(null))
    }

    @Test fun `stored sizes are clamped and invalid values fall back to defaults`() {
        assertEquals(WindowGeometry(10, 20, WindowGeometry.MIN_WIDTH, WindowGeometry.MIN_HEIGHT, false),
            WindowGeometry.fromDocument(WindowSettingsDocument(10, 20, 300, 200, false)))
        assertEquals(WindowGeometry(10, 20, WindowGeometry.MAX_SIZE, WindowGeometry.MAX_SIZE, false),
            WindowGeometry.fromDocument(WindowSettingsDocument(10, 20, Int.MAX_VALUE, 99_999, null)))
        assertEquals(WindowGeometry.DEFAULT, WindowGeometry.fromDocument(WindowSettingsDocument()))
        assertEquals(WindowGeometry.DEFAULT, WindowGeometry.fromDocument(WindowSettingsDocument(width = 0, height = -5)))
        assertEquals(WindowGeometry.DEFAULT, WindowGeometry.fromDocument(WindowSettingsDocument(x = 10)))
        assertEquals(WindowGeometry.DEFAULT, WindowGeometry.fromDocument(WindowSettingsDocument(y = 10)))
        assertEquals(WindowGeometry.DEFAULT, WindowGeometry.fromDocument(WindowSettingsDocument(x = Int.MIN_VALUE, y = 0)))
        assertEquals(WindowGeometry.DEFAULT,
            WindowGeometry.fromDocument(WindowSettingsDocument(x = 0, y = WindowGeometry.MAX_COORDINATE + 1)))
        assertThrows<IllegalArgumentException> { WindowGeometry(0, 0, 100, 100, false) }
        assertThrows<IllegalArgumentException> { WindowGeometry(0, null, 1000, 700, false) }
    }

    @Test fun `visible positions are kept on any connected screen`() {
        val onPrimary = WindowGeometry(200, 100, 1000, 700, false)
        assertEquals(onPrimary, onPrimary.fitTo(listOf(primary, right)))
        val onRight = WindowGeometry(2000, -150, 1000, 700, true)
        assertEquals(onRight, onRight.fitTo(listOf(primary, right)))
        // Partly off the left edge, but the title strip is still reachable.
        val partly = WindowGeometry(-800, 50, 1000, 700, false)
        assertEquals(partly, partly.fitTo(listOf(primary)))
    }

    @Test fun `positions on disconnected screens are centered on the primary screen`() {
        val centered = WindowGeometry(460, 170, 1000, 700, false)
        assertEquals(centered, WindowGeometry(2000, 100, 1000, 700, false).fitTo(listOf(primary)))
        assertEquals(centered.copy(maximized = true), WindowGeometry(-5000, -5000, 1000, 700, true).fitTo(listOf(primary, right)))
        // Title strip above the screen, only a sliver visible, or below the bottom edge.
        assertEquals(centered, WindowGeometry(200, -10, 1000, 700, false).fitTo(listOf(primary)))
        assertEquals(centered, WindowGeometry(-950, 100, 1000, 700, false).fitTo(listOf(primary)))
        assertEquals(centered, WindowGeometry(200, 1030, 1000, 700, false).fitTo(listOf(primary)))
        assertEquals(WindowGeometry(410, 140, 1100, 760, false), WindowGeometry.DEFAULT.fitTo(listOf(primary)))
    }

    @Test fun `windows larger than their screen are reduced but never below the minimum size`() {
        assertEquals(WindowGeometry(0, 0, 1920, 1040, false), WindowGeometry(0, 0, 3000, 2000, false).fitTo(listOf(primary)))
        assertEquals(WindowGeometry(0, 0, 1920, 1040, false), WindowGeometry(9000, 9000, 3000, 2000, false).fitTo(listOf(primary)))
        val small = ScreenArea(100, 50, 640, 480)
        assertEquals(WindowGeometry(100, 50, WindowGeometry.MIN_WIDTH, WindowGeometry.MIN_HEIGHT, false),
            WindowGeometry(9000, 9000, 1000, 700, false).fitTo(listOf(small)))
    }

    @Test fun `without screens the geometry is unchanged`() {
        val geometry = WindowGeometry(9000, 9000, 1000, 700, false)
        assertEquals(geometry, geometry.fitTo(emptyList()))
        assertEquals(WindowGeometry.DEFAULT, WindowGeometry.DEFAULT.fitTo(emptyList()))
        assertThrows<IllegalArgumentException> { ScreenArea(0, 0, 0, 100) }
    }
}
