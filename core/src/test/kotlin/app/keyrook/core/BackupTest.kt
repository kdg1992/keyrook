// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.backup.BackupPolicy
import app.keyrook.core.backup.BackupService
import app.keyrook.core.crypto.InvalidVaultException
import app.keyrook.core.model.Vault
import app.keyrook.core.service.VaultSession
import app.keyrook.core.storage.VaultConflictException
import app.keyrook.core.storage.VaultStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BackupTest {
    @TempDir lateinit var directory: Path
    private val source get() = directory.toRealPath().resolve("vault.keyrook")
    private val store = VaultStore()
    private fun folder() = Files.createDirectory(directory.toRealPath().resolve("backups"))

    @Test fun `backup is exact ciphertext and preview needs authentication`() {
        val backups = BackupService(folder())
        credentials().use { c -> sampleVault().use { vault ->
            val saved = store.save(source, vault, c, parameters = testKdf)
            val result = backups.create(source, c, saved.stamp)
            assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(result.path))
            val preview = backups.preview(result.path, c)
            assertEquals(vault.id, preview.vaultId)
            assertEquals(8, preview.entries)
            credentials("wrong").use { wrong ->
                assertThrows(InvalidVaultException::class.java) { backups.preview(result.path, wrong) }
            }
        } }
    }

    @Test fun `restore creates new file and refuses existing target`() {
        val backups = BackupService(folder())
        credentials().use { c -> sampleVault().use { vault ->
            store.save(source, vault, c, parameters = testKdf)
            val backup = backups.create(source, c).path
            val preview = backups.preview(backup, c)
            val target = directory.toRealPath().resolve("restored.keyrook")
            backups.restoreToNew(backup, target, c, preview)
            store.load(target, c).use { loaded ->
                assertEquals(vault.id, loaded.vault.id)
                assertEquals(vault.entries.size, loaded.vault.entries.size)
            }
            val before = Files.readAllBytes(target)
            assertThrows(VaultConflictException::class.java) { backups.restoreToNew(backup, target, c, preview) }
            assertArrayEquals(before, Files.readAllBytes(target))
        } }
    }

    @Test fun `restore rejects changed backup after preview without creating target`() {
        val backups = BackupService(folder())
        credentials().use { c ->
            store.save(source, Vault(), c, parameters = testKdf)
            val backup = backups.create(source, c).path
            val preview = backups.preview(backup, c)
            val altered = Files.readAllBytes(backup)
            altered[altered.lastIndex] = (altered.last().toInt() xor 1).toByte()
            Files.write(backup, altered)
            val target = directory.toRealPath().resolve("restored.keyrook")
            assertThrows(VaultConflictException::class.java) { backups.restoreToNew(backup, target, c, preview) }
            assertFalse(Files.exists(target))
            assertThrows(InvalidVaultException::class.java) { backups.preview(backup, c) }
        }
    }

    @Test fun `stale source stamp is refused and creates no backup`() {
        val root = folder()
        val backups = BackupService(root)
        credentials().use { c ->
            val vault = Vault()
            val original = store.save(source, vault, c, parameters = testKdf)
            store.save(source, vault.copy(revision = 1), c, original.stamp, testKdf)
            assertThrows(VaultConflictException::class.java) { backups.create(source, c, original.stamp) }
            Files.list(root).use { assertEquals(0L, it.count()) }
        }
    }

    @Test fun `rotation keeps latest versions plus daily snapshots and foreign files`() {
        val root = folder()
        val unrelated = root.resolve("unrelated.keyrook.bak")
        Files.write(unrelated, byteArrayOf(1, 2, 3))
        credentials().use { c ->
            store.save(source, Vault(), c, parameters = testKdf)
            val times = listOf("2026-01-01T12:00:00Z", "2026-01-02T12:00:00Z", "2026-01-03T12:00:00Z",
                "2026-01-03T13:00:00Z", "2026-01-03T14:00:00Z")
            val paths = times.map { date ->
                BackupService(root, BackupPolicy(latest = 2, daily = 3), Clock.fixed(Instant.parse(date), ZoneOffset.UTC))
                    .create(source, c).path
            }
            assertTrue(Files.exists(paths[0]))
            assertTrue(Files.exists(paths[1]))
            assertFalse(Files.exists(paths[2]))
            assertTrue(Files.exists(paths[3]))
            assertTrue(Files.exists(paths[4]))
            assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(unrelated))
        }
    }

    @Test fun `backups on save retain prior revision including old password`() {
        val root = folder()
        credentials().use { c -> credentials("new master").use { next -> VaultSession().use { session ->
            session.create(source, Vault(), c, testKdf)
            session.configureBackups(BackupService(root))
            session.changePassword(next, testKdf)
            val backup = Files.list(root).use { files -> files.filter { it.toString().endsWith(".keyrook.bak") }.findFirst().orElseThrow() }
            store.load(backup, c).use { assertEquals(0L, it.vault.revision) }
            store.load(source, next).use { assertEquals(1L, it.vault.revision) }
        } } }
    }

    @Test fun `unavailable backup folder aborts save and preserves original`() {
        credentials().use { c -> VaultSession().use { session ->
            session.create(source, Vault(), c, testKdf)
            val before = Files.readAllBytes(source)
            session.configureBackups(BackupService(directory.resolve("missing")))
            session.snapshot().use { candidate ->
                assertThrows(IOException::class.java) { session.save(candidate) }
            }
            assertArrayEquals(before, Files.readAllBytes(source))
            session.snapshot().use { assertEquals(0L, it.revision) }
        } }
    }

    @Test fun `nonregular source and wrong credentials never create backups`() {
        val root = folder()
        val backups = BackupService(root)
        credentials().use { c ->
            assertThrows(IOException::class.java) { backups.create(root, c) }
            store.save(source, Vault(), c, parameters = testKdf)
            credentials("wrong").use { wrong ->
                assertThrows(InvalidVaultException::class.java) { backups.create(source, wrong) }
            }
            Files.list(root).use { assertEquals(0L, it.count()) }
        }
    }

    @Test fun `locking clears backup configuration before opening another vault`() {
        credentials().use { c -> VaultSession().use { session ->
            session.create(source, Vault(), c, testKdf)
            session.configureBackups(BackupService(directory.resolve("missing")))
            session.lock()
            session.open(source, c)
            session.snapshot().use { session.save(it) }
            session.snapshot().use { assertEquals(1L, it.revision) }
        } }
    }
}
