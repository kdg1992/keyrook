// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.backup.BackupPolicy
import app.keyrook.core.backup.BackupService
import app.keyrook.core.crypto.*
import app.keyrook.core.format.SchemaMigration
import app.keyrook.core.format.SchemaMigrations
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.format.VaultHeader
import app.keyrook.core.model.*
import app.keyrook.core.service.VaultSession
import app.keyrook.core.storage.VaultStore
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * Format migration test suite. Schema 0 is synthetic and exists only here: its root list was called `items`
 * and web fields were bare strings. Production registers no step because schema 1 is the first format.
 */
class FormatMigrationTest {
    @TempDir lateinit var directory: Path

    private val vaultId = "11111111-2222-4333-8444-555555555555"
    private val entryId = "66666666-7777-4888-8999-aaaaaaaaaaaa"

    private val zeroToOne = SchemaMigration(0, 1) { source ->
        buildJsonObject {
            source.forEach { (key, value) ->
                when (key) {
                    "schemaVersion" -> put(key, 1)
                    "items" -> put("entries", JsonArray(value.jsonArray.map { upgradeEntry(it.jsonObject) }))
                    else -> put(key, value)
                }
            }
        }
    }
    private val testMigrations = SchemaMigrations(1, listOf(zeroToOne))
    private val migratingCodec = VaultCodec(testMigrations)
    private val productionCodec = VaultCodec()

    private fun upgradeEntry(entry: JsonObject) = JsonObject(entry.mapValues { (key, value) ->
        if (key != "data") value
        else JsonObject(value.jsonObject.mapValues { (name, field) ->
            if (name == "type") field
            else buildJsonObject {
                put("value", field)
                put("hidden", name != "url")
                put("kind", if (name == "url") "URL" else "TEXT")
            }
        })
    })

    private fun schemaZero(version: Int = 0, port: Int? = null) = buildJsonObject {
        put("schemaVersion", version)
        put("id", vaultId)
        put("revision", 4)
        put("customers", JsonArray(emptyList()))
        put("projects", JsonArray(emptyList()))
        putJsonArray("items") {
            addJsonObject {
                put("id", entryId)
                put("title", "Legacy-Title-SENTINEL")
                putJsonObject("data") {
                    put("type", "web")
                    put("url", "https://legacy.example.invalid")
                    put("username", "Legacy-User-SENTINEL")
                    put("password", "Legacy-Password-SENTINEL-ä🗝")
                    if (port != null) put("port", port)
                }
                put("createdAt", DATE)
                put("modifiedAt", DATE)
                putJsonArray("tags") { add("legacy") }
                put("notes", "Legacy-Notes-SENTINEL")
            }
        }
    }.toString()

    private fun encryptPayload(payload: String, credentials: Credentials): ByteArray {
        val header = VaultHeader(testKdf, false, VaultCrypto.randomBytes(32), VaultCrypto.randomBytes(12))
        val aad = header.encode()
        val key = credentials.derive(header.salt, testKdf)
        return try { aad + VaultCrypto.aesGcm(true, key, header.nonce, aad, payload.toByteArray()) } finally { key.fill(0) }
    }

    private fun assertMigratedContent(vault: Vault) {
        vault.schemaVersion shouldBe Vault.SCHEMA_VERSION
        vault.id shouldBe vaultId
        vault.revision shouldBe 4L
        val entry = vault.entries.single()
        entry.id shouldBe entryId
        entry.title shouldBe "Legacy-Title-SENTINEL"
        entry.tags shouldBe listOf("legacy")
        entry.notes.useChars { String(it) shouldBe "Legacy-Notes-SENTINEL" }
        val web = entry.data as EntryData.Web
        web.url.hidden shouldBe false
        web.url.kind shouldBe FieldKind.URL
        web.password.hidden shouldBe true
        web.totp shouldBe null
        web.url.value.useChars { String(it) shouldBe "https://legacy.example.invalid" }
        web.username.value.useChars { String(it) shouldBe "Legacy-User-SENTINEL" }
        web.password.value.useChars { String(it) shouldBe "Legacy-Password-SENTINEL-ä🗝" }
    }

    private fun assertRejected(block: () -> Unit) {
        val error = assertThrows(InvalidVaultException::class.java) { block() }
        error.message shouldBe "Invalid or unsupported vault"
        error.cause shouldBe null
    }

    @Test fun `format migration test - older schema is migrated in memory and validated like a fresh vault`() {
        credentials().use { c ->
            val bytes = encryptPayload(schemaZero(), c)
            migratingCodec.decrypt(bytes, c).use { assertMigratedContent(it) }
            // Without a registered step the production pipeline refuses the document instead of guessing.
            assertRejected { productionCodec.decrypt(bytes, c) }
        }
    }

    @Test fun `registered steps run in version order regardless of registration order`() {
        val trail = mutableListOf<Int>()
        fun step(from: Int) = SchemaMigration(from, from + 1) { source ->
            trail += from
            JsonObject(source + ("schemaVersion" to JsonPrimitive(from + 1)))
        }
        val registry = SchemaMigrations(3, listOf(step(2), step(0), step(1)))
        val document = buildJsonObject { put("schemaVersion", 0); put("id", vaultId); put("revision", 9) }
        val migrated = registry.migrate(document)
        trail shouldBe listOf(0, 1, 2)
        migrated["schemaVersion"] shouldBe JsonPrimitive(3)
        migrated["id"] shouldBe JsonPrimitive(vaultId)
        migrated["revision"] shouldBe JsonPrimitive(9)
        trail.clear()
        registry.migrate(JsonObject(document + ("schemaVersion" to JsonPrimitive(1))))
        trail shouldBe listOf(1, 2)
        registry.chain(3) shouldBe emptyList()
    }

