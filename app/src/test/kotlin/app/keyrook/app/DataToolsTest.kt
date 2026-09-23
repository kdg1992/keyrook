// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

class DataToolsTest {
    @TempDir lateinit var directory: Path

    @Test fun `plaintext export restricts permissions and never replaces a file`() {
        val target = directory.resolve("export.json")
        val bytes = "synthetic test secret".toByteArray()
        writePrivateNew(target, bytes)
        assertArrayEquals(bytes, Files.readAllBytes(target))
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { writePrivateNew(target, byteArrayOf(1)) }
        assertArrayEquals(bytes, Files.readAllBytes(target))
        Files.getFileAttributeView(target, PosixFileAttributeView::class.java)?.let {
            assertEquals(PosixFilePermissions.fromString("rw-------"), it.readAttributes().permissions())
        }
        Files.getFileAttributeView(target, AclFileAttributeView::class.java)?.let {
            assertEquals(1, it.acl.size)
            assertEquals(it.owner, it.acl.single().principal())
            assertTrue(AclEntryPermission.READ_DATA in it.acl.single().permissions())
        }
    }

    @Test fun `failed export removes partial plaintext and preserves caller owned buffer`() {
        val target = directory.resolve("export.json")
        val bytes = "synthetic test secret".toByteArray()
        val original = bytes.copyOf()
        assertThrows(IOException::class.java) {
            writePrivateNew(target, bytes, object : TransferIo {
                override fun force(channel: FileChannel) { throw IOException("Simulated storage failure") }
            })
        }
        assertFalse(Files.exists(target))
        assertArrayEquals(original, bytes)
    }

    @Test fun `input bounds are checked before reading and directories are rejected`() {
        val source = directory.resolve("source")
        Files.write(source, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), readTransfer(source, 3))
        assertThrows(IllegalArgumentException::class.java) { readTransfer(source, 2) }
        assertThrows(IllegalArgumentException::class.java) { readTransfer(directory) }
    }

    @Test fun `failed import erases partially read buffer`() {
        val source = directory.resolve("source")
        Files.write(source, "synthetic test secret".toByteArray())
        var captured: ByteArray? = null
        assertThrows(IOException::class.java) {
            readTransfer(source, operations = object : TransferIo {
                override fun read(channel: FileChannel, buffer: ByteBuffer): Int {
                    captured = buffer.array()
                    channel.read(buffer)
                    throw IOException("Simulated read failure")
                }
            })
        }
        assertNotNull(captured)
        assertTrue(captured!!.all { it == 0.toByte() })
    }

    @Test fun `growing import is rejected and both buffers erased`() {
        val source = directory.resolve("source")
        Files.write(source, byteArrayOf(1, 2, 3))
        val buffers = mutableListOf<ByteArray>()
        assertThrows(IOException::class.java) {
            readTransfer(source, operations = object : TransferIo {
                override fun read(channel: FileChannel, buffer: ByteBuffer): Int {
                    buffers += buffer.array()
                    if (buffers.size == 1) return channel.read(buffer)
                    buffer.put(9)
                    return 1
                }
            })
        }
        assertEquals(2, buffers.size)
        assertTrue(buffers.all { bytes -> bytes.all { it == 0.toByte() } })
    }
}
