// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.storage

import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.InvalidVaultException
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.format.VaultHeader
import app.keyrook.core.model.Vault
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

class VaultConflictException : IOException("Vault changed or is already in use")
class AtomicSaveUnavailableException : IOException("Atomic replacement is unavailable")

/** Opaque optimistic concurrency token; contains no plaintext values. */
class FileStamp internal constructor(internal val digest: ByteArray, internal val vaultId: String, internal val revision: Long)
class LoadedVault(val vault: Vault, val stamp: FileStamp, val parameters: KdfParameters) : AutoCloseable {
    override fun close() = vault.close()
}
enum class DirectoryDurability { FORCED, NOT_SUPPORTED }
data class SaveResult(val stamp: FileStamp, val directoryDurability: DirectoryDurability)

internal interface StorageOperations {
    fun force(channel: FileChannel) = channel.force(true)
    fun beforeVerification(temp: Path) {}
    fun move(source: Path, target: Path) {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
private object NioOperations : StorageOperations

/** Cooperating writers use a persistent, empty sidecar lock; it must not be deleted while in use. */
class VaultStore internal constructor(private val codec: VaultCodec, private val operations: StorageOperations) {
    constructor(codec: VaultCodec = VaultCodec()) : this(codec, NioOperations)

    fun load(path: Path, credentials: Credentials, allowExpensive: Boolean = false): LoadedVault {
        val bytes = readBounded(resolve(path))
        val vault = codec.decrypt(bytes, credentials, allowExpensive)
        return LoadedVault(vault, stamp(bytes, vault), VaultHeader.parse(bytes, allowExpensive).kdf)
    }

    /** expected=null creates a new vault; updates must advance revision by exactly one. */
    fun save(path: Path, vault: Vault, credentials: Credentials, expected: FileStamp? = null,
             parameters: KdfParameters = KdfParameters(), allowExpensive: Boolean = false): SaveResult {
        val target = resolve(path)
        val lockPath = target.resolveSibling(".${target.fileName}.lock")
        val attributes = if (Files.getFileAttributeView(target.parent, PosixFileAttributeView::class.java) != null)
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
        FileChannel.open(lockPath, setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), *attributes).use { channel ->
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) throw VaultConflictException()
            lock.use {
                checkExpected(target, expected)
                if (expected == null) {
                    if (vault.revision != 0L) throw VaultConflictException()
                } else if (vault.id != expected.vaultId || expected.revision == Long.MAX_VALUE ||
                    vault.revision != expected.revision + 1) throw VaultConflictException()
                val bytes = codec.encrypt(vault, credentials, parameters, allowExpensive)
                val temp = Files.createTempFile(target.parent, ".keyrook-", ".tmp", *attributes)
                try {
                    restrictAccess(temp)
                    FileChannel.open(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { output ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) output.write(buffer)
                        operations.force(output)
                    }
                    operations.beforeVerification(temp)
                    val readBack = readBounded(temp)
                    if (!MessageDigest.isEqual(bytes, readBack)) throw IOException("Vault write verification failed")
                    codec.decrypt(readBack, credentials, allowExpensive).use { verified ->
                        if (verified.id != vault.id || verified.revision != vault.revision) throw InvalidVaultException()
                    }
                    checkExpected(target, expected)
                    try { operations.move(temp, target) }
                    catch (_: AtomicMoveNotSupportedException) { throw AtomicSaveUnavailableException() }
                    // After commit, do not report failure as though the original still existed.
                    val durability = forceDirectory(target.parent)
                    return SaveResult(stamp(bytes, vault), durability)
                } finally { Files.deleteIfExists(temp) }
            }
        }
    }

    private fun checkExpected(path: Path, expected: FileStamp?) {
        if (expected == null) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw VaultConflictException()
        } else {
            val actual = try { hash(readBounded(path)) } catch (_: NoSuchFileException) { throw VaultConflictException() }
            if (!MessageDigest.isEqual(expected.digest, actual)) throw VaultConflictException()
        }
    }

    private fun resolve(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        require(absolute.fileName != null) { "Vault path must name a file" }
        return absolute.parent.toRealPath().resolve(absolute.fileName)
    }

    private fun restrictAccess(path: Path) {
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) ?: return
        acl.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.owner)
            .setPermissions(AclEntryPermission.entries.toSet()).build())
    }

    private fun forceDirectory(path: Path): DirectoryDurability = try {
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        DirectoryDurability.FORCED
    } catch (_: IOException) { DirectoryDurability.NOT_SUPPORTED }
      catch (_: UnsupportedOperationException) { DirectoryDurability.NOT_SUPPORTED }

    private fun stamp(bytes: ByteArray, vault: Vault) = FileStamp(hash(bytes), vault.id, vault.revision)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun readBounded(path: Path): ByteArray {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw NoSuchFileException(path.toString())
            throw IOException("Vault must be a regular file without symbolic links")
        }
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size !in 92..VaultCodec.MAX_FILE_BYTES.toLong()) throw InvalidVaultException()
            val bytes = ByteArray(size.toInt())
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw InvalidVaultException()
            if (channel.read(ByteBuffer.allocate(1)) != -1) throw InvalidVaultException()
            return bytes
        }
    }
}
