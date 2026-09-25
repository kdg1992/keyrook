// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.crypto.Credentials
import app.keyrook.core.crypto.InvalidVaultException
import app.keyrook.core.crypto.Secret
import app.keyrook.core.crypto.VaultCrypto
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.format.VaultHeader
import app.keyrook.core.format.VaultTooLargeException
import app.keyrook.core.model.*
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A vault at every model maximum at once does not fit into [VaultCodec.MAX_FILE_BYTES]: 10,000 entries with 100
 * history items each would already need more. These tests build the largest document that fits, with every count
 * maximum the file allows and history filling the rest, and show that the file size, not the JSON guard's separator
 * bound, is the binding limit on saving, opening and migrating.
 */
@OptIn(ExperimentalSerializationApi::class)
class VaultSizeLimitTest {
    private val codec = VaultCodec()
    private val json = Json { encodeDefaults = true; classDiscriminator = "type" }
    private val maxDocument = VaultCodec.MAX_FILE_BYTES - VaultHeader.SIZE - 16
    // Shared empty values keep the source model small; decoding creates independent objects.
    private val empty = Secret(charArrayOf())
    private val created = "2026-01-01T00:00:00Z"
    private val modified = "2026-09-01T00:00:00Z"
    private val historyItem = HistoryItem("2026-05-01T00:00:00Z",
        EntryData.Custom(mapOf("password" to Field(Secret("old-password-0001".toCharArray())))))
    private val tags = List(100) { "t%02d".format(it) }
    private val customFields = (0 until 100).associate { "f%02d".format(it) to Field(empty) }
    private fun uuid(kind: Int, index: Int) = "%08x-0000-4000-8000-%012x".format(kind, index)

    private fun field(hidden: Boolean = true) = Field(empty, hidden)

    /** Model maximums for everything but history: 10,000 customers, projects and entries, 1,000 full templates. */
    private fun baseVault(templates: Boolean): Vault {
        val customers = List(10_000) { Customer(uuid(1, it), "Customer %05d".format(it)) }
        val projects = List(10_000) { Project(uuid(2, it), "Project %05d".format(it), customers[it].id) }
        val servers = (1..9_000).map { uuid(3, it) }
        val entries = List(10_000) { index ->
            val data = when (index) {
                0 -> EntryData.Ssh(SshKeyType.ED25519, field(), field(false), field(), field(false), servers)
                in 1..9_000 -> EntryData.Server(field(false), 22, field(false), field(), field(false), field(false))
                else -> EntryData.Custom(customFields)
            }
            // The longest possible notes: every character needs a six-byte JSON escape.
            val notes = if (index == 9_999) Secret(CharArray(Vault.MAX_FIELD_CHARS) { '\u0001' }) else empty
            Entry(uuid(3, index), "Entry %05d".format(index), data, created, modified, customers[index].id,
                projects[index].id, tags, notes, expiresOn = "2027-01-01")
        }
        val templateList = if (!templates) emptyList() else List(Vault.MAX_TEMPLATES) { index ->
            EntryTemplate(uuid(4, index), "Template %04d".format(index), TemplateType.CUSTOM,
                List(100) { TemplateField("f%02d".format(it)) }, tags, customers[index].id, projects[index].id)
        }
        return Vault(id = uuid(5, 0), revision = 3, customers = customers, projects = projects, entries = entries,
            templates = templateList)
    }

    /** [total] history items, 100 per entry from the second entry on. */
    private fun withHistory(vault: Vault, total: Int): Vault = vault.copy(entries = vault.entries.mapIndexed { index, entry ->
        val count = (total - (index - 1) * 100).coerceIn(0, 100)
        if (index == 0 || count == 0) entry else entry.copy(history = List(count) { historyItem })
    })

