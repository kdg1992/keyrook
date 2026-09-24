// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.backup

import app.keyrook.core.storage.FileStamp
import app.keyrook.core.storage.PrivateFiles
import app.keyrook.core.storage.VaultConflictException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/**
 * Keeps the unchanged encrypted bytes of a vault file that stores an older schema, before the first save rewrites it
 * in the current schema. The copy is written next to the vault as `<file>.schema-v<version>-r<revision>.keyrook.bak`
 * (with a random suffix if a different file already has that name), independent of any configured backup folder. Its
 * name does not match the managed backup pattern, so rotation never deletes it. The bytes must still be the ones the
 * session authenticated ([expected]); otherwise [VaultConflictException] is raised and nothing is written. An
 * identical existing copy is reused. Returns the copy's path.
 */
internal fun preserveBeforeMigration(vaultPath: Path, expected: FileStamp, storedSchemaVersion: Int): Path {
    val source = resolveWithoutFinalLink(vaultPath)
    val bytes = readBackupFile(source)
    try {
        if (!MessageDigest.isEqual(expected.digest, MessageDigest.getInstance("SHA-256").digest(bytes)))
            throw VaultConflictException()
        val base = "${source.fileName}.schema-v$storedSchemaVersion-r${expected.revision}"
        val existing = source.resolveSibling("$base.keyrook.bak")
        if (Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS) &&
            runCatching { readBackupFile(existing) }.getOrNull()?.let { copy ->
                MessageDigest.isEqual(copy, bytes).also { copy.fill(0) }
            } == true) return existing
        val target = if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS))
            source.resolveSibling("$base-${UUID.randomUUID()}.keyrook.bak") else existing
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
        return target
    } finally { bytes.fill(0) }
}
