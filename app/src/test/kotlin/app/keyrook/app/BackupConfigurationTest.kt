// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.backup.BackupPolicy
import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.Vault
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BackupConfigurationTest {
    @TempDir lateinit var directory: Path

    @Test fun `retention accepts exact bounds and rejects invalid input without clamping`() {
        assertEquals(BackupPolicy(1, 0), parseBackupPolicy("1", "0"))
        assertEquals(BackupPolicy(1000, 3660), parseBackupPolicy("1000", "3660"))
        assertEquals(BackupPolicy(), parseBackupPolicy(" 30 ", "30"))
        listOf("0" to "30", "1001" to "30", "1" to "-1", "1" to "3661",
            "" to "30", "1.5" to "30", "1" to "", "2147483648" to "30",
            "1" to "2147483648", "abc" to "30").forEach { (latest, daily) ->
            assertNull(parseBackupPolicy(latest, daily), "$latest / $daily")
        }
    }

    @Test fun `confirmed custom retention controls automatic backup rotation`() {
        val root = directory.toRealPath()
        val folder = Files.createDirectory(root.resolve("backups"))
        withVault(root) { controller ->
            assertTrue(applyBackupConfiguration(controller, BackupConfiguration(folder, BackupPolicy(2, 0))))
            repeat(3) { controller.session.snapshot().use { controller.session.save(it) } }
            assertEquals(2L, backupCount(folder))
        }
    }

    @Test fun `canceled invalid and expired selections preserve existing backup configuration`() {
        val root = directory.toRealPath()
        val original = Files.createDirectory(root.resolve("original"))
        val replacement = Files.createDirectory(root.resolve("replacement"))
        withVault(root) { controller ->
            assertTrue(applyBackupConfiguration(controller, BackupConfiguration(original, BackupPolicy(1, 0))))
            assertFalse(applyBackupConfiguration(controller, null))
            assertThrows(IllegalArgumentException::class.java) {
                applyBackupConfiguration(controller, BackupConfiguration(root.resolve("missing"), BackupPolicy()))
            }
            val token = controller.sessionEpoch.capture()
            withOperationGuard(controller, token) {
                controller.sessionEpoch.invalidate()
                assertThrows(IllegalStateException::class.java) {
                    applyBackupConfiguration(controller, BackupConfiguration(replacement, BackupPolicy()))
                }
            }
            repeat(2) { controller.session.snapshot().use { controller.session.save(it) } }
            assertEquals(1L, backupCount(original))
            assertEquals(0L, backupCount(replacement))
            assertFalse(Files.exists(root.resolve("missing")))
        }
    }

    private fun withVault(root: Path, action: (VaultController) -> Unit) {
        VaultController().use { controller ->
            Secret("synthetic backup configuration password".toCharArray()).use { secret ->
                Credentials(secret).use { credentials ->
                    Vault().use { vault ->
                        controller.session.create(root.resolve("test.keyrook"), vault, credentials,
                            KdfParameters(iterations = 1))
                    }
                }
            }
            action(controller)
        }
    }

    private fun backupCount(folder: Path): Long = Files.list(folder).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".keyrook.bak") }.count()
    }
}
