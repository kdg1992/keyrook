// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.backup.BackupService
import app.keyrook.core.crypto.*
import app.keyrook.core.model.Vault
import app.keyrook.core.service.VaultSession
import app.keyrook.core.storage.VaultStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CredentialReconfigurationTest {
    @TempDir lateinit var directory: Path

    @Test fun `KDF change preserves both factors backs up old parameters and persists new header`() {
        val root = directory.toRealPath()
        val path = root.resolve("vault.keyrook")
        val backups = Files.createDirectory(root.resolve("backups"))
        val key = ByteArray(32) { it.toByte() }
        credential(key).use { credentials -> VaultSession().use { session ->
            Vault().use { session.create(path, it, credentials, KdfParameters(iterations = 1)) }
            session.configureBackups(BackupService(backups))
            val next = KdfParameters(iterations = 2, parallelism = 2)
            session.changeKdf(next)
            assertEquals(next, session.kdfParameters())
            VaultStore().load(path, credentials).use {
                assertEquals(next, it.parameters)
                assertEquals(1L, it.vault.revision)
            }
            val backup = Files.list(backups).use { it.filter { file -> file.toString().endsWith(".keyrook.bak") }.findFirst().orElseThrow() }
            VaultStore().load(backup, credentials).use { assertEquals(1, it.parameters.iterations) }
            credential(null).use { missing -> assertThrows(AuthenticationException::class.java) { VaultStore().load(path, missing) } }
            val before = Files.readAllBytes(path)
            assertThrows(ResourceApprovalRequired::class.java) { session.changeKdf(KdfParameters(iterations = 6)) }
            assertArrayEquals(before, Files.readAllBytes(path))
            session.lock()
            assertThrows(IllegalStateException::class.java) { session.kdfParameters() }
            session.open(path, credentials)
            assertEquals(next, session.kdfParameters())
        } }
    }

    @Test fun `adding replacing and removing second factor preserves recoverable backups`() {
        val root = directory.toRealPath()
        val path = root.resolve("vault.keyrook")
        val backups = Files.createDirectory(root.resolve("backups"))
        credential(null).use { passwordOnly -> credential(ByteArray(32) { 1 }).use { first ->
            credential(ByteArray(32) { 2 }).use { second -> VaultSession().use { session ->
                Vault().use { session.create(path, it, passwordOnly, KdfParameters(iterations = 1)) }
                session.configureBackups(BackupService(backups))
                session.changePassword(first)
                assertThrows(AuthenticationException::class.java) { VaultStore().load(path, passwordOnly) }
                session.changePassword(second)
                assertThrows(AuthenticationException::class.java) { VaultStore().load(path, first) }
                session.changePassword(passwordOnly)
                VaultStore().load(path, passwordOnly).use { assertEquals(3L, it.vault.revision) }
                assertThrows(AuthenticationException::class.java) { VaultStore().load(path, second) }
                val files = Files.list(backups).use { it.filter { file -> file.toString().endsWith(".keyrook.bak") }.toList() }
                assertEquals(3, files.size)
                listOf(passwordOnly, first, second).forEachIndexed { index, credentials ->
                    val backup = files.single { it.fileName.toString().split('_')[2] == index.toString() }
                    VaultStore().load(backup, credentials).use { assertEquals(index.toLong(), it.vault.revision) }
                }
            } }
        } }
    }

    private fun credential(key: ByteArray?): Credentials =
        Secret("synthetic factor change password".toCharArray()).use { Credentials(it, key) }
}
