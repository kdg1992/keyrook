// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.crypto.*
import app.keyrook.core.format.SchemaMigrations
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.format.VaultHeader
import app.keyrook.core.model.*
import app.keyrook.core.service.VaultSession
import app.keyrook.core.storage.VaultStore
import app.keyrook.core.transfer.VaultTransfer
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The production migration from schema 2 to schema 3 and the limits schema 3 raises. */
class SchemaV3MigrationTest {
    @TempDir lateinit var directory: Path

    private val codec = VaultCodec()
    private val json = Json { encodeDefaults = true; classDiscriminator = "type" }
    private val created = "2026-01-01T00:00:00Z"
    private val modified = "2026-09-01T00:00:00Z"
    private fun uuid(kind: Int, index: Int) = "%08x-0000-4000-8000-%012x".format(kind, index)
    private fun historyItem() = HistoryItem("2026-05-01T00:00:00Z", EntryData.Custom(mapOf("password" to field("old"))))
    private fun entry(index: Int, history: Int = 0) = Entry(uuid(3, index), "Entry $index",
        EntryData.Custom(emptyMap()), created, modified, history = List(history) { historyItem() })

    private fun encrypt(payload: String, credentials: Credentials): ByteArray {
        val header = VaultHeader(testKdf, false, VaultCrypto.randomBytes(32), VaultCrypto.randomBytes(12))
        val aad = header.encode()
        val key = credentials.derive(header.salt, testKdf)
        return try { aad + VaultCrypto.aesGcm(true, key, header.nonce, aad, payload.toByteArray()) } finally { key.fill(0) }
    }

    /** The decrypted schema 2 document of the frozen fixture, as a 1.x release up to the last schema 2 version wrote it. */
    private fun schemaTwo(): JsonObject = credentials("fixture-password-v2", FROZEN_V2_KEY_FILE).use { c ->
        val current = codec.decrypt(frozenV2Fixture(), c).use { json.encodeToJsonElement(Vault.serializer(), it).jsonObject }
        JsonObject(current + ("schemaVersion" to JsonPrimitive(2)))
    }

    @Test fun `the step changes only the schema version and is deterministic`() {
        val source = schemaTwo()
        val migrated = SchemaMigrations.V2_TO_V3.apply(source)
        migrated["schemaVersion"] shouldBe JsonPrimitive(3)
        (migrated - "schemaVersion") shouldBe (source - "schemaVersion")
        migrated.keys.toList() shouldBe source.keys.toList()
        SchemaMigrations.PRODUCTION.migrate(source) shouldBe migrated
        SchemaMigrations.PRODUCTION.chain(2)!!.map { it.from to it.to } shouldBe listOf(2 to 3)
        SchemaMigrations.PRODUCTION.chain(1)!!.map { it.from to it.to } shouldBe listOf(1 to 2, 2 to 3)
        SchemaMigrations.PRODUCTION.chain(3) shouldBe emptyList()
        SchemaMigrations.PRODUCTION.chain(4) shouldBe null
    }

    @Test fun `a schema 2 vault is migrated in memory and every value and secret survives`() {
        val source = schemaTwo()
        credentials().use { c ->
            val document = codec.decryptDocument(encrypt(source.toString(), c), c)
            document.storedSchemaVersion shouldBe 2
            document.vault.use { migrated ->
                migrated.schemaVersion shouldBe 3
                val encoded = json.encodeToJsonElement(Vault.serializer(), migrated).jsonObject
                (encoded - "schemaVersion") shouldBe (source - "schemaVersion")
                // The migrated document validates and round trips like a fresh one.
                codec.decrypt(codec.encrypt(migrated, c, testKdf), c).use { reopened ->
                    json.encodeToJsonElement(Vault.serializer(), reopened) shouldBe encoded
                }
            }
        }
    }

    @Test fun `the frozen schema 3 fixture decodes without migration`() {
        val bytes = frozenV3Fixture()
        val header = VaultHeader.parse(bytes, false)
        header.kdf shouldBe KdfParameters(iterations = 1)
        header.keyFile shouldBe true
        credentials("fixture-password-v3", FROZEN_V3_KEY_FILE).use { c ->
            val document = codec.decryptDocument(bytes, c)
            document.storedSchemaVersion shouldBe 3
            document.vault.use { vault ->
                vault.id shouldBe "33333333-4444-4555-8666-777777777777"
                vault.revision shouldBe 5L
                vault.customers.map { it.name } shouldBe listOf("Fixture Customer", "Minimal Customer")
                vault.projects.single().name shouldBe "Fixture Project"
                vault.entries.map { it.title } shouldBe listOf("Fixture web", "Fixture server", "Fixture custom")
                vault.entries.map { it.pinned } shouldBe listOf(true, false, true)
                vault.templates.size shouldBe 2
                val web = vault.entries[0]
                (web.data as EntryData.Web).password.value.useChars { String(it) shouldBe "Password-FIXTURE" }
                web.history.size shouldBe 101
                web.history.first().changedAt shouldBe "2026-06-01T08:00:00Z"
                web.history.last().changedAt shouldBe "2026-06-01T08:01:40Z"
                web.history.mapIndexed { index, item ->
                    (item.data as EntryData.Custom).values.getValue("password").value.useChars { String(it) == "Old-Password-FIXTURE-%03d".format(index) }
                }.all { it } shouldBe true
            }
        }
        credentials("fixture-password-v3").use { c -> assertThrows(AuthenticationException::class.java) { codec.decrypt(bytes, c) } }
    }

