// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.backup.BackupPolicy
import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.Vault
import app.keyrook.core.service.BackupStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BackupActionsTest {
    @TempDir lateinit var directory: Path

    @Test fun `canceling disable preserves backup configuration and confirmed disable preserves files`() {
        withConfiguredVault { controller, folder ->
            assertFalse(disableBackups(controller, false))
            assertTrue(controller.session.backupStatus().configured)
            assertEquals(0, createManualBackup(controller))
            assertEquals(BackupStatus(true, 0), controller.session.backupStatus())
            assertTrue(backupStatusText(controller).contains("Revision: 0"))
            assertTrue(disableBackups(controller, true))
            assertEquals(BackupStatus(false, null), controller.session.backupStatus())
            assertThrows(IllegalStateException::class.java) { createManualBackup(controller) }
            Files.list(folder).use { paths -> assertEquals(1L, paths.filter { it.toString().endsWith(".keyrook.bak") }.count()) }
            assertFalse(backupStatusText(controller).contains(folder.toString()))
            controller.lock()
            assertEquals(BackupStatus(false, null), controller.session.backupStatus())
        }
    }

    @Test fun `expired modal cannot disable backups or start manual backup`() {
        withConfiguredVault { controller, folder ->
            val token = controller.sessionEpoch.capture()
            withOperationGuard(controller, token) {
                controller.sessionEpoch.invalidate()
                assertThrows(IllegalStateException::class.java) { disableBackups(controller, true) }
                assertThrows(IllegalStateException::class.java) { createManualBackup(controller) }
                assertThrows(IllegalStateException::class.java) { backupStatusText(controller) }
            }
            assertEquals(BackupStatus(true, null), controller.session.backupStatus())
            Files.list(folder).use { assertEquals(0L, it.count()) }
        }
    }

    @Test fun `integrity report lists vault and backups without folder paths or changes`() {
        withConfiguredVault { controller, folder ->
            assertEquals(0, createManualBackup(controller))
            val before = Files.list(folder).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() }
            val text = integrityReportText(controller)
            assertTrue(text.contains("2 von 2"))
            assertTrue(text.contains("vault.keyrook"))
            assertFalse(text.contains(folder.toString()))
            assertEquals(before, Files.list(folder).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() })
            val token = controller.sessionEpoch.capture()
            withOperationGuard(controller, token) {
                controller.sessionEpoch.invalidate()
                assertThrows(IllegalStateException::class.java) { integrityReportText(controller) }
            }
        }
    }

    private fun withConfiguredVault(action: (VaultController, Path) -> Unit) {
        val root = directory.toRealPath()
        val folder = Files.createDirectory(root.resolve("backups"))
        VaultController().use { controller ->
            Secret("synthetic backup action password".toCharArray()).use { secret ->
                Credentials(secret).use { credentials -> Vault().use { vault ->
                    controller.session.create(root.resolve("vault.keyrook"), vault, credentials, KdfParameters(iterations = 1))
                } }
            }
            applyBackupConfiguration(controller, BackupConfiguration(folder, BackupPolicy(2, 0)))
            action(controller, folder)
        }
    }
}
