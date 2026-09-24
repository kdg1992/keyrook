// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.crypto.AuthenticationException
import app.keyrook.core.model.Vault
import app.keyrook.core.storage.VaultConflictException
import app.keyrook.core.storage.VaultRepository
import app.keyrook.core.storage.VaultStore
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Exercises the file store only through the repository contract that later backends must also meet. */
class VaultRepositoryContractTest {
    @TempDir lateinit var directory: Path
    private val repository: VaultRepository = VaultStore()
    private val path get() = directory.resolve("contract.keyrook")

    @Test fun `load returns the saved revision and a stamp that permits exactly the next revision`() {
        credentials().use { c ->
            val initial = Vault()
            val created = repository.save(path, initial, c, parameters = testKdf)
            repository.load(path, c).use { loaded ->
                loaded.vault.id shouldBe initial.id
                loaded.vault.revision shouldBe 0L
                val updated = repository.save(path, loaded.vault.copy(revision = 1), c, loaded.stamp, testKdf)
                repository.load(path, c).use { it.vault.revision shouldBe 1L }
                assertThrows(VaultConflictException::class.java) {
                    repository.save(path, loaded.vault.copy(revision = 1), c, created.stamp, testKdf)
                }
                repository.save(path, loaded.vault.copy(revision = 2), c, updated.stamp, testKdf)
            }
            repository.load(path, c).use { it.vault.revision shouldBe 2L }
        }
    }

    @Test fun `stale stamps are rejected without changing the stored version`() {
        credentials().use { c ->
            val initial = Vault()
            val first = repository.save(path, initial, c, parameters = testKdf).stamp
            repository.save(path, initial.copy(revision = 1), c, first, testKdf)
            val stored = Files.readAllBytes(path)
            assertThrows(VaultConflictException::class.java) {
                repository.save(path, initial.copy(revision = 1), c, first, testKdf)
            }
            assertThrows(VaultConflictException::class.java) {
                repository.save(path, initial.copy(revision = 2), c, first, testKdf)
            }
            assertArrayEquals(stored, Files.readAllBytes(path))
        }
    }

    @Test fun `updates must keep the vault id and advance the revision by exactly one`() {
        credentials().use { c ->
            val initial = Vault()
            val stamp = repository.save(path, initial, c, parameters = testKdf).stamp
            val stored = Files.readAllBytes(path)
            listOf(initial, initial.copy(revision = 2), Vault(revision = 1)).forEach { candidate ->
                assertThrows(VaultConflictException::class.java) { repository.save(path, candidate, c, stamp, testKdf) }
            }
            assertArrayEquals(stored, Files.readAllBytes(path))
        }
    }

    @Test fun `creation requires revision zero and never replaces an existing vault`() {
        credentials().use { c ->
            assertThrows(VaultConflictException::class.java) { repository.save(path, Vault(revision = 1), c, parameters = testKdf) }
            Files.exists(path) shouldBe false
            repository.save(path, Vault(), c, parameters = testKdf)
            val stored = Files.readAllBytes(path)
            assertThrows(VaultConflictException::class.java) { repository.save(path, Vault(), c, parameters = testKdf) }
            assertArrayEquals(stored, Files.readAllBytes(path))
        }
    }

    @Test fun `stored bytes stay encrypted and require the original credentials`() {
        credentials().use { c ->
            sampleVault().use { repository.save(path, it, c, parameters = testKdf) }
            String(Files.readAllBytes(path), Charsets.ISO_8859_1).contains("SENTINEL") shouldBe false
            credentials("different test-only password").use { other ->
                assertThrows(AuthenticationException::class.java) { repository.load(path, other) }
            }
        }
    }
}
