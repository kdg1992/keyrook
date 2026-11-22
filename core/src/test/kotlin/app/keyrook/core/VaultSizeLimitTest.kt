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
 * A vault at every model maximum at once does not fit into [VaultCodec.MAX_FILE_BYTES]: 100,000 entries with 500
 * history items each would need far more. These tests build a document that fills the file exactly, with every
 * record count at its maximum and notes filling the rest, and show that the file size, not the JSON guard's
 * separator bound, is the binding limit on saving and opening. A schema 1 document that fills the 64 MiB file of
 * its release still opens through both migration steps and can be saved again.
 */
@OptIn(ExperimentalSerializationApi::class)
class VaultSizeLimitTest {
    private val codec = VaultCodec()
    private val json = Json { encodeDefaults = true; classDiscriminator = "type" }
    private val maxDocument = VaultCodec.MAX_FILE_BYTES - VaultHeader.SIZE - 16
    /** The largest decrypted document of schemas 1 and 2, whose files were limited to 64 MiB. */
    private val legacyMaxDocument = 64 * 1024 * 1024 - VaultHeader.SIZE - 16
    // Shared empty values keep the source model small; decoding creates independent objects.
    private val empty = Secret(charArrayOf())
    private val created = "2026-01-01T00:00:00Z"
    private val modified = "2026-09-01T00:00:00Z"
    private val historyItem = HistoryItem("2026-05-01T00:00:00Z",
        EntryData.Custom(mapOf("password" to Field(Secret("old-password-0001".toCharArray())))))
    private val tags = List(100) { "t%02d".format(it) }
    private val customFields = (0 until 100).associate { "f%02d".format(it) to Field(empty) }
    /** The longest possible notes: every character needs a six-byte JSON escape. */
    private val longestNotes = Secret(CharArray(Vault.MAX_FIELD_CHARS) { '\u0001' })
    private fun uuid(kind: Int, index: Int) = "%08x-0000-4000-8000-%012x".format(kind, index)

    private fun field(hidden: Boolean = true) = Field(empty, hidden)

    /**
     * [records] customers, projects and entries: one SSH key assigned to nine tenths of the entries, which are
     * servers, and custom entries with 100 fields and 100 tags for the rest, up to 1,000. The last [fullHistories]
     * entries carry [historyPerEntry] history items each. With [templates], 1,000 templates of 100 fields and tags.
     */
    private fun baseVault(records: Int, templates: Boolean, fullHistories: Int, historyPerEntry: Int): Vault {
        val customers = List(records) { Customer(uuid(1, it), "Customer %06d".format(it)) }
        val projects = List(records) { Project(uuid(2, it), "Project %06d".format(it), customers[it].id) }
        val serverCount = records / 10 * 9
        val servers = (1..serverCount).map { uuid(3, it) }
        val history = List(historyPerEntry) { historyItem }
        val entries = List(records) { index ->
            val data = when {
                index == 0 -> EntryData.Ssh(SshKeyType.ED25519, field(), field(false), field(), field(false), servers)
                index <= serverCount -> EntryData.Server(field(false), 22, field(false), field(), field(false), field(false))
                index <= serverCount + 1_000 -> EntryData.Custom(customFields)
                else -> EntryData.Custom(emptyMap())
            }
            val full = index in serverCount + 1..serverCount + 1_000
            Entry(uuid(3, index), "Entry %06d".format(index), data, created, modified, customers[index].id,
                projects[index].id, if (full) tags else emptyList(), empty, expiresOn = "2027-01-01",
                history = if (index >= records - fullHistories) history else emptyList())
        }
        val templateList = if (!templates) emptyList() else List(Vault.MAX_TEMPLATES) { index ->
            EntryTemplate(uuid(4, index), "Template %04d".format(index), TemplateType.CUSTOM,
                List(100) { TemplateField("f%02d".format(it)) }, tags, customers[index].id, projects[index].id)
        }
        return Vault(id = uuid(5, 0), revision = 3, customers = customers, projects = projects, entries = entries,
            templates = templateList)
    }