    @Test fun `earlier fixtures still open and newer or unbridged schemas are rejected`() {
        credentials("fixture-password").use { c -> codec.decrypt(frozenV1Fixture(), c).use { it.schemaVersion shouldBe 3 } }
        credentials("fixture-password-v2", FROZEN_V2_KEY_FILE).use { c ->
            codec.decrypt(frozenV2Fixture(), c).use { it.schemaVersion shouldBe 3 }
            // Without the registered step a schema 2 file is unbridged.
            val unbridged = VaultCodec(SchemaMigrations(Vault.SCHEMA_VERSION, listOf(SchemaMigrations.V1_TO_V2)))
            assertThrows(InvalidVaultException::class.java) { unbridged.decrypt(frozenV2Fixture(), c) }
        }
        credentials().use { c ->
            val newer = JsonObject(schemaTwo() + ("schemaVersion" to JsonPrimitive(4))).toString()
            val error = assertThrows(InvalidVaultException::class.java) { codec.decrypt(encrypt(newer, c), c) }
            error.message shouldBe "Invalid or unsupported vault"
            error.cause shouldBe null
        }
    }

    @Test fun `schema 3 accepts more history and records than schema 2 and still bounds them`() {
        Vault(entries = listOf(entry(1, history = Vault.MAX_HISTORY))).use { it.validate() }
        assertThrows(IllegalArgumentException::class.java) {
            Vault(entries = listOf(entry(1, history = Vault.MAX_HISTORY + 1))).use { it.validate() }
        }
        val shared = EntryData.Custom(emptyMap())
        fun entries(count: Int) = List(count) { Entry(uuid(3, it), "E", shared, created, modified) }
        fun customers(count: Int) = List(count) { Customer(uuid(1, it), "C") }
        fun projects(count: Int) = List(count) { Project(uuid(2, it), "P") }
        Vault.MAX_RECORDS shouldBe 100_000
        Vault.MAX_HISTORY shouldBe 500
        val full = Vault(customers = customers(Vault.MAX_RECORDS), projects = projects(Vault.MAX_RECORDS), entries = entries(Vault.MAX_RECORDS))
        full.validate()
        credentials().use { c ->
            codec.decrypt(codec.encrypt(full, c, testKdf), c).use { opened ->
                listOf(opened.customers.size, opened.projects.size, opened.entries.size) shouldBe List(3) { Vault.MAX_RECORDS }
            }
        }
        listOf(full.copy(entries = entries(Vault.MAX_RECORDS + 1)), full.copy(customers = customers(Vault.MAX_RECORDS + 1)),
            full.copy(projects = projects(Vault.MAX_RECORDS + 1))).forEach { vault ->
            assertThrows(IllegalArgumentException::class.java) { vault.validate() }
        }
    }

    @Test fun `the first save of a schema 2 file keeps a copy that a schema 2 release can open`() {
        val root = directory.toRealPath()
        val path = root.resolve("company.keyrook")
        val original = frozenV2Fixture()
        Files.write(path, original)
        credentials("fixture-password-v2", FROZEN_V2_KEY_FILE).use { c ->
            VaultSession().use { session ->
                session.open(path, c)
                session.pendingMigration() shouldBe 2
                session.migrationBackup() shouldBe null
                assertArrayEquals(original, Files.readAllBytes(path))
                session.snapshot().use { session.save(it) }
                session.pendingMigration() shouldBe null
                session.migrationBackup() shouldBe "company.keyrook.schema-v2-r3.keyrook.bak"
                session.snapshot().use { session.save(it) }
            }
            val copies = Files.list(root).use { files -> files.filter { it.fileName.toString().endsWith(".keyrook.bak") }.toList() }
            copies.map { it.fileName.toString() } shouldBe listOf("company.keyrook.schema-v2-r3.keyrook.bak")
            assertArrayEquals(original, Files.readAllBytes(copies.single()))
            VaultStore().load(path, c).use { loaded ->
                loaded.storedSchemaVersion shouldBe 3
                loaded.vault.revision shouldBe 5L
                loaded.vault.entries.map { it.title } shouldBe listOf("Fixture web", "Fixture server", "Fixture custom")
            }
            VaultSession().use { session -> session.open(path, c); session.pendingMigration() shouldBe null }
        }
    }

    @Test fun `schema 2 JSON exports are migrated on import and other programs' imports keep the 64 MiB bound`() {
        val transfer = VaultTransfer()
        transfer.importJson(schemaTwo().toString().toByteArray()).use { imported ->
            imported.schemaVersion shouldBe 3
            imported.entries.map { it.title } shouldBe listOf("Fixture web", "Fixture server", "Fixture custom")
        }
        VaultTransfer.isKeyrookCsv("keyrook-json\r\n\"{}\"\r\n".toByteArray()) shouldBe true
        VaultTransfer.isKeyrookCsv(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "keyrook-json\r\n".toByteArray()) shouldBe true
        VaultTransfer.isKeyrookCsv("Title,URL\r\n".toByteArray()) shouldBe false
        VaultTransfer.isKeyrookCsv(ByteArray(0)) shouldBe false
        VaultTransfer.MAX_FOREIGN_BYTES shouldBe 64 * 1024 * 1024
        // One byte above the bound is refused before parsing, whatever the content.
        val oversized = ByteArray(VaultTransfer.MAX_FOREIGN_BYTES + 1) { ' '.code.toByte() }
        "Title\r\nx\r\n".toByteArray().copyInto(oversized)
        assertThrows(Exception::class.java) { transfer.importCsv(oversized) }
        assertThrows(Exception::class.java) { transfer.importBitwarden(oversized) }
        assertThrows(Exception::class.java) { transfer.importKeePassXml(oversized) }
    }
}
