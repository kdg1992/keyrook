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

    @Test fun `remembered retention that would delete existing backups needs confirmation`() {
        val folder = Path.of("backups").toAbsolutePath()
        val enabled = StoredBackup(folder, BackupPolicy(2, 1), true)
        val configuration = BackupConfiguration(folder, BackupPolicy(2, 1))
        assertEquals(RememberedBackup.None, decideRememberedBackup(null, 5))
        assertEquals(RememberedBackup.Unavailable, decideRememberedBackup(enabled, null))
        assertEquals(RememberedBackup.Restore(configuration, 0), decideRememberedBackup(enabled, 0))
        // Rotation keeps at most latest + daily files, so as many as that are left untouched by an unchanged policy.
        assertEquals(RememberedBackup.Restore(configuration, 3), decideRememberedBackup(enabled, 3))
        assertEquals(RememberedBackup.Confirm(configuration, 4), decideRememberedBackup(enabled, 4))
        val tampered = StoredBackup(folder, BackupPolicy(1, 0), true)
        assertEquals(RememberedBackup.Confirm(BackupConfiguration(folder, BackupPolicy(1, 0)), 60),
            decideRememberedBackup(tampered, 60))
        val disabled = enabled.copy(enabled = false)
        assertEquals(RememberedBackup.Disabled(folder, 2), decideRememberedBackup(disabled, 2))
        assertEquals(RememberedBackup.None, decideRememberedBackup(disabled, 0))
        assertEquals(RememberedBackup.None, decideRememberedBackup(disabled, null))
    }

    @Test fun `declined destructive retention leaves backups unconfigured and files intact`() {
        val root = directory.toRealPath()
        val folder = Files.createDirectory(root.resolve("backups"))
        val settings = SettingsStore(root.resolve("config"))
        VaultController().use { controller ->
            controller.unlock(root.resolve("test.keyrook"), "synthetic retention password".toCharArray(), null, true,
                KdfParameters(iterations = 1)).close()
            val vault = controller.vaultPath!!
            assertTrue(applyBackupConfiguration(controller, BackupConfiguration(folder, BackupPolicy(5, 0)), settings))
            repeat(4) { controller.session.snapshot().use { controller.session.save(it) } }
            assertEquals(4L, backupCount(folder))
            // Simulates a tampered settings file that shrinks retention.
            settings.update { it.withBackup(vault, StoredBackup(folder, BackupPolicy(1, 0), true)) }
            assertTrue(settings.flush())
            controller.lock()
            controller.unlock(vault, "synthetic retention password".toCharArray(), null, false).close()
            val asked = mutableListOf<String>()
            val declined = restoreRememberedBackups(controller, settings) { asked += it; false }
            assertEquals(BackupNotice(UiText.text("settings.backupRetentionDeclined"), true), declined)
            assertEquals(listOf(UiText.text("settings.backupRetentionConfirm", folder.toString(), 1, 0, 4)), asked)
            assertFalse(controller.session.backupStatus().configured)
            controller.session.snapshot().use { controller.session.save(it) }
            assertEquals(4L, backupCount(folder))
            controller.lock()
            controller.unlock(vault, "synthetic retention password".toCharArray(), null, false).close()
            val accepted = restoreRememberedBackups(controller, settings) { true }
            assertEquals(BackupNotice(UiText.text("settings.backupRestored", folder.toString(), 1, 0), false), accepted)
            assertTrue(controller.session.backupStatus().configured)
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
