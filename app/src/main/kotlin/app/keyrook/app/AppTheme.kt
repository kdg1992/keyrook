// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.material.*
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.pow

/**
 * Opaque ARGB colours of one high-contrast Material colour set. Kept as plain numbers so the contrast guarantees can be
 * checked without a window ([textPairs], [contrastRatio]).
 */
internal data class ContrastColors(
    val primary: Long, val primaryVariant: Long, val secondary: Long, val secondaryVariant: Long,
    val background: Long, val surface: Long, val error: Long,
    val onPrimary: Long, val onSecondary: Long, val onBackground: Long, val onSurface: Long, val onError: Long,
) {
    /**
     * Foreground and background of every text combination the Material components draw from this set: each on-colour on
     * its colour, and primary (text buttons, links), secondary and error text on the surface and background.
     */
    fun textPairs(): List<Pair<Long, Long>> = listOf(
        onPrimary to primary, onSecondary to secondary, onBackground to background, onSurface to surface, onError to error,
        primary to surface, primary to background, secondary to surface, secondary to background,
        error to surface, error to background,
    )

    fun toMaterial(dark: Boolean): Colors = if (dark) darkColors(
        primary = Color(primary), primaryVariant = Color(primaryVariant), secondary = Color(secondary),
        secondaryVariant = Color(secondaryVariant), background = Color(background), surface = Color(surface), error = Color(error),
        onPrimary = Color(onPrimary), onSecondary = Color(onSecondary), onBackground = Color(onBackground),
        onSurface = Color(onSurface), onError = Color(onError),
    ) else lightColors(
        primary = Color(primary), primaryVariant = Color(primaryVariant), secondary = Color(secondary),
        secondaryVariant = Color(secondaryVariant), background = Color(background), surface = Color(surface), error = Color(error),
        onPrimary = Color(onPrimary), onSecondary = Color(onSecondary), onBackground = Color(onBackground),
        onSurface = Color(onSurface), onError = Color(onError),
    )
}

/**
 * The high-contrast colours: black on white or white on black, with dark blue or yellow as the accent. Every text
 * combination reaches at least 7:1 (WCAG AAA); focused controls get a stronger focus tint that keeps text above 4.5:1.
 */
internal object HighContrastPalette {
    val LIGHT = ContrastColors(
        primary = 0xFF002E6E, primaryVariant = 0xFF001A40, secondary = 0xFF5C3300, secondaryVariant = 0xFF5C3300,
        background = 0xFFFFFFFF, surface = 0xFFFFFFFF, error = 0xFF9E0000,
        onPrimary = 0xFFFFFFFF, onSecondary = 0xFFFFFFFF, onBackground = 0xFF000000, onSurface = 0xFF000000, onError = 0xFFFFFFFF,
    )
    val DARK = ContrastColors(
        primary = 0xFFFFD400, primaryVariant = 0xFFFFE066, secondary = 0xFF7FDBFF, secondaryVariant = 0xFF7FDBFF,
        background = 0xFF000000, surface = 0xFF000000, error = 0xFFFF8A80,
        onPrimary = 0xFF000000, onSecondary = 0xFF000000, onBackground = 0xFFFFFFFF, onSurface = 0xFFFFFFFF, onError = 0xFF000000,
    )

    /** Opacity of the focus and press tint; the Material default of about 0.12 is hard to see. */
    const val FOCUS_ALPHA = 0.3f

    /** Opacity Material 2 uses for medium-emphasis text such as field labels in a high-contrast colour set. */
    const val MEDIUM_TEXT_ALPHA = 0.74f

    val rippleAlpha = RippleAlpha(draggedAlpha = 0.24f, focusedAlpha = FOCUS_ALPHA, hoveredAlpha = 0.12f, pressedAlpha = FOCUS_ALPHA)
}

/** WCAG 2 relative luminance of an opaque ARGB colour; the alpha byte is ignored. */
internal fun relativeLuminance(argb: Long): Double {
    fun channel(shift: Int): Double {
        val value = ((argb shr shift) and 0xFF) / 255.0
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

/** WCAG 2 contrast ratio between two opaque colours, from 1.0 (equal) to 21.0 (black and white). */
internal fun contrastRatio(first: Long, second: Long): Double {
    val a = relativeLuminance(first)
    val b = relativeLuminance(second)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

/** [foreground] drawn with [alpha] over opaque [background], as an opaque ARGB colour. */
internal fun blend(foreground: Long, background: Long, alpha: Float): Long {
    fun channel(shift: Int): Long {
        val mixed = ((foreground shr shift) and 0xFF) * alpha + ((background shr shift) and 0xFF) * (1 - alpha)
        return Math.round(mixed).toLong().coerceIn(0, 255) shl shift
    }
    return 0xFF000000 or channel(16) or channel(8) or channel(0)
}

/** The standard Material colours by default; [ContrastMode.HIGH] uses [HighContrastPalette] in the same brightness. */
internal fun appColors(dark: Boolean, contrast: ContrastMode): Colors = when (contrast) {
    ContrastMode.STANDARD -> if (dark) darkColors() else lightColors()
    ContrastMode.HIGH -> (if (dark) HighContrastPalette.DARK else HighContrastPalette.LIGHT).toMaterial(dark)
}

/**
 * [base] enlarged by [scalePercent]. Text sizes in sp are converted through the density as well, so the font scale is
 * kept as it is; scaling it too would enlarge text twice.
 */
internal fun scaledDensity(base: Density, scalePercent: Int): Density =
    Density(base.density * scalePercent / 100f, base.fontScale)

/**
 * The application theme: colours for [dark] and [contrast], and every size and text scaled by [scalePercent]. Dialogs
 * inherit both because they are composed inside it and drawn in the main window.
 */
@Composable
internal fun KeyrookTheme(dark: Boolean, contrast: ContrastMode, scalePercent: Int, content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val density = remember(base, scalePercent) { scaledDensity(base, scalePercent) }
    CompositionLocalProvider(LocalDensity provides density) {
        MaterialTheme(colors = appColors(dark, contrast)) {
            if (contrast == ContrastMode.HIGH) {
                CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration(rippleAlpha = HighContrastPalette.rippleAlpha),
                    content = content)
            } else content()
        }
    }
}
