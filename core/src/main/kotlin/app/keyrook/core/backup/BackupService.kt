// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.backup

import app.keyrook.core.crypto.AuthenticationException
import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.InvalidVaultException
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.format.VaultHeader
import app.keyrook.core.storage.FileStamp
import app.keyrook.core.storage.PrivateFiles
import app.keyrook.core.storage.SaveResult
import app.keyrook.core.storage.VaultConflictException
import app.keyrook.core.storage.VaultStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class BackupPolicy(val latest: Int = 30, val daily: Int = 30) {
    init { require(latest in 1..1000 && daily in 0..3660) }

    /** Upper bound of managed backups that survive a rotation: the latest versions plus one per retained day. */
    val maximumKept: Int get() = latest + daily
}

/** The date is filesystem metadata, not an authenticated creation time. Counts include trash. */
class BackupPreview internal constructor(
    val vaultId: String, val revision: Long, val entries: Int, val modifiedAt: Instant,
    internal val digest: ByteArray,
)
/**
 * [removed] old backups were deleted by rotation. Rotation is best effort after the new backup is verified:
 * [notRemoved] backups selected for deletion could not be deleted, and [rotationComplete] is false when some could
 * not be deleted or the folder could not be listed for rotation. The new backup at [path] is valid either way.
 */
data class BackupResult(val path: Path, val removed: Int, val notRemoved: Int = 0,
                        val rotationComplete: Boolean = notRemoved == 0)

/** Copies authenticated ciphertext. Backup names disclose only a random vault ID, revision and time. */
class BackupService internal constructor(
    internal val directory: Path,
    private val policy: BackupPolicy,
    private val clock: Clock,
    private val codec: VaultCodec,
    private val remove: (Path) -> Unit = { Files.delete(it) },
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
                    // CREATE_NEW never replaces a conflicting target, even when another process races us.
                    PrivateFiles.createNew(target).use { output ->
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
                    return rotate(root, vault.id, target)
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

    /**
     * Restores only to an absent file; the source and any existing target remain untouched. The restored file is an
     * independent vault with a new ID at revision zero, so its backups never mix with those of the original.
     */
    fun restoreToNew(backup: Path, target: Path, credentials: Credentials, preview: BackupPreview,
                     allowExpensive: Boolean = false): SaveResult {
        val bytes = read(backup)
        if (!MessageDigest.isEqual(hash(bytes), preview.digest)) throw VaultConflictException()
        val destination = safePath(target)
        return authenticate(bytes, credentials, allowExpensive).use { vault ->
            VaultStore().save(destination, vault.independentCopy(), credentials,
                parameters = VaultHeader.parse(bytes, allowExpensive).kdf, allowExpensive = allowExpensive)
        }
    }

    /**
     * Deletes managed backups outside the retention after [newest] was verified. Failures only reduce what is
     * removed: they are counted in the result and never undo the new backup or fail the caller's save.
     */
    private fun rotate(root: Path, vaultId: String, newest: Path): BackupResult {
        val pattern = backupNamePattern(vaultId)
        val candidates = try {
            Files.newDirectoryStream(root).use { stream ->
                stream.mapNotNull { path ->
                    val match = pattern.matchEntire(path.fileName.toString()) ?: return@mapNotNull null
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
                    val time = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                    path to Instant.ofEpochMilli(time)
                }.sortedWith(compareByDescending<Pair<Path, Instant>> { it.second }
                    .thenByDescending { it.first == newest }.thenByDescending { it.first.fileName.toString() })
            }
        } catch (_: IOException) { return BackupResult(newest, 0, rotationComplete = false) }
          catch (_: DirectoryIteratorException) { return BackupResult(newest, 0, rotationComplete = false) }
          catch (_: SecurityException) { return BackupResult(newest, 0, rotationComplete = false) }
        val keep = candidates.take(policy.latest).map { it.first }.toMutableSet()
        keep.add(newest)
        candidates.groupBy { it.second.atZone(ZoneOffset.UTC).toLocalDate() }.values.take(policy.daily)
            .forEach { keep.add(it.first().first) }
        var removed = 0
        var notRemoved = 0
        for ((path) in candidates) {
            if (path in keep || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
            try {
                remove(path)
                removed++
            } catch (_: NoSuchFileException) {
                // Already gone, for example removed by hand: nothing is left to rotate.
            } catch (_: IOException) { notRemoved++ }
              catch (_: SecurityException) { notRemoved++ }
        }
        return BackupResult(newest, removed, notRemoved)
    }

    private fun read(path: Path): ByteArray = readBackupFile(path)

    private fun safePath(path: Path): Path = resolveWithoutFinalLink(path)

    /** Bad passwords and damaged ciphertext have the same content-free backup error. */
    private fun authenticate(bytes: ByteArray, credentials: Credentials, allowExpensive: Boolean) =
        try { codec.decrypt(bytes, credentials, allowExpensive) }
        catch (_: AuthenticationException) { throw InvalidVaultException() }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** Group 1 is the file-system time in epoch milliseconds, group 2 the revision; neither is authenticated. */
internal fun backupNamePattern(vaultId: String) =
    Regex("${Regex.escape(vaultId)}_([0-9]{1,19})_([0-9]{1,19})_([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.keyrook\\.bak")

/**
 * Number of regular files in [directory] that match this vault's managed backup naming pattern, i.e. the files
 * that rotation may delete. Symbolic links and unrelated files are not counted. Throws [IOException] when the
 * folder is missing, unreadable or itself a symbolic link. Nothing is created, written or decrypted.
 */
fun countManagedBackups(directory: Path, vaultId: String): Int {
    val root = resolveWithoutFinalLink(directory)
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IOException("Backup folder must exist")
    val pattern = backupNamePattern(vaultId)
    return Files.newDirectoryStream(root).use { stream ->
        stream.count { pattern.matchEntire(it.fileName.toString()) != null && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
    }
}

/** Reads a bounded regular file below canonical parent directories; a symbolic link as the file itself is refused. */
internal fun readBackupFile(path: Path): ByteArray {
    val resolved = resolveWithoutFinalLink(path)
    if (!Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) throw IOException("Backup input must be a regular file")
    return PrivateFiles.readBounded(resolved, 92..VaultCodec.MAX_FILE_BYTES.toLong(),
        { InvalidVaultException() }, { InvalidVaultException() })
}

/**
 * Resolves [path] the way [VaultStore] resolves vault files: parent directories are canonicalized, so a symbolic
 * link above the final component (for example macOS `/var` to `/private/var`) is accepted, while a final component
 * that is itself a symbolic link is refused and never followed. Missing parent directories raise [IOException].
 * Backups, the integrity check and key-file generation share it so all user-selected paths behave alike.
 */
fun resolveWithoutFinalLink(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    val name = absolute.fileName ?: return absolute
    val resolved = absolute.parent.toRealPath().resolve(name)
    if (Files.isSymbolicLink(resolved)) throw IOException("Symbolic links are not accepted as the final path component")
    return resolved
}