    /**
     * Adds exactly [bytes] to the encoded document through notes, from the second entry on: the longest notes
     * first, then one with the remaining escaped characters and up to five single-byte characters.
     */
    private fun withNotes(vault: Vault, bytes: Long): Vault {
        val perNote = Vault.MAX_FIELD_CHARS * 6L
        val whole = (bytes / perNote).toInt()
        val rest = (bytes % perNote).toInt()
        val last = Secret(CharArray(rest / 6 + rest % 6) { if (it < rest / 6) '\u0001' else 'a' })
        check(whole + 2 <= vault.entries.size)
        return vault.copy(entries = vault.entries.mapIndexed { index, entry ->
            when {
                index in 1..whole -> entry.copy(notes = longestNotes)
                index == whole + 1 -> entry.copy(notes = last)
                else -> entry
            }
        })
    }

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
        override fun write(b: ByteArray, off: Int, len: Int) { for (i in off until off + len) write(b[i].toInt()) }
    }

    private fun measure(write: (OutputStream) -> Unit): Measure = Measure().also(write)
    private fun current(vault: Vault): (OutputStream) -> Unit = { json.encodeToStream(vault, it) }

    /** Schema 1 as its releases wrote it: plain customers and projects, no templates, no pinned flag. */
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

    private fun encryptPlain(bytes: ByteArray, credentials: Credentials): ByteArray {
        val header = VaultHeader(testKdf, false, VaultCrypto.randomBytes(32), VaultCrypto.randomBytes(12))
        val aad = header.encode()
        val key = credentials.derive(header.salt, testKdf)
        return try { aad + VaultCrypto.aesGcm(true, key, header.nonce, aad, bytes) } finally { key.fill(0) }
    }

    @Test fun `a vault that fills the file exactly saves and opens and the file size is the binding limit`() {
        val base = baseVault(Vault.MAX_RECORDS, templates = true, fullHistories = 200, historyPerEntry = Vault.MAX_HISTORY)
        val start = measure(current(base))
        val fill = maxDocument - start.bytes
        assertTrue(fill > 0) { "base document already exceeds the file: ${start.bytes}" }
        val largest = withNotes(base, fill)
        val size = measure(current(largest))
        size.bytes shouldBe maxDocument.toLong()
        // Separators stay well below the guard's bound, even with every record count at its maximum.
        assertTrue(size.separators > 5_000_000 && size.separators * 3 < maxDocument) { "separators: ${size.separators}" }
        credentials().use { c ->
            val bytes = codec.encrypt(largest, c, testKdf)
            bytes.size shouldBe VaultCodec.MAX_FILE_BYTES
            codec.decrypt(bytes, c).use { opened ->
                opened.entries.size shouldBe Vault.MAX_RECORDS
                opened.customers.size shouldBe Vault.MAX_RECORDS
                opened.projects.size shouldBe Vault.MAX_RECORDS
                opened.templates.size shouldBe Vault.MAX_TEMPLATES
                opened.entries.sumOf { it.history.size } shouldBe 200 * Vault.MAX_HISTORY
                opened.entries.last().history.size shouldBe Vault.MAX_HISTORY
                (opened.entries[0].data as EntryData.Ssh).serverIds.size shouldBe 90_000
                opened.entries[1].notes.useChars { it.size } shouldBe Vault.MAX_FIELD_CHARS
                opened.entries.sumOf { entry -> entry.notes.useChars { it.size.toLong() } } shouldBe
                    largest.entries.sumOf { entry -> entry.notes.useChars { it.size.toLong() } }
            }
            // One more byte exceeds the file size: a specific, content-free error.
            val error = assertThrows(VaultTooLargeException::class.java) { codec.encrypt(withNotes(base, fill + 1), c, testKdf) }
            error.message shouldBe "Vault exceeds the size limits of the file format"
            error.cause shouldBe null
        }
        // Validation, not size, rejects counts beyond the model maximums.
        val overfull = base.entries.last().copy(history = List(Vault.MAX_HISTORY + 1) { historyItem })
        assertThrows(InvalidVaultException::class.java) {
            credentials().use { codec.encrypt(Vault(entries = listOf(overfull.copy(customerId = null, projectId = null))), it, testKdf) }
        }
    }

    @Test fun `a schema 1 vault that fills the 64 MiB file of its release opens and can be saved again`() {
        // The maximums of schemas 1 and 2: 10,000 records and 100 history items per entry.
        val base = baseVault(10_000, templates = false, fullHistories = 1_000, historyPerEntry = 100)
        val fill = legacyMaxDocument - measure(v1(base)).bytes
        assertTrue(fill > 0)
        val plaintext = ByteArrayOutputStream(legacyMaxDocument).also { v1(withNotes(base, fill))(it) }.toByteArray()
        plaintext.size shouldBe legacyMaxDocument
        credentials().use { c ->
            val file = encryptPlain(plaintext, c)
            plaintext.fill(0)
            file.size shouldBe 64 * 1024 * 1024
            codec.decrypt(file, c).use { migrated ->
                migrated.schemaVersion shouldBe Vault.SCHEMA_VERSION
                migrated.entries.size shouldBe 10_000
                migrated.entries.sumOf { it.history.size } shouldBe 100_000
                // Migration adds default fields; the schema 3 file has room for them.
                val saved = codec.encrypt(migrated, c, testKdf)
                assertTrue(saved.size > file.size && saved.size < file.size + VaultCodec.MIGRATION_GROWTH_BYTES)
                codec.decrypt(saved, c).use { it.entries.size shouldBe 10_000 }
            }
        }
    }
}
