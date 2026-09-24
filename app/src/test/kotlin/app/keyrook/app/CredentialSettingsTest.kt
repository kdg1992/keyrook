// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.Vault
import app.keyrook.core.storage.VaultStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

class CredentialSettingsTest {
    @TempDir lateinit var directory: Path

    @Test fun `KDF input enforces resource limits divisibility and integer bounds`() {
        assertEquals(KdfParameters(), parseKdfParameters("65536", "3", "4"))
        assertEquals(KdfParameters(262144, 5, 16), parseKdfParameters("262144", "5", "16"))
        listOf(Triple("65535", "3", "4"), Triple("262145", "3", "4"),
            Triple("65536", "0", "4"), Triple("65536", "6", "4"),
            Triple("65536", "3", "0"), Triple("65536", "3", "17"),
            Triple("65536", "3", "3"), Triple("2147483648", "3", "4"),
            Triple("", "3", "4"), Triple("65536", "1.5", "4")).forEach { (memory, rounds, lanes) ->
            assertNull(parseKdfParameters(memory, rounds, lanes))
        }
    }

    @Test fun `generated factor is private and cannot overwrite an existing file`() {
        val target = directory.toRealPath().resolve("factor.key")
        generateKeyFile(target)
        val before = Files.readAllBytes(target)
        assertEquals(32, before.size)
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { generateKeyFile(target) }
        assertArrayEquals(before, Files.readAllBytes(target))
        if (Files.getFileAttributeView(target, PosixFileAttributeView::class.java) != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(target))
        }
        before.fill(0)
    }

    @Test fun `key file is generated below a linked parent directory but never through a final link`() {
        val real = Files.createDirectory(directory.toRealPath().resolve("real"))
        val parent = directory.toRealPath().resolve("parent-link")
        try { Files.createSymbolicLink(parent, real) } catch (_: Exception) { return }
        generateKeyFile(parent.resolve("linked.key"))
        assertEquals(32, Files.size(real.resolve("linked.key")))
        val finalLink = directory.toRealPath().resolve("final-link.key")
        Files.createSymbolicLink(finalLink, real.resolve("absent.key"))
        assertThrows(java.io.IOException::class.java) { generateKeyFile(finalLink) }
        assertFalse(Files.exists(real.resolve("absent.key")))
    }

    @Test fun `selected creation parameters and generated factor survive authenticated reopen`() {
        val root = directory.toRealPath()
        val key = root.resolve("factor.key")
        val file = root.resolve("vault.keyrook")
        generateKeyFile(key)
        val parameters = KdfParameters(iterations = 1, parallelism = 2)
        VaultController().use { controller ->
            val password = "synthetic settings password".toCharArray()
            controller.unlock(file, password, key, true, parameters).close()
            assertTrue(password.all { it == '\u0000' })
            assertEquals(parameters, controller.session.kdfParameters())
            controller.lock()
            controller.unlock(file, "synthetic settings password".toCharArray(), key, false).close()
            assertEquals(parameters, controller.session.kdfParameters())
        }
    }

    @Test fun `cancel and stale confirmation leave ciphertext and parameters unchanged`() {
        val root = directory.toRealPath()
        val path = root.resolve("vault.keyrook")
        VaultController().use { controller ->
            Secret("synthetic settings password".toCharArray()).use { secret -> Credentials(secret).use { credentials ->
                Vault().use { controller.session.create(path, it, credentials, KdfParameters(iterations = 1)) }
                val original = Files.readAllBytes(path)
                assertFalse(applyKdfParameters(controller, KdfParameters(iterations = 2), false))
                assertFalse(applyKdfParameters(controller, null, true))
                val token = controller.sessionEpoch.capture()
                withOperationGuard(controller, token) {
                    controller.sessionEpoch.invalidate()
                    assertThrows(IllegalStateException::class.java) { applyKdfParameters(controller, KdfParameters(), true) }
                    assertThrows(IllegalStateException::class.java) { generateKeyFile(root.resolve("rejected.key")) }
                }
                assertFalse(Files.exists(root.resolve("rejected.key")))
                assertArrayEquals(original, Files.readAllBytes(path))
                VaultStore().load(path, credentials).use { assertEquals(1, it.parameters.iterations) }
            } }
        }
    }
}
