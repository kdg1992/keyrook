// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.crypto.AuthenticationException
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import app.keyrook.core.service.*
import app.keyrook.core.storage.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SessionTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("session.keyrook")

    @Test fun `create edit lock and reopen preserve data with independent snapshots`() {
        credentials().use { c -> VaultSession().use { session -> sampleVault().use { original ->
            session.state shouldBe SessionState.LOCKED
            session.create(path, original, c, testKdf)
            original.close(); c.close()
            session.snapshot().use { snapshot ->
                session.save(snapshot.copy(entries = snapshot.entries.map { it.copy(title = "edited") }))
            }
            session.snapshot().use { independent ->
                session.lock()
                independent.entries.first().data.fields().first().value.useChars { assertTrue(it.isNotEmpty()) }
            }
            session.state shouldBe SessionState.LOCKED
            assertThrows(IllegalStateException::class.java) { session.snapshot() }
            credentials().use { reopened -> session.open(path, reopened) }
            session.snapshot().use { snapshot ->
                snapshot.revision shouldBe 1L
                snapshot.entries.first().title shouldBe "edited"
            }
        } } }
    }

    @Test fun `password change reencrypts and old credentials no longer open current file`() {
        credentials().use { old -> credentials("replacement-master", ByteArray(32) { 7 }).use { replacement ->
            VaultSession().use { session ->
                session.create(path, Vault(), old, testKdf)
                val oldBytes = Files.readAllBytes(path)
                session.changePassword(replacement)
                assertFalse(oldBytes.contentEquals(Files.readAllBytes(path)))
                assertThrows(AuthenticationException::class.java) { VaultStore().load(path, old) }
                VaultStore().load(path, replacement).use { it.vault.revision shouldBe 1L }
                session.snapshot().use { session.save(it) }
                VaultStore().load(path, replacement).use { it.vault.revision shouldBe 2L }
            }
        } }
    }

    @Test fun `failed password change retains old credentials and in-memory document`() {
        credentials().use { old -> credentials("replacement").use { replacement -> VaultSession().use { session ->
            session.create(path, Vault(), old, testKdf)
            val initial = Files.readAllBytes(path)
            Files.write(path, initial + byteArrayOf(0))
            assertThrows(VaultConflictException::class.java) { session.changePassword(replacement) }
            session.state shouldBe SessionState.ERROR
            session.snapshot().use { it.revision shouldBe 0L }
            Files.write(path, initial)
            session.snapshot().use { session.save(it) }
            session.state shouldBe SessionState.UNLOCKED
            VaultStore().load(path, old).close()
            assertThrows(AuthenticationException::class.java) { VaultStore().load(path, replacement) }
        } } }
    }

    @Test fun `failed open remains locked and cannot replace active session`() {
        credentials().use { good -> credentials("wrong").use { bad -> VaultSession().use { session ->
            VaultStore().save(path, Vault(), good, parameters = testKdf)
            assertThrows(AuthenticationException::class.java) { session.open(path, bad) }
            session.state shouldBe SessionState.LOCKED
            session.open(path, good)
            assertThrows(IllegalStateException::class.java) { session.open(path, good) }
        } } }
    }

    @Test fun `custom KDF settings survive open and save`() {
        val custom = KdfParameters(iterations = 2, parallelism = 2)
        credentials().use { c ->
            VaultStore().save(path, Vault(), c, parameters = custom)
            VaultSession().use { session ->
                session.open(path, c)
                session.snapshot().use { session.save(it) }
            }
            VaultStore().load(path, c).use { it.parameters shouldBe custom }
        }
    }

    @Test fun `closing vault erases notes current fields and historic secrets`() {
        val vault = sampleVault()
        val current = vault.entries.first().data.fields().first().value
        val historic = vault.entries.first().history.first().data.fields().first().value
        val notes = vault.entries.first().notes
        vault.close()
        listOf(current, historic, notes).forEach {
            assertThrows(IllegalStateException::class.java) { it.useChars { } }
        }
    }
}
