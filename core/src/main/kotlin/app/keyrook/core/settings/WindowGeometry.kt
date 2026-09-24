// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.settings

/** A connected screen's bounds in window-system units (AWT user space, which Compose Desktop uses as dp). */
data class ScreenArea(val x: Int, val y: Int, val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) { "Screen area must not be empty" } }
}

/**
 * Size, position and maximized state of the main window. [x] and [y] are both null when no position is known;
 * [fitTo] then centers the window. Sizes always lie within [MIN_WIDTH]..[MAX_SIZE] and [MIN_HEIGHT]..[MAX_SIZE].
 */
data class WindowGeometry(val x: Int?, val y: Int?, val width: Int, val height: Int, val maximized: Boolean) {
    init {
        require(width in MIN_WIDTH..MAX_SIZE && height in MIN_HEIGHT..MAX_SIZE) { "Window size out of range" }
        require((x == null) == (y == null)) { "Window position must be complete" }
        require(x == null || (y != null && x in COORDINATES && y in COORDINATES)) { "Window position out of range" }
    }

    fun toDocument() = WindowSettingsDocument(x, y, width, height, maximized)

    /**
     * Fits this geometry to the currently connected [screens], primary screen first. The position is kept when the
     * window's title strip is visible on one screen (fully in height, at least [MIN_VISIBLE_WIDTH] wide); otherwise the
     * window is centered on the primary screen. The size is reduced to that screen, never below the minimum size.
     * Without any screen (headless) the geometry is returned unchanged.
     */
    fun fitTo(screens: List<ScreenArea>): WindowGeometry {
        val primary = screens.firstOrNull() ?: return this
        val home = if (x != null && y != null) screens.firstOrNull { titleVisible(it, x, y) } else null
        val screen = home ?: primary
        val fittedWidth = width.coerceAtMost(screen.width).coerceAtLeast(MIN_WIDTH)
        val fittedHeight = height.coerceAtMost(screen.height).coerceAtLeast(MIN_HEIGHT)
        if (home != null) return copy(width = fittedWidth, height = fittedHeight)
        return copy(
            x = screen.x + ((screen.width - fittedWidth) / 2).coerceAtLeast(0),
            y = screen.y + ((screen.height - fittedHeight) / 2).coerceAtLeast(0),
            width = fittedWidth, height = fittedHeight,
        )
    }

    private fun titleVisible(screen: ScreenArea, left: Int, top: Int): Boolean {
        val screenRight = screen.x.toLong() + screen.width
        val screenBottom = screen.y.toLong() + screen.height
        val overlap = minOf(left.toLong() + width, screenRight) - maxOf(left.toLong(), screen.x.toLong())
        return top >= screen.y && top.toLong() + TITLE_STRIP <= screenBottom && overlap >= MIN_VISIBLE_WIDTH
    }

    companion object {
        const val MIN_WIDTH = 720
        const val MIN_HEIGHT = 520
        const val DEFAULT_WIDTH = 1100
        const val DEFAULT_HEIGHT = 760
        const val MAX_SIZE = 16384
        const val MAX_COORDINATE = 100_000
        const val TITLE_STRIP = 32
        const val MIN_VISIBLE_WIDTH = 120
        private val COORDINATES = -MAX_COORDINATE..MAX_COORDINATE

        val DEFAULT = WindowGeometry(null, null, DEFAULT_WIDTH, DEFAULT_HEIGHT, false)

        /**
         * Validates a stored entry. Missing or non-positive sizes use the default size, other sizes are clamped to the
         * allowed range; an incomplete or out-of-range position is dropped so the window is centered. Null stays null.
         */
        fun fromDocument(document: WindowSettingsDocument?): WindowGeometry? {
            if (document == null) return null
            fun size(value: Int?, minimum: Int, default: Int) =
                value?.takeIf { it > 0 }?.coerceIn(minimum, MAX_SIZE) ?: default
            val position = document.x?.takeIf { it in COORDINATES }?.let { x -> document.y?.takeIf { it in COORDINATES }?.let { x to it } }
            return WindowGeometry(
                x = position?.first, y = position?.second,
                width = size(document.width, MIN_WIDTH, DEFAULT_WIDTH),
                height = size(document.height, MIN_HEIGHT, DEFAULT_HEIGHT),
                maximized = document.maximized == true,
            )
        }
    }
}
