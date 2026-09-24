// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.backup.BackupPolicy
import app.keyrook.core.backup.BackupService
import app.keyrook.core.backup.countManagedBackups
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.model.Vault
import app.keyrook.core.service.VaultSession
import app.keyrook.core.storage.VaultStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BackupRobustnessTest {
    @TempDir lateinit var directory: Path
    private val root get() = directory.toRealPath()
    private val store = VaultStore()

    private fun service(folder: Path, policy: BackupPolicy, time: String, remove: (Path) -> Unit = { Files.delete(it) }) =
        BackupService(folder, policy, Clock.fixed(Instant.parse(time), ZoneOffset.UTC), VaultCodec(), remove)

    @Test fun `old backups that cannot be removed never fail the save and are reported once`() {
        val folder = Files.createDirectory(root.resolve("backups"))
        val source = root.resolve("vault.keyrook")
        val refused = mutableListOf<Path>()
        credentials().use { c -> VaultSession().use { session ->
            session.create(source, Vault(), c, testKdf)
            val id = session.snapshot().use { it.id }
            session.configureBackups(service(folder, BackupPolicy(1, 0), "2026-01-01T12:00:00Z"))
            session.snapshot().use { session.save(it) }
            assertNull(session.takeIncompleteRotation())
            session.configureBackups(service(folder, BackupPolicy(1, 0), "2026-01-02T12:00:00Z") {
                refused.add(it)
                throw AccessDeniedException(it.toString())
            })
            session.snapshot().use { session.save(it) }
            session.snapshot().use { assertEquals(2L, it.revision) }
            store.load(source, c).use { assertEquals(2L, it.vault.revision) }
            assertEquals(1, refused.size)
            assertEquals(2, countManagedBackups(folder, id))
            val incomplete = session.takeIncompleteRotation()!!
            assertEquals(0, incomplete.removed)
            assertEquals(1, incomplete.notRemoved)
            assertFalse(incomplete.rotationComplete)
            assertTrue(Files.exists(incomplete.path))
            assertNull(session.takeIncompleteRotation())
        } }
    }

    @Test fun `manual backup reports old backups it could not remove`() {
        val folder = Files.createDirectory(root.resolve("backups"))
        credentials().use { c -> VaultSession().use { session ->
            session.create(root.resolve("vault.keyrook"), Vault(), c, testKdf)
            session.configureBackups(service(folder, BackupPolicy(1, 0), "2026-01-01T12:00:00Z"))
            session.backupNow()
            session.configureBackups(service(folder, BackupPolicy(1, 0), "2026-01-02T12:00:00Z") {
                throw AccessDeniedException(it.toString())
            })
            val result = session.backupNow()
            assertEquals(0, result.removed)
            assertEquals(1, result.notRemoved)
            assertEquals(result, session.takeIncompleteRotation())
            session.lock()
            assertThrows(IllegalStateException::class.java) { session.snapshot() }
        } }
    }

    @Test fun `rotation removes what it can and counts the rest`() {
        val folder = Files.createDirectory(root.resolve("backups"))
        val source = root.resolve("vault.keyrook")
        credentials().use { c ->
            store.save(source, Vault(), c, parameters = testKdf)
            val first = service(folder, BackupPolicy(10, 0), "2026-01-01T12:00:00Z").create(source, c).path
            val second = service(folder, BackupPolicy(10, 0), "2026-01-02T12:00:00Z").create(source, c).path
            val result = service(folder, BackupPolicy(1, 0), "2026-01-03T12:00:00Z") {
                if (it == first) throw IOException("Simulated deletion failure") else Files.delete(it)
            }.create(source, c)
            assertEquals(1, result.removed)
            assertEquals(1, result.notRemoved)
            assertFalse(result.rotationComplete)
            assertTrue(Files.exists(first))
            assertFalse(Files.exists(second))
            assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(result.path))
        }
    }

    @Test fun `a backup that disappeared before rotation is neither removed nor reported`() {
        val folder = Files.createDirectory(root.resolve("backups"))
        val source = root.resolve("vault.keyrook")
        credentials().use { c ->
            store.save(source, Vault(), c, parameters = testKdf)
            service(folder, BackupPolicy(1, 0), "2026-01-01T12:00:00Z").create(source, c)
            val result = service(folder, BackupPolicy(1, 0), "2026-01-02T12:00:00Z") {
                Files.delete(it)
                Files.delete(it)
            }.create(source, c)
            assertEquals(0, result.removed)
            assertEquals(0, result.notRemoved)
            assertTrue(result.rotationComplete)
        }
    }
}
