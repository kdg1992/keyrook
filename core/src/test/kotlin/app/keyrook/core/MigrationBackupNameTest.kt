// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.backup.migrationBackupName
import app.keyrook.core.backup.preserveBeforeMigration
import app.keyrook.core.service.VaultSession
import app.keyrook.core.storage.MAX_FILE_NAME_BYTES
import app.keyrook.core.storage.VaultStore
import app.keyrook.core.storage.boundedFileName
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Names of the copy kept before a schema migration stay within the file-name limit of common file systems. */
class MigrationBackupNameTest {
    @TempDir lateinit var directory: Path

    private fun String.bytes() = toByteArray(Charsets.UTF_8).size
    private val revisionMax = Long.MAX_VALUE
    private val unique = UUID.randomUUID().toString()

    @Test fun `short names are unchanged`() {
        migrationBackupName("company.keyrook", 1, 12) shouldBe "company.keyrook.schema-v1-r12.keyrook.bak"
        migrationBackupName("company.keyrook", 1, 12, unique) shouldBe "company.keyrook.schema-v1-r12-$unique.keyrook.bak"
    }

    @Test fun `long single and multibyte names are cut at character boundaries and stay distinct`() {
        val names = listOf("a".repeat(242) + ".keyrook", "ä".repeat(121) + ".keyrook", "🗝".repeat(60) + ".keyrook",
            "x" + "ä".repeat(124), "b".repeat(255))
        for (name in names) {
            for (suffix in listOf(null, unique)) {
                val result = migrationBackupName(name, Int.MAX_VALUE, revisionMax, suffix)
                assertTrue(result.bytes() <= MAX_FILE_NAME_BYTES) { "${result.bytes()} bytes" }
                assertTrue(result.endsWith(".schema-v${Int.MAX_VALUE}-r$revisionMax${suffix?.let { "-$it" }.orEmpty()}.keyrook.bak"))
                // Recognisable: starts with the vault name up to the cut; whole characters only.
                val stem = result.substringBefore("~")
                assertTrue(name.startsWith(stem) && stem.length > 50)
                assertFalse(stem.last().isHighSurrogate())
                // Deterministic, so an existing copy is found again.
                migrationBackupName(name, Int.MAX_VALUE, revisionMax, suffix) shouldBe result
            }
        }
        // Long names with a common beginning still get different copies.
        val first = migrationBackupName("c".repeat(250) + "1", 1, 2)
        val second = migrationBackupName("c".repeat(250) + "2", 1, 2)
        assertNotEquals(first, second)
        assertTrue(first.bytes() <= MAX_FILE_NAME_BYTES && second.bytes() <= MAX_FILE_NAME_BYTES)
    }

    @Test fun `the lock of a vault keeps its name unless that is too long`() {
        boundedFileName("company.keyrook", ".lock", ".") shouldBe ".company.keyrook.lock"
        val long = "ä".repeat(125)
        val lock = boundedFileName(long, ".lock", ".")
        assertTrue(lock.startsWith(".ää") && lock.endsWith(".lock") && lock.bytes() <= MAX_FILE_NAME_BYTES)
    }

    @Test fun `a vault with a 250 byte name keeps a migration copy and reuses it`() {
        val root = directory.toRealPath()
        for (name in listOf("a".repeat(242) + ".keyrook", "ä".repeat(121) + ".keyrook")) {
            name.bytes() shouldBe 250
            // Multibyte file names need a UTF-8 file-name encoding (sun.jnu.encoding); other platforms skip them.
            val path = runCatching { root.resolve(name) }.getOrNull() ?: continue
            val original = frozenV1Fixture()
            Files.write(path, original)
            credentials("fixture-password").use { c ->
                val stamp = VaultStore().load(path, c).use { it.stamp }
                val copy = preserveBeforeMigration(path, stamp, 1)
                assertTrue(copy.fileName.toString().bytes() <= MAX_FILE_NAME_BYTES)
                assertArrayEquals(original, Files.readAllBytes(copy))
                // An identical copy is reused; a different file under that name gets a unique, bounded name.
                preserveBeforeMigration(path, stamp, 1) shouldBe copy
                Files.write(copy, byteArrayOf(1, 2, 3))
                val other = preserveBeforeMigration(path, stamp, 1)
                assertNotEquals(copy, other)
                assertTrue(other.fileName.toString().bytes() <= MAX_FILE_NAME_BYTES)
                assertArrayEquals(original, Files.readAllBytes(other))
                Files.delete(copy)
                Files.delete(other)
                // The first save of the session keeps the copy and reports its name.
                VaultSession().use { session ->
                    session.open(path, c)
                    session.snapshot().use { session.save(it) }
                    val kept = session.migrationBackup()!!
                    assertTrue(kept.bytes() <= MAX_FILE_NAME_BYTES)
                    assertArrayEquals(original, Files.readAllBytes(root.resolve(kept)))
                }
            }
        }
    }
}
