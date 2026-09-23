// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ClipboardTest {
    @Test fun `changing expiry clears owned clipboard and refuses unsafe bounds`() {
        val clipboard = Clipboard("test-only")
        ClipboardGuard(clipboard).use { guard ->
            guard.copy("synthetic-secret")
            guard.configure(5)
            assertEquals("", clipboard.getData(DataFlavor.stringFlavor))
            assertThrows(IllegalArgumentException::class.java) { guard.configure(0) }
            assertThrows(IllegalArgumentException::class.java) { guard.configure(121) }
            clipboard.setContents(StringSelection("external-content"), null)
            guard.configure(120)
            assertEquals("external-content", clipboard.getData(DataFlavor.stringFlavor))
        }
    }
    @Test fun `clear removes owned secret but preserves newer external contents`() {
        val clipboard = Clipboard("test-only")
        ClipboardGuard(clipboard).use { guard ->
            guard.copy("synthetic-secret")
            guard.clear()
            assertEquals("", clipboard.getData(DataFlavor.stringFlavor))
            guard.copy("synthetic-secret")
            clipboard.setContents(StringSelection("external-content"), null)
            guard.clear()
            assertEquals("external-content", clipboard.getData(DataFlavor.stringFlavor))
        }
    }
}
