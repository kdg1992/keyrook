// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.backup.BackupPolicy
import app.keyrook.core.backup.BackupService
import app.keyrook.core.crypto.AuthenticationException
import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.InvalidVaultException
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.Vault
import app.keyrook.core.service.BackupStatus
import app.keyrook.core.service.VaultSession
import app.keyrook.core.storage.VaultConflictException
import app.keyrook.core.storage.VaultStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ManualBackupTest {
    @TempDir lateinit var directory: Path
    private val kdf = KdfParameters(iterations = 1)

    @Test fun `manual backup authenticates after reopen preserves source revision and rotates`() {
        val root = directory.toRealPath()
        val source = root.resolve("source.keyrook")
        val folder = Files.createDirectory(root.resolve("backups"))
        credentials().use { credentials ->
            VaultSession().use { session ->
                Vault().use { session.create(source, it, credentials, kdf) }
                session.snapshot().use { session.save(it) }
                session.lock()
                session.open(source, credentials)
                session.configureBackups(BackupService(folder, BackupPolicy(1, 0)))
                val before = Files.readAllBytes(source)
                val first = session.backupNow()
                assertEquals(BackupStatus(true, 1), session.backupStatus())
                val second = session.backupNow()
                assertFalse(Files.exists(first.path))
                assertEquals(1, second.removed)
                assertArrayEquals(before, Files.readAllBytes(second.path))
                assertArrayEquals(before, Files.readAllBytes(source))
                session.snapshot().use { assertEquals(1L, it.revision) }
                VaultStore().load(second.path, credentials).use { assertEquals(1L, it.vault.revision) }
                this.credentials("incorrect password").use { wrong ->
                    assertThrows(AuthenticationException::class.java) { VaultStore().load(second.path, wrong) }
                }
            }
        }
    }

    @Test fun `manual backup rejects corrupt or externally changed persisted source`() {
        val root = directory.toRealPath()
        val source = root.resolve("source.keyrook")
        val folder = Files.createDirectory(root.resolve("backups"))
        credentials().use { credentials -> VaultSession().use { session ->
            Vault().use { session.create(source, it, credentials, kdf) }
            session.configureBackups(BackupService(folder))
            val original = Files.readAllBytes(source)
            val altered = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            Files.write(source, altered)
            assertThrows(InvalidVaultException::class.java) { session.backupNow() }
            Files.write(source, original)
            val store = VaultStore()
            store.load(source, credentials).use {
                store.save(source, it.vault.copy(revision = 1), credentials, it.stamp, kdf)
            }
            assertThrows(VaultConflictException::class.java) { session.backupNow() }
            assertEquals(BackupStatus(true, null), session.backupStatus())
            session.snapshot().use { assertEquals(0L, it.revision) }
            Files.list(folder).use { assertEquals(0L, it.count()) }
        } }
    }

    @Test fun `status reflects automatic and manual backups then clears on disable and lock`() {
        val root = directory.toRealPath()
        val source = root.resolve("source.keyrook")
        val folder = Files.createDirectory(root.resolve("backups"))
        credentials().use { credentials -> VaultSession().use { session ->
            assertEquals(BackupStatus(false, null), session.backupStatus())
            Vault().use { session.create(source, it, credentials, kdf) }
            assertThrows(IllegalStateException::class.java) { session.backupNow() }
            session.configureBackups(BackupService(folder))
            session.snapshot().use { session.save(it) }
            assertEquals(BackupStatus(true, 0), session.backupStatus())
            val backup = session.backupNow()
            assertEquals(BackupStatus(true, 1), session.backupStatus())
            session.configureBackups(null)
            assertEquals(BackupStatus(false, null), session.backupStatus())
            assertThrows(IllegalStateException::class.java) { session.backupNow() }
            assertTrue(Files.exists(backup.path))
            session.configureBackups(BackupService(folder))
            session.lock()
            assertEquals(BackupStatus(false, null), session.backupStatus())
            assertThrows(IllegalStateException::class.java) { session.backupNow() }
            assertThrows(IllegalStateException::class.java) { session.configureBackups(BackupService(folder)) }
            session.open(source, credentials)
            assertEquals(BackupStatus(false, null), session.backupStatus())
            assertThrows(IllegalStateException::class.java) { session.backupNow() }
        } }
    }

    private fun credentials(password: String = "synthetic manual backup password"): Credentials =
        Secret(password.toCharArray()).use { Credentials(it) }
}
