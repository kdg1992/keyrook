// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.backup.BackupPolicy
import app.keyrook.core.backup.BackupService
import app.keyrook.core.crypto.*
import app.keyrook.core.format.SchemaMigrations
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.format.VaultHeader
import app.keyrook.core.model.*
import app.keyrook.core.service.VaultSession
import app.keyrook.core.storage.VaultStore
import app.keyrook.core.transfer.PlaintextConsent
import app.keyrook.core.transfer.VaultTransfer
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The production migration from schema 1 to schema 2 and the schema 2 additions. */
class SchemaV2MigrationTest {
    @TempDir lateinit var directory: Path

    private val vaultId = "11111111-2222-4333-8444-555555555555"
    private val customerId = "22222222-3333-4444-8555-666666666666"
    private val projectId = "33333333-4444-4555-8666-777777777777"
    private val webId = "44444444-5555-4666-8777-888888888888"
    private val serverId = "55555555-6666-4777-8888-999999999999"
    private val trashedId = "66666666-7777-4888-8999-aaaaaaaaaaaa"
    private val codec = VaultCodec()
    private val json = Json { classDiscriminator = "type" }

    private fun JsonObjectBuilder.field(name: String, value: String, hidden: Boolean = true, kind: String = "TEXT") =
        putJsonObject(name) { put("value", value); put("hidden", hidden); put("kind", kind) }

    /** A schema 1 document as the previous release wrote it: customers, projects, favorites, history and trash. */
    private fun schemaOne(extraRoot: Pair<String, JsonElement>? = null, extraEntry: Pair<String, JsonElement>? = null,
                          extraCustomer: Pair<String, JsonElement>? = null) = buildJsonObject {
        put("schemaVersion", 1)
        put("id", vaultId)
        put("revision", 12)
        putJsonArray("customers") {
            addJsonObject {
                put("id", customerId); put("name", "Customer-V1")
                extraCustomer?.let { put(it.first, it.second) }
            }
        }
        putJsonArray("projects") { addJsonObject { put("id", projectId); put("name", "Project-V1"); put("customerId", customerId) } }
        putJsonArray("entries") {
            addJsonObject {
                put("id", webId); put("title", "Shop login")
                putJsonObject("data") {
                    put("type", "web")
                    field("url", "https://shop.example.invalid", hidden = false, kind = "URL")
                    field("username", "shop-admin")
                    field("password", "V1-Password-SENTINEL")
                }
                put("createdAt", "2026-01-01T00:00:00Z"); put("modifiedAt", "2026-03-01T00:00:00Z")
                put("customerId", customerId); put("projectId", projectId)
                putJsonArray("tags") { add("shop"); add(ReservedTags.LEGACY_FAVORITE); add("live") }
                put("notes", "V1-Notes-SENTINEL")
                putJsonArray("history") {
                    addJsonObject {
                        put("changedAt", "2026-02-01T00:00:00Z")
                        putJsonObject("data") { put("type", "custom"); putJsonObject("values") { field("password", "V1-Old-SENTINEL") } }
                    }
                }
                extraEntry?.let { put(it.first, it.second) }
            }
            addJsonObject {
                put("id", serverId); put("title", "Web server")
                putJsonObject("data") {
                    put("type", "server")
                    field("host", "srv.example.invalid", hidden = false); put("port", 2222)
                    field("username", "root"); field("password", "V1-Server-SENTINEL")
                    field("operatingSystem", "Debian", hidden = false); field("role", "web", hidden = false)
                }
                put("createdAt", "2026-01-01T00:00:00Z"); put("modifiedAt", "2026-01-01T00:00:00Z")
                put("customerId", customerId)
                putJsonArray("tags") { add("server") }
                put("notes", "")
            }
            addJsonObject {
                put("id", trashedId); put("title", "Old mailbox")
                putJsonObject("data") {
                    put("type", "email")
                    field("address", "old@example.invalid", hidden = false); field("username", "old"); field("password", "V1-Mail-SENTINEL")
                }
                put("createdAt", "2026-01-01T00:00:00Z"); put("modifiedAt", "2026-01-02T00:00:00Z")
                putJsonArray("tags") { add(ReservedTags.LEGACY_FAVORITE) }
                put("notes", "")
                put("deletedAt", "2026-01-02T00:00:00Z")
            }
        }
        extraRoot?.let { put(it.first, it.second) }
    }

    private fun encrypt(payload: String, credentials: Credentials): ByteArray {
        val header = VaultHeader(testKdf, false, VaultCrypto.randomBytes(32), VaultCrypto.randomBytes(12))
        val aad = header.encode()
        val key = credentials.derive(header.salt, testKdf)
        return try { aad + VaultCrypto.aesGcm(true, key, header.nonce, aad, payload.toByteArray()) } finally { key.fill(0) }
    }

