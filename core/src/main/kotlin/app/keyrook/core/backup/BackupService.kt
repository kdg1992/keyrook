// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.backup

import app.keyrook.core.crypto.AuthenticationException
import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.InvalidVaultException
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.format.VaultHeader
import app.keyrook.core.storage.FileStamp
import app.keyrook.core.storage.SaveResult
import app.keyrook.core.storage.VaultConflictException
import app.keyrook.core.storage.VaultStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class BackupPolicy(val latest: Int = 30, val daily: Int = 30) {
    init { require(latest in 1..1000 && daily in 0..3660) }
}

/** The date is filesystem metadata, not an authenticated creation time. Counts include trash. */
class BackupPreview internal constructor(
    val vaultId: String, val revision: Long, val entries: Int, val modifiedAt: Instant,
    internal val digest: ByteArray,
)
data class BackupResult(val path: Path, val removed: Int)

/** Copies authenticated ciphertext. Backup names disclose only a random vault ID, revision and time. */
class BackupService internal constructor(
    private val directory: Path,
    private val policy: BackupPolicy,
    private val clock: Clock,
    private val codec: VaultCodec,
) {
    constructor(directory: Path, policy: BackupPolicy = BackupPolicy(), clock: Clock = Clock.systemUTC()) :
        this(directory, policy, clock, VaultCodec())

    fun create(source: Path, credentials: Credentials, expected: FileStamp? = null,
               allowExpensive: Boolean = false): BackupResult {
        val bytes = read(source)
        authenticate(bytes, credentials, allowExpensive).use { vault ->
            if (expected != null && (!MessageDigest.isEqual(expected.digest, hash(bytes)) ||
                    vault.id != expected.vaultId || vault.revision != expected.revision)) throw VaultConflictException()
            val root = safePath(directory)
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IOException("Backup folder must exist")
            val lockPath = root.resolve(".keyrook-backup.lock")
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { lockChannel ->
                val lock = try { lockChannel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) throw VaultConflictException()
                lock.use {
                    val target = root.resolve("${vault.id}_${clock.instant().toEpochMilli()}_${vault.revision}_${UUID.randomUUID()}.keyrook.bak")
                    val attributes = if (Files.getFileAttributeView(root, PosixFileAttributeView::class.java) != null)
                        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
                    // CREATE_NEW never replaces a conflicting target, even when another process races us.
                    FileChannel.open(target, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), *attributes).use { output ->
                        try {
                            val buffer = ByteBuffer.wrap(bytes)
                            while (buffer.hasRemaining()) output.write(buffer)
                            output.force(true)
                        } catch (failure: Exception) {
                            output.close()
                            Files.deleteIfExists(target)
                            throw failure
                        }
                    }
                    try {
                        if (!MessageDigest.isEqual(bytes, read(target))) throw IOException("Backup verification failed")
                    } catch (failure: Exception) { Files.deleteIfExists(target); throw failure }
                    return BackupResult(target, rotate(root, vault.id, target))
                }
            }
        }
    }

    fun preview(backup: Path, credentials: Credentials, allowExpensive: Boolean = false): BackupPreview {
        val bytes = read(backup)
        return authenticate(bytes, credentials, allowExpensive).use {
            BackupPreview(it.id, it.revision, it.entries.size,
                Files.getLastModifiedTime(safePath(backup), LinkOption.NOFOLLOW_LINKS).toInstant(), hash(bytes))
        }
    }

    /** Restores only to an absent file; the source and any existing target remain untouched. */
    fun restoreToNew(backup: Path, target: Path, credentials: Credentials, preview: BackupPreview,
                     allowExpensive: Boolean = false): SaveResult {
        val bytes = read(backup)
        if (!MessageDigest.isEqual(hash(bytes), preview.digest)) throw VaultConflictException()
        val destination = safePath(target)
        return authenticate(bytes, credentials, allowExpensive).use { vault ->
            VaultStore().save(destination, vault.copy(revision = 0), credentials,
                parameters = VaultHeader.parse(bytes, allowExpensive).kdf, allowExpensive = allowExpensive)
        }
    }

    private fun rotate(root: Path, vaultId: String, newest: Path): Int {
        val pattern = Regex("${Regex.escape(vaultId)}_([0-9]{1,19})_([0-9]{1,19})_([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.keyrook\\.bak")
        val candidates = Files.newDirectoryStream(root).use { stream ->
            stream.mapNotNull { path ->
                val match = pattern.matchEntire(path.fileName.toString()) ?: return@mapNotNull null
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
                val time = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                path to Instant.ofEpochMilli(time)
            }.sortedWith(compareByDescending<Pair<Path, Instant>> { it.second }
                .thenByDescending { it.first == newest }.thenByDescending { it.first.fileName.toString() })
        }
        val keep = candidates.take(policy.latest).map { it.first }.toMutableSet()
        keep.add(newest)
        candidates.groupBy { it.second.atZone(ZoneOffset.UTC).toLocalDate() }.values.take(policy.daily)
            .forEach { keep.add(it.first().first) }
        var removed = 0
        for ((path) in candidates) {
            if (path !in keep && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(path)
                removed++
            }
        }
        return removed
    }

    private fun read(path: Path): ByteArray {
        val resolved = safePath(path)
        if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) throw IOException("Backup input must be a regular file")
        FileChannel.open(resolved, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val size = input.size()
            if (size !in 92..VaultCodec.MAX_FILE_BYTES.toLong()) throw InvalidVaultException()
            val bytes = ByteArray(size.toInt())
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) if (input.read(buffer) < 0) throw InvalidVaultException()
            if (input.read(ByteBuffer.allocate(1)) != -1) throw InvalidVaultException()
            return bytes
        }
    }

    private fun safePath(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var current = absolute.root
        for (part in absolute) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw IOException("Symbolic links are not accepted for backups")
        }
        return absolute
    }

    /** Bad passwords and damaged ciphertext have the same content-free backup error. */
    private fun authenticate(bytes: ByteArray, credentials: Credentials, allowExpensive: Boolean) =
        try { codec.decrypt(bytes, credentials, allowExpensive) }
        catch (_: AuthenticationException) { throw InvalidVaultException() }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
