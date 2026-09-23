// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

class OperationGuardTest {
    @TempDir lateinit var directory: Path

    @Test fun `lock prevents queued plaintext export but still clears owned buffer`() {
        VaultController().use { controller ->
            val token = controller.sessionEpoch.capture()
            val bytes = "synthetic-secret".toByteArray()
            controller.sessionEpoch.invalidate()
            val target = directory.resolve("export.json")
            assertThrows(IllegalStateException::class.java) {
                withOperationGuard(controller, token) {
                    try { writePrivateNew(target, bytes) } finally { bytes.fill(0) }
                }
            }
            assertFalse(Files.exists(target))
            assertTrue(bytes.all { it == 0.toByte() })
            assertDoesNotThrow { ensureOperationCurrent() }
        }
    }

    @Test fun `captured guard rejects late dialog on event thread`() {
        VaultController().use { controller ->
            withOperationGuard(controller, controller.sessionEpoch.capture()) {
                val guard = capturedOperationGuard()
                controller.sessionEpoch.invalidate()
                SwingUtilities.invokeAndWait {
                    assertThrows(IllegalStateException::class.java) { guard() }
                }
            }
        }
    }
}