    private fun assertRejected(block: () -> Unit) {
        val error = assertThrows(InvalidVaultException::class.java) { block() }
        error.message shouldBe "Invalid or unsupported vault"
        error.cause shouldBe null
    }

    private fun assertMigrated(vault: Vault) {
        vault.schemaVersion shouldBe 2
        vault.id shouldBe vaultId
        vault.revision shouldBe 12L
        vault.templates shouldBe emptyList()
        val customer = vault.customers.single()
        customer.name shouldBe "Customer-V1"
        listOf(customer.contactName, customer.contactEmail, customer.phone, customer.website) shouldBe listOf(null, null, null, null)
        customer.notes.useChars { it.size shouldBe 0 }
        vault.projects.single().description shouldBe null
        vault.projects.single().customerId shouldBe customerId
        val (web, server, trashed) = vault.entries
        web.pinned shouldBe true
        web.tags shouldBe listOf("shop", "live")
        web.notes.useChars { String(it) shouldBe "V1-Notes-SENTINEL" }
        (web.data as EntryData.Web).password.value.useChars { String(it) shouldBe "V1-Password-SENTINEL" }
        web.history.single().changedAt shouldBe "2026-02-01T00:00:00Z"
        (web.history.single().data as EntryData.Custom).values.getValue("password").value.useChars { String(it) shouldBe "V1-Old-SENTINEL" }
        server.pinned shouldBe false
        server.tags shouldBe listOf("server")
        (server.data as EntryData.Server).port shouldBe 2222
        trashed.pinned shouldBe true
        trashed.tags shouldBe emptyList()
        trashed.deletedAt shouldBe "2026-01-02T00:00:00Z"
    }

    @Test fun `a realistic schema 1 vault is migrated in memory, favorites become pins and every value survives`() {
        credentials().use { c ->
            codec.decrypt(encrypt(schemaOne().toString(), c), c).use(::assertMigrated)
        }
    }

    @Test fun `the step is deterministic and a migrated document needs no further step`() {
        val first = SchemaMigrations.PRODUCTION.migrate(schemaOne())
        first shouldBe SchemaMigrations.PRODUCTION.migrate(schemaOne())
        first["schemaVersion"] shouldBe JsonPrimitive(2)
        SchemaMigrations.PRODUCTION.chain(2) shouldBe emptyList()
        SchemaMigrations.PRODUCTION.migrate(first) shouldBe first
        credentials().use { c ->
            codec.decrypt(encrypt(schemaOne().toString(), c), c).use { migrated ->
                val written = codec.encrypt(migrated, c, testKdf)
                codec.decrypt(written, c).use { reopened ->
                    assertMigrated(reopened)
                    json.encodeToString(Vault.serializer(), reopened) shouldBe json.encodeToString(Vault.serializer(), migrated)
                }
            }
        }
    }

    @Test fun `schema 2 documents round trip with metadata, pins and templates`() {
        credentials().use { c ->
            sampleVault().use { vault ->
                vault.validate()
                val bytes = codec.encrypt(vault, c, testKdf)
                codec.decrypt(bytes, c).use { decoded ->
                    json.encodeToString(Vault.serializer(), decoded) shouldBe json.encodeToString(Vault.serializer(), vault)
                    decoded.customers.single().notes.useChars { String(it) shouldBe "Customer-Notes-SENTINEL-6612" }
                    decoded.projects.single().notes.useChars { String(it) shouldBe "Project-Notes-SENTINEL-7741" }
                    decoded.customers.single().contactEmail shouldBe "contact@example.invalid"
                    decoded.entries.map { it.pinned } shouldBe vault.entries.map { it.pinned }
                    decoded.templates shouldBe vault.templates
                }
            }
        }
    }

    @Test fun `newer schemas, unknown fields and schema 2 fields in a schema 1 document are rejected`() {
        credentials().use { c ->
            val current = sampleVault().use { json.encodeToString(Vault.serializer(), it) }
            listOf(
                current.replaceFirst("\"schemaVersion\":2", "\"schemaVersion\":3"),
                current.replaceFirst("\"templates\":", "\"unknown\":1,\"templates\":"),
                current.replaceFirst("\"pinned\":", "\"starred\":true,\"pinned\":"),
                current.replaceFirst("\"contactName\":", "\"fax\":\"1\",\"contactName\":"),
                current.replaceFirst("\"type\":\"web\",\"fields\"", "\"type\":\"web\",\"secret\":\"x\",\"fields\""),
                schemaOne(extraRoot = "templates" to JsonArray(emptyList())).toString(),
                schemaOne(extraEntry = "pinned" to JsonPrimitive(true)).toString(),
                schemaOne(extraCustomer = "phone" to JsonPrimitive("1")).toString(),
                schemaOne(extraEntry = "unknown" to JsonPrimitive(1)).toString(),
            ).forEach { payload -> assertRejected { codec.decrypt(encrypt(payload, c), c) } }
        }
    }