    /** Bytes and structural separators of what [write] produces, without keeping the document. */
    private class Measure : OutputStream() {
        var bytes = 0L
        var separators = 0L
        private var quoted = false
        private var escape = false
        override fun write(b: Int) {
            bytes++
            val c = b.toChar()
            if (quoted) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                ',', ':' -> separators++
            }
        }
    }

    private fun measure(write: (OutputStream) -> Unit): Measure = Measure().also(write)
    private fun v2(vault: Vault): (OutputStream) -> Unit = { json.encodeToStream(vault, it) }

    /** Schema 1 as the previous release wrote it: plain customers and projects, no templates, no pinned flag. */
    private fun v1(vault: Vault): (OutputStream) -> Unit = { out ->
        fun text(value: String) = out.write(value.toByteArray(Charsets.UTF_8))
        text("{\"schemaVersion\":1,\"id\":\"${vault.id}\",\"revision\":${vault.revision},\"customers\":[")
        vault.customers.forEachIndexed { i, c -> text((if (i > 0) "," else "") + "{\"id\":\"${c.id}\",\"name\":\"${c.name}\"}") }
        text("],\"projects\":[")
        vault.projects.forEachIndexed { i, p ->
            text((if (i > 0) "," else "") + "{\"id\":\"${p.id}\",\"name\":\"${p.name}\",\"customerId\":\"${p.customerId}\"}")
        }
        text("],\"entries\":[")
        vault.entries.forEachIndexed { i, entry ->
            val encoded = json.encodeToString(Entry.serializer(), entry)
            check(encoded.endsWith(",\"pinned\":false}"))
            text((if (i > 0) "," else "") + encoded.removeSuffix(",\"pinned\":false}") + "}")
        }
        text("]}")
    }

    /** The largest history total whose document still fits [maxDocument]; every item adds the same bytes. */
    private fun fill(base: Vault, write: (Vault) -> (OutputStream) -> Unit): Int {
        val start = measure(write(base)).bytes
        val item = measure(write(withHistory(base, 1))).bytes - start
        val next = measure(write(withHistory(base, 2))).bytes - start - item
        var total = ((maxDocument - start - item) / next + 1).toInt()
        while (measure(write(withHistory(base, total))).bytes > maxDocument) total--
        while (measure(write(withHistory(base, total + 1))).bytes <= maxDocument) total++
        return total
    }

    private fun encryptPlain(bytes: ByteArray, credentials: Credentials): ByteArray {
        val header = VaultHeader(testKdf, false, VaultCrypto.randomBytes(32), VaultCrypto.randomBytes(12))
        val aad = header.encode()
        val key = credentials.derive(header.salt, testKdf)
        return try { aad + VaultCrypto.aesGcm(true, key, header.nonce, aad, bytes) } finally { key.fill(0) }
    }

    @Test fun `the largest vault that fits the file saves and opens and the file size is the binding limit`() {
        val base = baseVault(templates = true)
        val total = fill(base, ::v2)
        val largest = withHistory(base, total)
        val size = measure(v2(largest))
        assertTrue(size.bytes > maxDocument - 200) { "document should fill the file: ${size.bytes}" }
        // Hundreds of entries with a full history: far beyond the former fixed separator bound.
        assertTrue(total > 250_000) { "history items: $total" }
        // Separators stay well below the guard's bound, even with every count at its maximum.
        assertTrue(size.separators > 5_000_000 && size.separators * 3 < maxDocument) { "separators: ${size.separators}" }
        credentials().use { c ->
            val bytes = codec.encrypt(largest, c, testKdf)
            bytes.size shouldBe (size.bytes + VaultHeader.SIZE + 16).toInt()
            codec.decrypt(bytes, c).use { opened ->
                opened.entries.size shouldBe 10_000
                opened.customers.size shouldBe 10_000
                opened.projects.size shouldBe 10_000
                opened.templates.size shouldBe Vault.MAX_TEMPLATES
                opened.entries.sumOf { it.history.size } shouldBe total
                opened.entries[1].history.size shouldBe 100
                (opened.entries[0].data as EntryData.Ssh).serverIds.size shouldBe 9_000
                opened.entries[9_999].notes.useChars { it.size } shouldBe Vault.MAX_FIELD_CHARS
            }
            // One more history item exceeds the file size: a specific, content-free error.
            val error = assertThrows(VaultTooLargeException::class.java) { codec.encrypt(withHistory(base, total + 1), c, testKdf) }
            error.message shouldBe "Vault exceeds the size limits of the file format"
            error.cause shouldBe null
        }
        // Validation, not size, rejects counts beyond the model maximums.
        val overfull = largest.entries[1].copy(history = List(101) { historyItem })
        assertThrows(InvalidVaultException::class.java) {
            credentials().use { codec.encrypt(largest.copy(entries = listOf(overfull)), it, testKdf) }
        }
    }

    @Test fun `a schema 1 vault that fills the file still opens although migration adds fields`() {
        val base = baseVault(templates = false)
        val total = fill(base) { v1(it) }
        val plaintext = ByteArrayOutputStream(maxDocument).also { v1(withHistory(base, total))(it) }.toByteArray()
        assertTrue(plaintext.size > maxDocument - 200) { "document should fill the file: ${plaintext.size}" }
        credentials().use { c ->
            val file = encryptPlain(plaintext, c)
            plaintext.fill(0)
            assertTrue(file.size <= VaultCodec.MAX_FILE_BYTES)
            codec.decrypt(file, c).use { migrated ->
                migrated.schemaVersion shouldBe Vault.SCHEMA_VERSION
                migrated.entries.size shouldBe 10_000
                migrated.entries.sumOf { it.history.size } shouldBe total
                // Schema 2 adds default fields, so this vault is now too large to save until something is removed.
                assertThrows(VaultTooLargeException::class.java) { codec.encrypt(migrated, c, testKdf) }
                val trimmed = migrated.copy(entries = migrated.entries.map { it.copy(history = it.history.take(90)) })
                codec.decrypt(codec.encrypt(trimmed, c, testKdf), c).use { it.entries.size shouldBe 10_000 }
            }
        }
    }
}
