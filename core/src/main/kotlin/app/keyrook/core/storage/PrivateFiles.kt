// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.storage

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * The one place that decides how vault files, backups, exports and settings are restricted to their owner:
 * POSIX mode 0600 at creation where POSIX permissions exist, and an ACL with a single owner entry where the
 * filesystem has ACLs (Windows). A filesystem that offers neither is refused, so no private file is ever written
 * with default permissions. Also provides the bounded read shared by every file Keyrook reads in full.
 */
object PrivateFiles {
    /** Creation attributes for a new file in [directory]: mode 0600 where POSIX permissions exist, otherwise none. */
    fun attributes(directory: Path): Array<FileAttribute<*>> =
        if (Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) != null)
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()

    /**
     * Replaces the ACL of the existing file [path] with one entry granting everything to its owner. Without an ACL
     * view the POSIX mode set at creation must apply; a filesystem with neither raises [IOException].
     */
    fun restrictToOwner(path: Path) {
        val acl = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
        if (acl != null) restrictToOwner(acl)
        else if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) == null)
            throw IOException("Private file permissions are unavailable")
    }

    internal fun restrictToOwner(acl: AclFileAttributeView) {
        acl.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.owner)
            .setPermissions(AclEntryPermission.entries.toSet()).build())
    }

    /**
     * Creates the absent file [path] for writing, never following or replacing an existing file or link, and restricts
     * it to its owner before any byte is written. If the restriction fails, the new empty file is removed again.
     */
    fun createNew(path: Path): FileChannel {
        val channel = FileChannel.open(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS), *attributes(path.toAbsolutePath().parent))
        try {
            restrictToOwner(path)
        } catch (failure: Exception) {
            try { channel.close() } catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
            try { Files.deleteIfExists(path) } catch (deleteFailure: Exception) { failure.addSuppressed(deleteFailure) }
            throw failure
        }
        return channel
    }

    /**
     * Reads the whole file [path] without following a final symbolic link. A size outside [sizes] raises
     * [sizeFailure]; a file that ends early or grows while it is read raises [changed]. Callers check the file
     * type first. Owned buffers are cleared when reading fails. [read] is a test seam.
     */
    fun readBounded(path: Path, sizes: LongRange, sizeFailure: () -> Exception, changed: () -> Exception,
                    read: (FileChannel, ByteBuffer) -> Int = { channel, buffer -> channel.read(buffer) }): ByteArray {
        var owned: ByteArray? = null
        try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                val size = input.size()
                if (size !in sizes) throw sizeFailure()
                val bytes = ByteArray(size.toInt()).also { owned = it }
                val extra = ByteBuffer.allocate(1)
                try {
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) if (read(input, buffer) < 0) throw changed()
                    if (read(input, extra) != -1) throw changed()
                    return bytes
                } finally { extra.array().fill(0) }
            }
        } catch (failure: Exception) { owned?.fill(0); throw failure }
    }
}
