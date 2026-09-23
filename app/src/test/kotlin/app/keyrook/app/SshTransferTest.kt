// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.ssh.SshKeyMaterial
import app.keyrook.core.ssh.SshKeyType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SshTransferTest {
    @TempDir lateinit var directory: Path
    private val publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAINdamAGCsQq31Uv+08lkBzoO4XLz2qYjJa8CGmj3B1Ea"

    @Test fun `public export writes a validated line to a new file without overwriting`() {
        val path = directory.resolve("authorized_keys.pub")
        exportPublicSshKey(path, publicKey) { true }
        assertEquals(publicKey + "\n", Files.readString(path))
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) {
            exportPublicSshKey(path, publicKey) { true }
        }
        assertEquals(publicKey + "\n", Files.readString(path))
    }

    @Test fun `invalid or cancelled public export leaves no file`() {
        val path = directory.resolve("key.pub")
        assertThrows(IllegalArgumentException::class.java) { exportPublicSshKey(path, "ssh-ed25519 invalid") { true } }
        assertFalse(Files.exists(path))
        assertThrows(IllegalStateException::class.java) { exportPublicSshKey(path, publicKey) { false } }
        assertFalse(Files.exists(path))
        var checks = 0
        assertThrows(IllegalStateException::class.java) { exportPublicSshKey(path, publicKey) { ++checks == 1 } }
        assertFalse(Files.exists(path))
    }

    @Test fun `import result and passphrase are erased after insertion cancellation and failure`() {
        for (mode in 0..2) {
            val key = SshKeyMaterial(SshKeyType.ED25519, Secret("synthetic".toCharArray()), publicKey, "synthetic")
            val phrase = "synthetic phrase".toCharArray()
            var inserted = false
            val action = {
                deliverImportedSshKey(key, phrase, { mode != 1 }) { material, password ->
                    inserted = true
                    assertEquals("synthetic phrase", password)
                    material.privateKey.useChars { assertEquals("synthetic", String(it)) }
                    if (mode == 2) error("Insertion failed")
                }
            }
            if (mode == 2) assertThrows(IllegalStateException::class.java, action) else action()
            assertEquals(mode != 1, inserted)
            assertTrue(phrase.all { it == '\u0000' })
            assertThrows(IllegalStateException::class.java) { key.privateKey.useChars {} }
        }
        val phrase = "synthetic phrase".toCharArray()
        deliverImportedSshKey(null, phrase, { true }) { _, _ -> fail("Failed import cannot be inserted") }
        assertTrue(phrase.all { it == '\u0000' })
    }
}