    @Test fun `an application that knows only schema 1 fails cleanly on a schema 2 file`() {
        credentials().use { c ->
            val bytes = sampleVault().use { codec.encrypt(it, c, testKdf) }
            // The previous release's registry ends at schema 1 and has no steps; a newer schema has no chain.
            SchemaMigrations(1, emptyList()).chain(2) shouldBe null
            assertRejected { VaultCodec(SchemaMigrations(1, emptyList())).decrypt(bytes, c) }
        }
    }

    @Test fun `closing a vault erases customer and project notes and deep copies own them`() {
        sampleVault().use { vault ->
            vault.deepCopy().use { copy ->
                assertNotSame(vault.customers.single().notes, copy.customers.single().notes)
                assertNotSame(vault.projects.single().notes, copy.projects.single().notes)
                copy.close()
                assertThrows(IllegalStateException::class.java) { copy.customers.single().notes.useChars { } }
                assertThrows(IllegalStateException::class.java) { copy.projects.single().notes.useChars { } }
                vault.customers.single().notes.useChars { String(it) shouldBe "Customer-Notes-SENTINEL-6612" }
            }
            vault.close()
            assertThrows(IllegalStateException::class.java) { vault.customers.single().notes.useChars { } }
        }
    }

    @Test fun `purging entries keeps customer and project notes`() {
        sampleVault().use { vault ->
            val purged = vault.purgeEntries(setOf(vault.entries[7].id))
            purged.customers.single().notes.useChars { String(it) shouldBe "Customer-Notes-SENTINEL-6612" }
            purged.projects.single().notes.useChars { String(it) shouldBe "Project-Notes-SENTINEL-7741" }
        }
    }

    @Test fun `metadata checks are lenient but bounded`() {
        with(OrganizationMetadata) {
            listOf(null, "a@b.de", "first.last+tag@sub.example.invalid").forEach { assertTrue(isValidEmail(it), it) }
            listOf("", " a@b.de", "ab.de", "a@b", "a@@b.de", "a@.de", "a b@c.de", "a@" + "b".repeat(250) + ".de")
                .forEach { assertFalse(isValidEmail(it), it) }
            listOf(null, "example.invalid", "https://example.invalid/path?q=1", "HTTP://x.invalid").forEach { assertTrue(isValidWebsite(it), it) }
            listOf("", "ftp://x.invalid", "javascript://x", "https://", "a b", "x".repeat(2049)).forEach { assertFalse(isValidWebsite(it), it) }
            listOf(null, "+49 (30) 123-456/7", "030.123").forEach { assertTrue(isValidPhone(it), it) }
            listOf("", "call me", "+-()", "1".repeat(65)).forEach { assertFalse(isValidPhone(it), it) }
            assertTrue(isValidContactName("Erika Mustermann"))
            assertFalse(isValidContactName("line\nbreak"))
            assertTrue(isValidDescription("two\nlines"))
            assertFalse(isValidDescription(" padded"))
            assertEquals(null, normalize("  "))
            assertEquals("x", normalize(" x "))
        }
    }

    @Test fun `templates keep the layout but no value and create empty entry data`() {
        sampleVault().use { vault ->
            vault.entries.forEach { entry ->
                val template = EntryTemplate.of(id(), " Layout ", entry)
                template.name shouldBe "Layout"
                template.type shouldBe TemplateType.of(entry.data)
                template.tags shouldBe entry.tags
                template.customerId shouldBe entry.customerId
                template.fields.map { it.hidden } shouldBe entry.data.fields().map { it.hidden }
                Vault(customers = vault.customers, projects = vault.projects, templates = listOf(template)).validate()
                val serialized = json.encodeToString(EntryTemplate.serializer(), template)
                listOf("SENTINEL-3412", "Password-SENTINEL", "Notes-SENTINEL", "Private-Key-SENTINEL", "Custom-SENTINEL")
                    .forEach { assertFalse(serialized.contains(it), serialized) }
                val data = template.newData()
                TemplateType.of(data) shouldBe template.type
                data.fields().map { it.hidden } shouldBe entry.data.fields().map { it.hidden }
                data.fields().map { it.kind } shouldBe entry.data.fields().map { it.kind }
                data.fields().forEach { field -> field.value.useChars { it.size shouldBe 0 }; field.value.close() }
            }
            val web = EntryTemplate(id(), "Web", TemplateType.WEB, listOf("url", "username", "password").map { TemplateField(it) })
            (web.newData() as EntryData.Web).totp shouldBe null
        }
    }

