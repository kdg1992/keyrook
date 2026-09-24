// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

class DesktopSmokeTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `unlock screen renders offscreen without creating a desktop window`() {
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(width = 1000, height = 800, content = { KeyrookApp(settings = remember { SettingsStore(null) }) })
            try {
                scene.render(0).close()
                scene.render(100_000_000).use { image ->
                    assertEquals(1000, image.width)
                    assertEquals(800, image.height)
                    image.encodeToData().use { encoded -> assertTrue(encoded != null && encoded.size > 1000) }
                }
            } finally { scene.close(); UiText.select(AppLanguage.GERMAN) }
        }
    }
}
