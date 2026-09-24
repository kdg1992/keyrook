// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.backup

import app.keyrook.core.crypto.AuthenticationException
import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.InvalidVaultException
import app.keyrook.core.crypto.ResourceApprovalRequired
import app.keyrook.core.format.VaultCodec
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant

/**
 * AES-GCM cannot tell a wrong key from modified ciphertext: a backup written before a password or key-file
 * change fails exactly like a tampered or truncated one. Both are reported as [AUTHENTICATION_FAILED], meaning
 * "cannot be authenticated with the current credentials", never as corrupt or intact.
 */
enum class IntegrityState {
    /** Authenticated and structurally valid; metadata matches the file name or the opened vault. */
    OK,
    /** Older credentials, or modified/truncated ciphertext. Cryptographically indistinguishable. */
    AUTHENTICATION_FAILED,
    /** Header, size bounds or authenticated content are invalid. */
    CORRUPT,
    /** Authentic, but vault ID or revision differs from the file name or the opened vault (renamed or replaced). */
    MISMATCH,
    /** Not a regular file, a symbolic link, vanished during the check, or an I/O error. */
    UNREADABLE,
    /** Key derivation parameters exceed the limits that need explicit approval; not decrypted. */
    APPROVAL_REQUIRED,
}

enum class IntegrityFileKind { VAULT, BACKUP }

enum class BackupFolderState { NOT_CONFIGURED, CHECKED, UNREADABLE }

/** Revision, entry count (including trash) and date are present only after successful authentication. */
data class IntegrityFileResult(
    val kind: IntegrityFileKind, val fileName: String, val state: IntegrityState,
    val revision: Long? = null, val entries: Int? = null, val modifiedAt: Instant? = null,
)

class IntegrityReport internal constructor(val files: List<IntegrityFileResult>, val backupFolder: BackupFolderState) {
    fun count(state: IntegrityState): Int = files.count { it.state == state }
    val intact: Boolean get() = backupFolder != BackupFolderState.UNREADABLE && files.all { it.state == IntegrityState.OK }
}

/**
 * Read-only: files are opened for reading only, never locked, created, renamed, rewritten or deleted.
 * Decrypted models are closed and ciphertext buffers cleared after each file. Failures carry no messages.
 */
class IntegrityCheck(private val codec: VaultCodec = VaultCodec()) {

    /** Checks [vault] and every managed backup of [vaultId] in [backupDirectory], newest backup first. */
    fun run(vault: Path, vaultId: String, expectedRevision: Long?, backupDirectory: Path?,
            credentials: Credentials, allowExpensive: Boolean = false): IntegrityReport {
        val results = mutableListOf(check(vault, IntegrityFileKind.VAULT, vaultId, expectedRevision, credentials, allowExpensive))
        if (backupDirectory == null) return IntegrityReport(results, BackupFolderState.NOT_CONFIGURED)
        val listed: List<Candidate>? = try { listBackups(backupDirectory, vaultId) }
            catch (_: IOException) { null } catch (_: SecurityException) { null }
        val backups = listed ?: return IntegrityReport(results, BackupFolderState.UNREADABLE)
        for (backup in backups) {
            results += check(backup.path, IntegrityFileKind.BACKUP, vaultId, backup.revision, credentials, allowExpensive)
        }
        return IntegrityReport(results, BackupFolderState.CHECKED)
    }

    private class Candidate(val path: Path, val time: Long, val revision: Long?)

    /**
     * Same folder resolution and name pattern as backup creation and rotation: parent directories are canonical,
     * a symbolically linked folder is refused, and linked entries are never followed but reported as unreadable.
     */
    private fun listBackups(directory: Path, vaultId: String): List<Candidate> {
        val root = resolveWithoutFinalLink(directory)
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw IOException("Backup folder must exist")
        val pattern = backupNamePattern(vaultId)
        return Files.newDirectoryStream(root).use { stream ->
            stream.mapNotNull { path ->
                val match = pattern.matchEntire(path.fileName.toString()) ?: return@mapNotNull null
                val time = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                Candidate(path, time, match.groupValues[2].toLongOrNull())
            }
        }.sortedWith(compareByDescending<Candidate> { it.time }.thenByDescending { it.path.fileName.toString() })
    }

    private fun check(path: Path, kind: IntegrityFileKind, expectedId: String, expectedRevision: Long?,
                      credentials: Credentials, allowExpensive: Boolean): IntegrityFileResult {
        val name = path.fileName?.toString().orEmpty()
        fun failed(state: IntegrityState) = IntegrityFileResult(kind, name, state)
        var bytes: ByteArray? = null
        return try {
            // Resolved like VaultStore: canonical parent directories, a linked file itself is refused.
            val resolved = resolveWithoutFinalLink(path)
            val content = readBackupFile(resolved)
            bytes = content
            val modified = Files.getLastModifiedTime(resolved, LinkOption.NOFOLLOW_LINKS).toInstant()
            codec.decrypt(content, credentials, allowExpensive).use { vault ->
                val matches = vault.id == expectedId && (expectedRevision == null || vault.revision == expectedRevision)
                IntegrityFileResult(kind, name, if (matches) IntegrityState.OK else IntegrityState.MISMATCH,
                    vault.revision, vault.entries.size, modified)
            }
        } catch (_: AuthenticationException) { failed(IntegrityState.AUTHENTICATION_FAILED) }
          catch (_: ResourceApprovalRequired) { failed(IntegrityState.APPROVAL_REQUIRED) }
          catch (_: InvalidVaultException) { failed(IntegrityState.CORRUPT) }
          catch (_: IOException) { failed(IntegrityState.UNREADABLE) }
          catch (_: SecurityException) { failed(IntegrityState.UNREADABLE) }
          finally { bytes?.fill(0) }
    }
}