    @Test fun `missing steps newer versions and inconsistent steps are rejected`() {
        val gap = SchemaMigrations(3, listOf(SchemaMigration(0, 1) { it }, SchemaMigration(2, 3) { it }))
        gap.chain(0) shouldBe null
        gap.chain(-1) shouldBe null
        gap.chain(4) shouldBe null
        assertRejected { gap.migrate(buildJsonObject { put("schemaVersion", 0) }) }
        assertRejected { gap.migrate(buildJsonObject { put("schemaVersion", "2") }) }
        assertThrows(IllegalArgumentException::class.java) { SchemaMigration(1, 1) { it } }
        assertThrows(IllegalArgumentException::class.java) {
            SchemaMigrations(2, listOf(SchemaMigration(0, 1) { it }, SchemaMigration(0, 2) { it }))
        }
        assertThrows(IllegalArgumentException::class.java) { SchemaMigrations(1, listOf(SchemaMigration(1, 2) { it })) }

        val base = buildJsonObject { put("schemaVersion", 0); put("id", vaultId); put("revision", 1) }
        listOf<(JsonObject) -> JsonObject>(
            { it },
            { JsonObject(it + ("schemaVersion" to JsonPrimitive(1)) + ("revision" to JsonPrimitive(2))) },
            { JsonObject(it + ("schemaVersion" to JsonPrimitive(1)) - "id") },
        ).forEach { transform -> assertRejected { SchemaMigrations(1, listOf(SchemaMigration(0, 1, transform))).migrate(base) } }

        credentials().use { c ->
            // Downgrades and unknown future schemas stay unsupported, also for a codec that knows older steps.
            listOf(2, 99, -1).forEach { assertRejected { migratingCodec.decrypt(encryptPayload(schemaZero(it), c), c) } }
            // A step with no source is never applied, even if the document happens to claim schema 0.
            assertRejected { VaultCodec(SchemaMigrations(1, emptyList())).decrypt(encryptPayload(schemaZero(), c), c) }
        }
    }

    @Test fun `migration output passes the same decoder and validation as a stored document`() {
        credentials().use { c ->
            // Unknown field left over by the step.
            assertRejected { migratingCodec.decrypt(encryptPayload(schemaZero(port = 443), c), c) }
            // Broken reference that only schema validation detects.
            val dangling = schemaZero().replace("\"tags\"", "\"projectId\":\"${id()}\",\"tags\"")
            assertRejected { migratingCodec.decrypt(encryptPayload(dangling, c), c) }
            // Limits guard: nesting beyond 32 levels before any migration step runs.
            assertRejected { migratingCodec.decrypt(encryptPayload("[".repeat(33) + "]".repeat(33), c), c) }
        }
    }

    @Test fun `frozen v1 fixture decodes unchanged with and without registered migrations`() {
        credentials("fixture-password").use { c ->
            listOf(productionCodec, migratingCodec).forEach { codec ->
                codec.decrypt(frozenV1Fixture(), c).use {
                    it.schemaVersion shouldBe 1
                    it.id shouldBe vaultId
                    it.revision shouldBe 7L
                    it.entries shouldBe emptyList()
                }
            }
        }
    }

    @Test fun `encoding after migration writes current v1 format that needs no migration`() {
        credentials().use { c ->
            migratingCodec.decrypt(encryptPayload(schemaZero(), c), c).use { migrated ->
                val rewritten = migratingCodec.encrypt(migrated, c, testKdf)
                ByteBuffer.wrap(rewritten).getShort(8).toInt() shouldBe 1
                ByteBuffer.wrap(rewritten).getShort(10).toInt() shouldBe VaultHeader.SIZE
                productionCodec.decrypt(rewritten, c).use { assertMigratedContent(it) }
            }
        }
    }

    @Test fun `first save after migration keeps an automatic backup of the original bytes`() {
        val root = directory.toRealPath()
        val path = root.resolve("legacy.keyrook")
        val backupFolder = Files.createDirectory(root.resolve("backups"))
        credentials().use { c ->
            val original = encryptPayload(schemaZero(), c)
            Files.write(path, original)
            VaultSession(VaultStore(migratingCodec), migratingCodec).use { session ->
                session.open(path, c)
                // Opening migrates in memory only.
                assertArrayEquals(original, Files.readAllBytes(path))
                session.configureBackups(BackupService(backupFolder, BackupPolicy(), Clock.systemUTC(), migratingCodec))
                session.snapshot().use { session.save(it) }
                session.backupStatus().lastRevision shouldBe 4L
            }
            val backup = Files.list(backupFolder).use { files -> files.filter { it.toString().endsWith(".keyrook.bak") }.toList() }.single()
            assertArrayEquals(original, Files.readAllBytes(backup))
            VaultStore().load(path, c).use { loaded ->
                loaded.vault.revision shouldBe 5L
                loaded.vault.schemaVersion shouldBe Vault.SCHEMA_VERSION
                loaded.vault.entries.single().notes.useChars { String(it) shouldBe "Legacy-Notes-SENTINEL" }
            }
        }
    }
}
