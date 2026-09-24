// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AppThemeTest {
    private val palettes = mapOf("light" to HighContrastPalette.LIGHT, "dark" to HighContrastPalette.DARK)

    @Test fun `contrast ratios follow the WCAG definition`() {
        assertEquals(21.0, contrastRatio(0xFF000000, 0xFFFFFFFF), 1e-9)
        assertEquals(1.0, contrastRatio(0xFF777777, 0xFF777777), 1e-9)
        assertEquals(contrastRatio(0xFF002E6E, 0xFFFFFFFF), contrastRatio(0xFFFFFFFF, 0xFF002E6E), 1e-12)
        // Reference values: #777777 on white just misses AA; #767676 is the lightest grey that passes.
        assertEquals(4.48, contrastRatio(0xFF777777, 0xFFFFFFFF), 0.01)
        assertTrue(contrastRatio(0xFF767676, 0xFFFFFFFF) >= 4.5)
        assertEquals(0.0, relativeLuminance(0xFF000000), 1e-12)
        assertEquals(1.0, relativeLuminance(0xFFFFFFFF), 1e-12)
        assertEquals(0xFF808080, blend(0xFFFFFFFF, 0xFF000000, 0.5f))
        assertEquals(0xFF002E6E, blend(0xFF002E6E, 0xFFFFFFFF, 1f))
    }

    @Test fun `every text colour of the high-contrast palettes reaches AAA contrast`() {
        palettes.forEach { (name, palette) ->
            palette.textPairs().forEach { (foreground, background) ->
                val ratio = contrastRatio(foreground, background)
                assertTrue(ratio >= 7.0, "$name ${foreground.toString(16)} on ${background.toString(16)}: $ratio")
            }
            // Medium-emphasis text such as field labels is drawn with reduced opacity and still passes AA.
            listOf(palette.onSurface to palette.surface, palette.onBackground to palette.background).forEach { (text, surface) ->
                val medium = blend(text, surface, HighContrastPalette.MEDIUM_TEXT_ALPHA)
                assertTrue(contrastRatio(medium, surface) >= 4.5, "$name medium text")
            }
        }
    }

    @Test fun `focused controls stay readable and visibly different from unfocused ones`() {
        palettes.forEach { (name, palette) ->
            // Text buttons tint with their text colour, filled buttons with their content colour over the fill.
            listOf(palette.primary to palette.surface, palette.onPrimary to palette.primary).forEach { (content, fill) ->
                val focused = blend(content, fill, HighContrastPalette.FOCUS_ALPHA)
                assertTrue(contrastRatio(content, focused) >= 4.5, "$name text on focus tint")
                assertTrue(contrastRatio(focused, fill) >= 1.8, "$name focus tint visibility")
            }
            assertEquals(HighContrastPalette.FOCUS_ALPHA, HighContrastPalette.rippleAlpha.focusedAlpha)
        }
    }

    @Test fun `the standard colours stay the default and high contrast keeps the brightness`() {
        assertEquals(ContrastMode.STANDARD, AppSettings().contrast)
        assertTrue(appColors(dark = false, ContrastMode.HIGH).isLight)
        assertFalse(appColors(dark = true, ContrastMode.HIGH).isLight)
        assertEquals(Color(0xFF000000), appColors(dark = false, ContrastMode.HIGH).onSurface)
        assertEquals(Color(0xFFFFFFFF), appColors(dark = true, ContrastMode.HIGH).onSurface)
        assertNotEquals(appColors(dark = false, ContrastMode.HIGH).primary, appColors(dark = false, ContrastMode.STANDARD).primary)
    }

    @Test fun `interface scaling enlarges the density once and keeps the font scale`() {
        val base = Density(2f, 1.25f)
        assertEquals(Density(3f, 1.25f), scaledDensity(base, 150))
        assertEquals(Density(1.8f, 1.25f), scaledDensity(base, 90))
        assertEquals(base, scaledDensity(base, DEFAULT_UI_SCALE))
        assertTrue(DEFAULT_UI_SCALE in UI_SCALE_CHOICES)
        assertEquals(150, UI_SCALE_CHOICES.max())
    }
}
