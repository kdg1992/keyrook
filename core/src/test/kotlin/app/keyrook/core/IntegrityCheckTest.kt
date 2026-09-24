// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.backup.BackupFolderState
import app.keyrook.core.backup.BackupService
import app.keyrook.core.backup.IntegrityCheck
import app.keyrook.core.backup.IntegrityFileKind
import app.keyrook.core.backup.IntegrityReport
import app.keyrook.core.backup.IntegrityState
import app.keyrook.core.crypto.Credentials
import app.keyrook.core.model.Vault
import app.keyrook.core.service.VaultSession
import app.keyrook.core.storage.VaultStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class IntegrityCheckTest {
    @TempDir lateinit var directory: Path
    private val root get() = directory.toRealPath()
    private val source get() = root.resolve("vault.keyrook")
    private val store = VaultStore()

    private fun folder() = Files.createDirectory(root.resolve("backups"))
    private fun service(folder: Path, time: String) =
        BackupService(folder, clock = Clock.fixed(Instant.parse(time), ZoneOffset.UTC))

    /** Saves a sample vault and two backups; returns the vault ID and backups, oldest first. */
    private fun prepare(folder: Path, c: Credentials): Pair<String, List<Path>> = sampleVault().use { vault ->
        store.save(source, vault, c, parameters = testKdf)
        val first = service(folder, "2026-01-01T12:00:00Z").create(source, c).path
        val second = service(folder, "2026-01-02T12:00:00Z").create(source, c).path
        vault.id to listOf(first, second)
    }

    private fun check(folder: Path?, id: String, c: Credentials, revision: Long? = 0): IntegrityReport =
        IntegrityCheck().run(source, id, revision, folder, c)

    private fun state(report: IntegrityReport, path: Path) =
        report.files.single { it.fileName == path.fileName.toString() }.state

    @Test fun `live vault and all backups authenticate with preview data`() {
        val folder = folder()
        credentials().use { c ->
            val (id, backups) = prepare(folder, c)
            val report = check(folder, id, c)
            assertTrue(report.intact)
            assertEquals(BackupFolderState.CHECKED, report.backupFolder)
            assertEquals(3, report.files.size)
            assertEquals(IntegrityFileKind.VAULT, report.files.first().kind)
            assertEquals("vault.keyrook", report.files.first().fileName)
            // Newest backup first.
            assertEquals(backups.reversed().map { it.fileName.toString() }, report.files.drop(1).map { it.fileName })
            report.files.forEach {
                assertEquals(IntegrityState.OK, it.state)
                assertEquals(0L, it.revision)
                assertEquals(8, it.entries)
                assertNotNull(it.modifiedAt)
            }
            assertEquals(3, report.count(IntegrityState.OK))
        }
    }

    @Test fun `flipped ciphertext byte in one backup is detected`() {
        val folder = folder()
        credentials().use { c ->
            val (id, backups) = prepare(folder, c)
            val bytes = Files.readAllBytes(backups[0])
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
            Files.write(backups[0], bytes)
            val report = check(folder, id, c)
            assertFalse(report.intact)
            assertEquals(IntegrityState.AUTHENTICATION_FAILED, state(report, backups[0]))
            assertEquals(IntegrityState.OK, state(report, backups[1]))
            assertEquals(IntegrityState.OK, state(report, source))
            val failed = report.files.single { it.state == IntegrityState.AUTHENTICATION_FAILED }
            assertNull(failed.revision); assertNull(failed.entries); assertNull(failed.modifiedAt)
        }
    }

    @Test fun `truncated files are never reported intact`() {
        val folder = folder()
        credentials().use { c ->
            val (id, backups) = prepare(folder, c)
            val bytes = Files.readAllBytes(backups[0])
            Files.write(backups[0], bytes.copyOf(bytes.size - 10))
            Files.write(backups[1], bytes.copyOf(50))
            val report = check(folder, id, c)
            assertEquals(IntegrityState.AUTHENTICATION_FAILED, state(report, backups[0]))
            assertEquals(IntegrityState.CORRUPT, state(report, backups[1]))
            assertEquals(IntegrityState.OK, state(report, source))
        }
    }

    @Test fun `damaged live vault header is corrupt`() {
        val folder = folder()
        credentials().use { c ->
            val (id, _) = prepare(folder, c)
            val bytes = Files.readAllBytes(source)
            bytes[0] = 0
            Files.write(source, bytes)
            val report = check(folder, id, c)
            assertEquals(IntegrityState.CORRUPT, state(report, source))
            assertEquals(2, report.count(IntegrityState.OK))
        }
    }

    @Test fun `unrelated files foreign vaults and links are not decrypted`() {
        val folder = folder()
        credentials().use { c ->
            val (id, backups) = prepare(folder, c)
            Files.write(folder.resolve("unrelated.keyrook.bak"), byteArrayOf(1, 2, 3))
            Files.write(folder.resolve("notes.txt"), byteArrayOf(4, 5, 6))
            Files.copy(backups[0], folder.resolve("${id}_1_0_not-a-uuid.keyrook.bak"))
            Files.copy(backups[0], folder.resolve("${id}_1_0_00000000-0000-0000-0000-000000000000.keyrook.bak.old"))
            val foreignName = "00000000-0000-0000-0000-000000000001_1_0_00000000-0000-0000-0000-000000000000.keyrook.bak"
            Files.copy(backups[0], folder.resolve(foreignName))
            val link = folder.resolve("${id}_1_0_00000000-0000-0000-0000-00000000000a.keyrook.bak")
            val linked = try { Files.createSymbolicLink(link, backups[0]); true } catch (_: Exception) { false }
            val report = check(folder, id, c)
            val names = report.files.map { it.fileName }.toSet()
            val expected = mutableSetOf(source.fileName.toString()) + backups.map { it.fileName.toString() }
            assertEquals(if (linked) expected + link.fileName.toString() else expected, names)
            if (linked) {
                assertEquals(IntegrityState.UNREADABLE, state(report, link))
                assertTrue(Files.isSymbolicLink(link))
            }
        }
    }

    @Test fun `check never changes files or folder contents`() {
        val folder = folder()
        credentials().use { c ->
            val (id, backups) = prepare(folder, c)
            val tampered = Files.readAllBytes(backups[0]).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            Files.write(backups[0], tampered)
            Files.write(folder.resolve("unrelated.txt"), byteArrayOf(7))
            val past = FileTime.from(Instant.parse("2025-05-05T05:05:05Z"))
            fun files() = (Files.list(folder).use { it.toList() } + listOf(source)).sorted()
            files().forEach { Files.setLastModifiedTime(it, past) }
            fun snapshot() = files().associate { path ->
                path.fileName.toString() to (Files.readAllBytes(path).toList() to
                    Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS))
            }
            val rootBefore = Files.list(root).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() }
            val before = snapshot()
            val report = check(folder, id, c)
            assertEquals(IntegrityState.AUTHENTICATION_FAILED, state(report, backups[0]))
            assertEquals(before, snapshot())
            assertEquals(rootBefore, Files.list(root).use { stream -> stream.map { it.fileName.toString() }.toList().toSet() })
        }
    }

    @Test fun `renamed backup and replaced vault are authentic but mismatched`() {
        val folder = folder()
        credentials().use { c ->
            val (id, backups) = prepare(folder, c)
            val renamed = backups[1].resolveSibling(backups[1].fileName.toString().replace("_0_", "_7_"))
            Files.move(backups[1], renamed)
            val report = check(folder, id, c, revision = 3)
            assertEquals(IntegrityState.MISMATCH, state(report, renamed))
            assertEquals(IntegrityState.MISMATCH, state(report, source))
            assertEquals(IntegrityState.OK, state(report, backups[0]))
            assertEquals(0L, report.files.single { it.fileName == renamed.fileName.toString() }.revision)
        }
    }

    @Test fun `session check reports backups written with an older password`() {
        val folder = folder()
        credentials().use { c -> credentials("new master").use { next -> VaultSession().use { session ->
            session.create(source, Vault(), c, testKdf)
            session.configureBackups(BackupService(folder))
            session.changePassword(next, testKdf)
            val report = session.checkIntegrity()
            assertEquals(BackupFolderState.CHECKED, report.backupFolder)
            assertEquals(IntegrityState.OK, report.files.first().state)
            assertEquals(1L, report.files.first().revision)
            assertEquals(listOf(IntegrityState.AUTHENTICATION_FAILED), report.files.drop(1).map { it.state })
            session.backupNow()
            assertEquals(1, session.checkIntegrity().count(IntegrityState.OK) - 1)
        } } }
    }

    @Test fun `missing folder and unconfigured backups are reported without creating anything`() {
        credentials().use { c -> VaultSession().use { session ->
            session.create(source, Vault(), c, testKdf)
            val alone = session.checkIntegrity()
            assertEquals(BackupFolderState.NOT_CONFIGURED, alone.backupFolder)
            assertTrue(alone.intact)
            val missing = root.resolve("missing")
            session.configureBackups(BackupService(missing))
            val report = session.checkIntegrity()
            assertEquals(BackupFolderState.UNREADABLE, report.backupFolder)
            assertFalse(report.intact)
            assertEquals(listOf(IntegrityState.OK), report.files.map { it.state })
            assertFalse(Files.exists(missing))
        } }
    }

    @Test fun `vault and backups below a linked parent directory are checked like the vault store opens them`() {
        val real = Files.createDirectory(root.resolve("real"))
        val parent = root.resolve("parent-link")
        try { Files.createSymbolicLink(parent, real) } catch (_: Exception) { return }
        val folder = Files.createDirectory(real.resolve("backups"))
        val linkedSource = parent.resolve("vault.keyrook")
        credentials().use { c -> VaultSession().use { session ->
            session.create(linkedSource, Vault(), c, testKdf)
            session.configureBackups(BackupService(parent.resolve("backups")))
            session.backupNow()
            val report = session.checkIntegrity()
            assertTrue(report.intact)
            assertEquals(BackupFolderState.CHECKED, report.backupFolder)
            assertEquals(listOf(IntegrityState.OK, IntegrityState.OK), report.files.map { it.state })

            // A vault file that is itself a symbolic link is still refused and never followed.
            val fileLink = root.resolve("vault-link.keyrook")
            Files.createSymbolicLink(fileLink, real.resolve("vault.keyrook"))
            val id = session.snapshot().use { it.id }
            val linked = IntegrityCheck().run(fileLink, id, 0, null, c)
            assertEquals(IntegrityState.UNREADABLE, linked.files.single().state)
            // A backup folder that is itself a symbolic link is refused as by backup creation.
            val folderLink = root.resolve("backups-link")
            Files.createSymbolicLink(folderLink, folder)
            assertEquals(BackupFolderState.UNREADABLE, IntegrityCheck().run(linkedSource, id, 0, folderLink, c).backupFolder)
        } }
    }

    @Test fun `wrong credentials fail authentication for every file`() {
        val folder = folder()
        credentials().use { c ->
            val (id, _) = prepare(folder, c)
            credentials("wrong").use { wrong ->
                val report = check(folder, id, wrong)
                assertEquals(3, report.count(IntegrityState.AUTHENTICATION_FAILED))
            }
        }
    }
}
