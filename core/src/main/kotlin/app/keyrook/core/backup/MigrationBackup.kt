// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.backup

import app.keyrook.core.storage.FileStamp
import app.keyrook.core.storage.PrivateFiles
import app.keyrook.core.storage.VaultConflictException
import app.keyrook.core.storage.boundedFileName
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** The copy kept before a schema migration could not be written or verified; the save was not attempted. */
class MigrationBackupException(cause: Throwable) : IOException("Migration copy could not be written", cause)

/**
 * Name of the migration copy of the vault file [vaultName]: `<file>.schema-v<version>-r<revision>[-<unique>].keyrook.bak`,
 * at most [app.keyrook.core.storage.MAX_FILE_NAME_BYTES] UTF-8 bytes; a longer vault name is shortened recognisably
 * and always the same way (see [boundedFileName]), so a later save finds an existing copy again.
 */
internal fun migrationBackupName(vaultName: String, version: Int, revision: Long, unique: String? = null): String =
    boundedFileName(vaultName, ".schema-v$version-r$revision${unique?.let { "-$it" }.orEmpty()}.keyrook.bak")

/**
 * Keeps the unchanged encrypted bytes of a vault file that stores an older schema, before the first save rewrites it
 * in the current schema. The copy is written next to the vault as named by [migrationBackupName] (with a random suffix
 * if a different file already has that name), independent of any configured backup folder. Its name does not match
 * the managed backup pattern, so rotation never deletes it. The bytes must still be the ones the session authenticated
 * ([expected]); otherwise [VaultConflictException] is raised and nothing is written. An identical existing copy is
 * reused. A copy that cannot be written or verified raises [MigrationBackupException]. Returns the copy's path.
 */
internal fun preserveBeforeMigration(vaultPath: Path, expected: FileStamp, storedSchemaVersion: Int): Path {
    val source = resolveWithoutFinalLink(vaultPath)
    val bytes = readBackupFile(source)
    try {
        if (!MessageDigest.isEqual(expected.digest, MessageDigest.getInstance("SHA-256").digest(bytes)))
            throw VaultConflictException()
        val name = source.fileName.toString()
        val existing = source.resolveSibling(migrationBackupName(name, storedSchemaVersion, expected.revision))
        if (Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { readBackupFile(existing) }.getOrNull()?.let { copy ->
                MessageDigest.isEqual(copy, bytes).also { copy.fill(0) }
            } == true) return existing
        val target = if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) source.resolveSibling(
            migrationBackupName(name, storedSchemaVersion, expected.revision, UUID.randomUUID().toString())) else existing
        try { writeCopy(target, bytes) } catch (failure: Exception) { throw MigrationBackupException(failure) }
        return target
    } finally { bytes.fill(0) }
}

/** Writes [bytes] to the new owner-only file [target] and reads them back; a partial or differing copy is removed. */
private fun writeCopy(target: Path, bytes: ByteArray) {
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
    val written = try { readBackupFile(target) } catch (failure: Exception) { Files.deleteIfExists(target); throw failure }
    try {
        if (!MessageDigest.isEqual(bytes, written)) {
            Files.deleteIfExists(target)
            throw IOException("Migration backup verification failed")
        }
    } finally { written.fill(0) }
}
