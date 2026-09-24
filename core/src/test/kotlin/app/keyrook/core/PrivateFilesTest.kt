// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.backup.BackupService
import app.keyrook.core.model.Vault
import app.keyrook.core.storage.PrivateFiles
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal

class PrivateFilesTest {
    @TempDir lateinit var directory: Path
    private val root get() = directory.toRealPath()

    private fun assertOwnerOnly(path: Path) {
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java)?.let {
            assertEquals(PosixFilePermissions.fromString("rw-------"), it.readAttributes().permissions())
        }
        Files.getFileAttributeView(path, AclFileAttributeView::class.java)?.let {
            assertEquals(1, it.acl.size)
            assertEquals(it.owner, it.acl.single().principal())
            assertEquals(AclEntryType.ALLOW, it.acl.single().type())
        }
    }

    @Test fun `new private files are owner only and never replace a file`() {
        val target = root.resolve("private.bin")
        PrivateFiles.createNew(target).use { it.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3))) }
        assertOwnerOnly(target)
        assertThrows(FileAlreadyExistsException::class.java) { PrivateFiles.createNew(target) }
        assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(target))
    }

    @Test fun `vault files and backups get the same owner only restriction`() {
        val source = root.resolve("vault.keyrook")
        val folder = Files.createDirectory(root.resolve("backups"))
        credentials().use { c ->
            app.keyrook.core.storage.VaultStore().save(source, Vault(), c, parameters = testKdf)
            val backup = BackupService(folder).create(source, c).path
            assertOwnerOnly(source)
            assertOwnerOnly(backup)
        }
    }

    @Test fun `acl restriction keeps a single full entry for the owner`() {
        val owner = UserPrincipal { "synthetic-owner" }
        val other = UserPrincipal { "synthetic-other" }
        val view = object : AclFileAttributeView {
            var entries = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(other)
                .setPermissions(AclEntryPermission.READ_DATA).build())
            override fun name() = "acl"
            override fun getOwner() = owner
            override fun setOwner(owner: UserPrincipal) = throw UnsupportedOperationException()
            override fun getAcl() = entries
            override fun setAcl(acl: List<AclEntry>) { entries = acl }
        }
        PrivateFiles.restrictToOwner(view)
        val entry = view.entries.single()
        assertEquals(owner, entry.principal())
        assertEquals(AclEntryType.ALLOW, entry.type())
        assertEquals(AclEntryPermission.entries.toSet(), entry.permissions())
    }

    @Test fun `windows files carry only the owner entry`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows", ignoreCase = true))
        val target = root.resolve("windows.bin")
        PrivateFiles.createNew(target).close()
        val acl = Files.getFileAttributeView(target, AclFileAttributeView::class.java)!!
        assertEquals(listOf(acl.owner), acl.acl.map { it.principal() })
    }

    @Test fun `bounded read enforces its size range and detects a changed file`() {
        val source = root.resolve("source.bin")
        Files.write(source, byteArrayOf(1, 2, 3))
        val size = { IllegalArgumentException("size") }
        val changed = { IOException("changed") }
        assertArrayEquals(byteArrayOf(1, 2, 3), PrivateFiles.readBounded(source, 0L..3L, size, changed))
        assertThrows(IllegalArgumentException::class.java) { PrivateFiles.readBounded(source, 0L..2L, size, changed) }
        assertThrows(IllegalArgumentException::class.java) { PrivateFiles.readBounded(source, 4L..9L, size, changed) }
        val buffers = mutableListOf<ByteArray>()
        assertThrows(IOException::class.java) {
            PrivateFiles.readBounded(source, 0L..3L, size, changed) { channel, buffer ->
                buffers += buffer.array()
                if (buffers.size == 1) channel.read(buffer) else { buffer.put(9); 1 }
            }
        }
        assertEquals(2, buffers.size)
        assertTrue(buffers.all { bytes -> bytes.all { it == 0.toByte() } })
    }
}
