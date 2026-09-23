// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.crypto.InvalidVaultException
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.model.Vault
import app.keyrook.core.storage.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

class StorageTest {
    @TempDir lateinit var directory: Path
    private val store = VaultStore()
    private val path get() = directory.resolve("vault.keyrook")

    @Test fun `create load and update advance the persisted revision`() {
        credentials().use { c -> sampleVault().use { original ->
            val created = store.save(path, original, c, parameters = testKdf)
            val updated = original.copy(revision = 1)
            store.save(path, updated, c, created.stamp, testKdf)
            store.load(path, c).use { loaded ->
                loaded.vault.revision shouldBe 1L
                loaded.vault.entries.size shouldBe 8
                loaded.parameters shouldBe testKdf
            }
            Files.size(directory.resolve(".vault.keyrook.lock")) shouldBe 0L
            assertNoTemps()
        } }
    }

    @Test fun `creation never overwrites an existing vault`() {
        credentials().use { c ->
            store.save(path, Vault(), c, parameters = testKdf)
            val original = Files.readAllBytes(path)
            assertThrows(VaultConflictException::class.java) { store.save(path, Vault(), c, parameters = testKdf) }
            assertArrayEquals(original, Files.readAllBytes(path))
        }
    }

    @Test fun `stale stamp foreign vault and skipped revisions are rejected`() {
        credentials().use { c ->
            val initial = Vault()
            val stamp = store.save(path, initial, c, parameters = testKdf).stamp
            assertThrows(VaultConflictException::class.java) { store.save(path, initial.copy(revision = 2), c, stamp, testKdf) }
            assertThrows(VaultConflictException::class.java) { store.save(path, Vault(revision = 1), c, stamp, testKdf) }
            store.save(path, initial.copy(revision = 1), c, stamp, testKdf)
            val original = Files.readAllBytes(path)
            assertThrows(VaultConflictException::class.java) { store.save(path, initial.copy(revision = 1), c, stamp, testKdf) }
            assertArrayEquals(original, Files.readAllBytes(path))
        }
    }

    @ParameterizedTest @ValueSource(strings = ["force", "verify", "move", "unsupported"])
    fun `failure before commit preserves original and cleans temporary ciphertext`(failure: String) {
        credentials().use { c ->
            val original = Vault()
            val stamp = store.save(path, original, c, parameters = testKdf).stamp
            val before = Files.readAllBytes(path)
            val failing = VaultStore(VaultCodec(), object : StorageOperations {
                override fun force(channel: FileChannel) {
                    if (failure == "force") throw IOException("simulated disk failure")
                    super.force(channel)
                }
                override fun beforeVerification(temp: Path) {
                    if (failure == "verify") Files.write(temp, byteArrayOf(0))
                }
                override fun move(source: Path, target: Path) {
                    if (failure == "unsupported") throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "test")
                    throw IOException("simulated replacement failure")
                }
            })
            val error = assertThrows(Exception::class.java) { failing.save(path, original.copy(revision = 1), c, stamp, testKdf) }
            if (failure == "unsupported") assertInstanceOf(AtomicSaveUnavailableException::class.java, error)
            assertArrayEquals(before, Files.readAllBytes(path))
            store.load(path, c).close()
            assertNoTemps()
        }
    }

    @Test fun `external edit during write is detected again before replacement`() {
        credentials().use { c ->
            val initial = Vault()
            val stamp = store.save(path, initial, c, parameters = testKdf).stamp
            val external = byteArrayOf(1, 2, 3)
            val racing = VaultStore(VaultCodec(), object : StorageOperations {
                override fun beforeVerification(temp: Path) { Files.write(path, external) }
            })
            assertThrows(InvalidVaultException::class.java) { racing.save(path, initial.copy(revision = 1), c, stamp, testKdf) }
            assertArrayEquals(external, Files.readAllBytes(path))
            assertNoTemps()
        }
    }

    @Test fun `concurrent cooperating writer is refused while lock is held`() {
        credentials().use { c ->
            val lockPath = directory.resolve(".vault.keyrook.lock")
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    assertThrows(VaultConflictException::class.java) { store.save(path, Vault(), c, parameters = testKdf) }
                    assertFalse(Files.exists(path))
                }
            }
        }
    }

    @Test fun `oversized and nonregular input refused before allocating ciphertext`() {
        credentials().use { c ->
            Files.createDirectory(path)
            assertThrows(IOException::class.java) { store.load(path, c) }
            Files.delete(path)
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                channel.position(VaultCodec.MAX_FILE_BYTES.toLong())
                channel.write(ByteBuffer.wrap(byteArrayOf(0)))
            }
            assertThrows(InvalidVaultException::class.java) { store.load(path, c) }
        }
    }

    @Test fun `stored files restrict access on supported filesystems`() {
        credentials().use { c -> store.save(path, Vault(), c, parameters = testKdf) }
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java)?.let {
            it.readAttributes().permissions() shouldBe PosixFilePermissions.fromString("rw-------")
        }
        Files.getFileAttributeView(path, AclFileAttributeView::class.java)?.let {
            it.acl.size shouldBe 1
            it.acl.single().principal() shouldBe it.owner
            assertTrue(AclEntryPermission.READ_DATA in it.acl.single().permissions())
        }
    }

    private fun assertNoTemps() {
        Files.list(directory).use { stream -> assertFalse(stream.anyMatch { it.fileName.toString().endsWith(".tmp") }) }
    }
}
