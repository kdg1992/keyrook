// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.crypto.Secret
import app.keyrook.core.crypto.SecretSerializer
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.model.*
import app.keyrook.core.security.VaultHealth
import app.keyrook.core.service.VaultSession
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections
import java.util.IdentityHashMap

class SessionReadTest {
    @TempDir lateinit var directory: Path
    private val path get() = directory.resolve("read.keyrook")

    @Test fun `read and snapshot copy secrets without converting them to strings`() {
        withSession { session ->
            val before = SecretSerializer.conversionsOnThisThread()
            val secrets = session.read { vault -> secretsOf(vault).size }
            session.snapshot().close()
            assertEquals(before, SecretSerializer.conversionsOnThisThread())
            assertTrue(secrets > 0)
            // The serialization round trip is still counted, so an unchanged counter above is meaningful.
            sampleVault().use { VaultCodec().duplicate(it).close() }
            assertTrue(SecretSerializer.conversionsOnThisThread() > before)
        }
    }

    @Test fun `independent copy owns every secret and keeps the document unchanged`() {
        sampleVault().use { original ->
            original.independentCopy().use { copy ->
                val originals = secretsOf(original)
                val copies = secretsOf(copy)
                assertEquals(originals.size, copies.size)
                assertTrue(originals.none { candidate -> copies.any { it === candidate } })
                val json = Json { classDiscriminator = "type" }
                assertEquals(json.encodeToString(Vault.serializer(), original), json.encodeToString(Vault.serializer(), copy))
            }
            original.entries.first().notes.useChars { assertEquals("Notes-SENTINEL-7123", String(it)) }
        }
    }

    @Test fun `read closes its copy and never exposes or erases the live document`() {
        withSession { session ->
            val seen = session.read { vault -> vault.entries.first().notes }
            assertThrows(IllegalStateException::class.java) { seen.useChars { } }
            session.read { vault -> vault.entries.first().notes.close() }
            session.read { vault -> vault.entries.first().notes.useChars { assertEquals("Notes-SENTINEL-7123", String(it)) } }
        }
    }

    @Test fun `a scan keeps its copy when the session locks meanwhile`() {
        withSession { session ->
            val value = session.read { vault ->
                session.lock()
                vault.entries.first().notes.useChars { String(it) }
            }
            assertEquals("Notes-SENTINEL-7123", value)
            assertThrows(IllegalStateException::class.java) { session.read { } }
        }
    }

    @Test fun `health findings through read match those of a snapshot`() {
        val health = VaultHealth(Clock.fixed(Instant.parse(DATE), ZoneOffset.UTC))
        withSession { session ->
            val expected = session.snapshot().use { health.inspect(it) }
            assertTrue(expected.isNotEmpty())
            assertEquals(expected, session.read { health.inspect(it) })
        }
    }

    private fun withSession(block: (VaultSession) -> Unit) {
        credentials().use { c -> VaultSession().use { session ->
            sampleVault().use { session.create(path, it, c, testKdf) }
            block(session)
        } }
    }

    /** Every [Secret] reachable from [root] through model objects, lists and maps, found by reflection. */
    private fun secretsOf(root: Any): List<Secret> {
        val found = mutableListOf<Secret>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun walk(value: Any?) {
            if (value == null || !visited.add(value)) return
            when (value) {
                is Secret -> found.add(value)
                is Collection<*> -> value.forEach(::walk)
                is Map<*, *> -> value.values.forEach(::walk)
                is Enum<*> -> Unit
                else -> if (value.javaClass.name.startsWith("app.keyrook.core.model.")) {
                    var type: Class<*>? = value.javaClass
                    while (type != null && type.name.startsWith("app.keyrook.core.model.")) {
                        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach {
                            it.isAccessible = true
                            walk(it.get(value))
                        }
                        type = type.superclass
                    }
                }
            }
        }
        walk(root)
        return found
    }
}