    @Test fun `template references are validated like entry references`() {
        sampleVault().use { vault ->
            val template = vault.templates.single()
            val otherCustomer = Customer(id(), "other")
            listOf(
                vault.copy(templates = listOf(template.copy(projectId = id()))),
                vault.copy(customers = vault.customers + otherCustomer,
                    templates = listOf(template.copy(customerId = otherCustomer.id))),
                vault.copy(templates = listOf(template.copy(name = " "))),
                vault.copy(templates = listOf(template.copy(id = "not-a-uuid"))),
                vault.copy(templates = listOf(template.copy(tags = listOf(ReservedTags.LEGACY_FAVORITE)))),
                vault.copy(templates = listOf(template.copy(fields = template.fields + template.fields.first()))),
            ).forEach { assertThrows(IllegalArgumentException::class.java) { it.validate() } }
        }
    }

    @Test fun `opening a schema 1 file keeps it unchanged until the first save, which keeps a copy of the old file`() {
        val root = directory.toRealPath()
        val path = root.resolve("company.keyrook")
        val backupFolder = Files.createDirectory(root.resolve("backups"))
        credentials().use { c ->
            val original = encrypt(schemaOne().toString(), c)
            Files.write(path, original)
            VaultSession().use { session ->
                session.open(path, c)
                session.pendingMigration() shouldBe 1
                session.migrationBackup() shouldBe null
                assertArrayEquals(original, Files.readAllBytes(path))
                session.configureBackups(BackupService(backupFolder, BackupPolicy()))
                session.snapshot().use { session.save(it) }
                session.pendingMigration() shouldBe null
                session.migrationBackup() shouldBe "company.keyrook.schema-v1-r12.keyrook.bak"
                // Later saves write no further copy.
                session.snapshot().use { session.save(it) }
            }
            val copies = Files.list(root).use { files -> files.filter { it.fileName.toString().endsWith(".keyrook.bak") }.toList() }
            copies.map { it.fileName.toString() } shouldBe listOf("company.keyrook.schema-v1-r12.keyrook.bak")
            assertArrayEquals(original, Files.readAllBytes(copies.single()))
            // The configured folder's first backup also holds the schema 1 bytes.
            val managed = Files.list(backupFolder).use { files -> files.filter { it.toString().endsWith(".keyrook.bak") }.toList() }
            assertTrue(managed.any { original.contentEquals(Files.readAllBytes(it)) })
            VaultStore().load(path, c).use { loaded ->
                loaded.storedSchemaVersion shouldBe 2
                loaded.vault.revision shouldBe 14L
                assertMigrated(loaded.vault.copy(revision = 12))
            }
            VaultSession().use { session -> session.open(path, c); session.pendingMigration() shouldBe null }
        }
    }

    @Test fun `without a backup folder the first save still keeps the old file and an existing copy is reused`() {
        val root = directory.toRealPath()
        val path = root.resolve("plain.keyrook")
        credentials().use { c ->
            val original = encrypt(schemaOne().toString(), c)
            Files.write(path, original)
            val copy = root.resolve("plain.keyrook.schema-v1-r12.keyrook.bak")
            Files.write(copy, original)
            VaultSession().use { session ->
                session.open(path, c)
                session.snapshot().use { session.save(it) }
                session.migrationBackup() shouldBe copy.fileName.toString()
            }
            assertArrayEquals(original, Files.readAllBytes(copy))
            Files.list(root).use { files -> files.filter { it.fileName.toString().endsWith(".keyrook.bak") }.count() } shouldBe 1L
        }
    }

    @Test fun `schema 1 JSON exports are migrated on import`() {
        val transfer = VaultTransfer()
        transfer.importJson(schemaOne().toString().toByteArray()).use(::assertMigrated)
        sampleVault().use { vault ->
            val exported = transfer.exportJson(vault, PlaintextConsent(true, true))
            try {
                exported.toString(Charsets.UTF_8).let { text ->
                    assertTrue(text.contains("\"schemaVersion\":2") && text.contains("\"templates\":[") && text.contains("\"pinned\":true"))
                }
            } finally { exported.fill(0) }
        }
    }
}
