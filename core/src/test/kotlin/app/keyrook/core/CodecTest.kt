// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.crypto.*
import app.keyrook.core.format.VaultCodec
import app.keyrook.core.format.VaultHeader
import app.keyrook.core.model.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.ByteBuffer

class CodecTest {
    private val codec = VaultCodec()

    @Test fun `frozen v1 fixture from independent JCA encryption remains readable`() {
        val bytes = frozenV1Fixture()
        credentials("fixture-password").use { c -> codec.decrypt(bytes, c).use {
            it.id shouldBe "11111111-2222-4333-8444-555555555555"
            it.revision shouldBe 7L
            it.entries shouldBe emptyList()
        } }
    }

    @Test fun `JSON guard bounds collections and handles escapes and canonical duplicate keys`() {
        VaultCodec.checkJsonLimits("{\"x\":\"[\\\"{value}\\\"]\"}".toByteArray())
        assertThrows(InvalidVaultException::class.java) {
            VaultCodec.checkJsonLimits("{\"a\":1,\"\\u0061\":2}".toByteArray())
        }
        assertThrows(InvalidVaultException::class.java) {
            VaultCodec.checkJsonLimits(("[" + List(10_001) { "0" }.joinToString(",") + "]").toByteArray())
        }
        assertThrows(Exception::class.java) { VaultCodec.checkJsonLimits(byteArrayOf(34, 0xc0.toByte(), 0x80.toByte(), 34)) }
    }

    @Test fun `all entry types metadata history and trash survive encrypted roundtrip`() {
        credentials().use { credentials -> sampleVault().use { source ->
            codec.decrypt(codec.encrypt(source, credentials, testKdf), credentials).use { actual ->
                actual.id shouldBe source.id
                // Notes are secrets with identity equality; their characters are compared separately.
                val noNotes = Secret(charArrayOf())
                actual.customers.map { it.copy(notes = noNotes) } shouldBe source.customers.map { it.copy(notes = noNotes) }
                actual.projects.map { it.copy(notes = noNotes) } shouldBe source.projects.map { it.copy(notes = noNotes) }
                (actual.customers + actual.projects).map { it.notesText() } shouldBe (source.customers + source.projects).map { it.notesText() }
                actual.templates shouldBe source.templates
                actual.entries.map { it.pinned } shouldBe source.entries.map { it.pinned }
                actual.entries.size shouldBe 8
                actual.entries.zip(source.entries).forEach { (a, b) ->
                    a.title shouldBe b.title
                    a.tags shouldBe b.tags
                    a.customerId shouldBe b.customerId
                    a.projectId shouldBe b.projectId
                    a.createdAt shouldBe b.createdAt
                    a.modifiedAt shouldBe b.modifiedAt
                    a.expiresOn shouldBe b.expiresOn
                    a.deletedAt shouldBe b.deletedAt
                    a.data::class shouldBe b.data::class
                    a.notes.useChars { x -> b.notes.useChars { y -> assertArrayEquals(y, x) } }
                    a.data.fields().zip(b.data.fields()).forEach { (x, y) ->
                        x.hidden shouldBe y.hidden
                        x.kind shouldBe y.kind
                        x.value.useChars { c -> y.value.useChars { d -> assertArrayEquals(d, c) } }
                    }
                }
                actual.entries[0].history.single().data.fields().single().value.useChars { String(it) shouldBe "old-password" }
            }
        } }
    }

    @Test fun `ciphertext contains no plaintext field values or metadata`() {
        credentials().use { c -> sampleVault().use { v ->
            val bytes = codec.encrypt(v, c, testKdf)
            val visible = String(bytes, Charsets.ISO_8859_1)
            val sentinels = listOf(v.customers.single().name, v.projects.single().name, v.entries.first().title,
                "Notes-SENTINEL-7123", "Password-SENTINEL-7751", "Private-Key-SENTINEL-5531", "Tag-SENTINEL-9891")
            sentinels.forEach { assertFalse(visible.contains(it)) }
            val header = VaultHeader.parse(bytes, false)
            header.kdf shouldBe testKdf
            assertArrayEquals(byteArrayOf(75, 69, 89, 82, 79, 79, 75, 0), bytes.copyOfRange(0, 8))
            ByteBuffer.wrap(bytes).getShort(10).toInt() shouldBe 76
        } }
    }

    @Test fun `each encryption uses fresh salt nonce and ciphertext`() {
        credentials().use { c -> Vault().use { v ->
            val a = codec.encrypt(v, c, testKdf)
            val b = codec.encrypt(v, c, testKdf)
            assertFalse(a.copyOfRange(32, 64).contentEquals(b.copyOfRange(32, 64)))
            assertFalse(a.copyOfRange(64, 76).contentEquals(b.copyOfRange(64, 76)))
            assertFalse(a.contentEquals(b))
        } }
    }

    @Test fun `wrong password and key file fail neutrally`() {
        val key = KeyFiles.generate()
        credentials(key = key).use { good -> credentials("wrong", key).use { wrong -> credentials().use { missing ->
            credentials(key = KeyFiles.generate()).use { wrongKey ->
                val encrypted = codec.encrypt(Vault(), good, testKdf)
                listOf(wrong, missing, wrongKey).forEach { c ->
                    val error = assertThrows(AuthenticationException::class.java) { codec.decrypt(encrypted, c) }
                    error.message shouldBe "Vault authentication failed"
                    error.cause shouldBe null
                }
                codec.decrypt(encrypted, good).close()
            }
        } } }
        key.fill(0)
    }

    @ParameterizedTest @ValueSource(ints = [13, 27, 31, 32, 63, 64, 75, 76, 90])
    fun `tampering with valid header values or ciphertext is authenticated`(offset: Int) {
        credentials().use { c ->
            val bytes = codec.encrypt(Vault(), c, testKdf)
            bytes[offset] = (bytes[offset].toInt() xor when (offset) { 31 -> 5; 27 -> 3; else -> 1 }).toByte()
            assertThrows(AuthenticationException::class.java) { codec.decrypt(bytes, c) }
        }
    }

    @Test fun `tag corruption truncation and appended bytes never produce a vault`() {
        credentials().use { c ->
            val bytes = codec.encrypt(Vault(), c, testKdf)
            val changed = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            listOf(changed, bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0)).forEach {
                assertThrows(AuthenticationException::class.java) { codec.decrypt(it, c) }
            }
            listOf(0, 7, 75, 76, 91).forEach {
                assertThrows(InvalidVaultException::class.java) { codec.decrypt(bytes.copyOf(it), c) }
            }
        }
    }

    @ParameterizedTest @ValueSource(ints = [0, 8, 9, 10, 11, 12, 14, 15, 16, 19, 20])
    fun `unsupported versions algorithms flags and memory limits fail before derivation`(offset: Int) {
        credentials().use { c ->
            val bytes = VaultHeader(testKdf, false, ByteArray(32), ByteArray(12)).encode() + ByteArray(16)
            bytes[offset] = (bytes[offset].toInt() xor 0x40).toByte()
            assertThrows(InvalidVaultException::class.java) { codec.decrypt(bytes, c) }
        }
    }

    @Test fun `KDF resource limits cannot be bypassed by approval`() {
        listOf(KdfParameters(1), KdfParameters(Int.MAX_VALUE), KdfParameters(iterations = 0),
            KdfParameters(iterations = 11), KdfParameters(parallelism = 0), KdfParameters(parallelism = 17),
            KdfParameters(memoryKiB = 65_537)).forEach {
            assertThrows(InvalidVaultException::class.java) { it.validate(true) }
        }
        assertThrows(ResourceApprovalRequired::class.java) { KdfParameters(memoryKiB = 524_288).validate() }
        assertThrows(ResourceApprovalRequired::class.java) { KdfParameters(iterations = 6).validate() }
        KdfParameters(memoryKiB = 524_288, iterations = 6).validate(true)
    }

    @Test fun `invalid authenticated schema is rejected without leaking parser details`() {
        credentials().use { c ->
            listOf("{\"schemaVersion\":2}", "{\"schemaVersion\":3}", "{\"private-value-SENTINEL\":42}", "{\"id\":\"private-value-SENTINEL\"}",
                "[".repeat(33) + "]".repeat(33), "{} {}", "{}", "{\"schemaVersion\":1,\"schemaVersion\":1}").forEach { payload ->
                val header = VaultHeader(testKdf, false, ByteArray(32), ByteArray(12))
                val key = c.derive(header.salt, testKdf)
                val bytes = try { header.encode() + VaultCrypto.aesGcm(true, key, header.nonce, header.encode(), payload.toByteArray()) }
                    finally { key.fill(0) }
                val error = assertThrows(InvalidVaultException::class.java) { codec.decrypt(bytes, c) }
                error.message shouldBe "Invalid or unsupported vault"
                error.cause shouldBe null
            }
        }
    }

    @Test fun `invalid references duplicate identifiers and ports are rejected`() {
        sampleVault().use { valid ->
            val web = valid.entries.first()
            listOf(valid.copy(entries = valid.entries + web), valid.copy(entries = listOf(web.copy(projectId = id()))),
                valid.copy(schemaVersion = 3), valid.copy(schemaVersion = 1), valid.copy(revision = -1),
                valid.copy(entries = listOf(web.copy(tags = listOf(ReservedTags.LEGACY_FAVORITE)))),
                valid.copy(customers = listOf(valid.customers[0].copy(contactEmail = "no-address"))),
                valid.copy(customers = listOf(valid.customers[0].copy(website = "javascript://x"))),
                valid.copy(projects = listOf(valid.projects[0].copy(description = " "))),
                valid.copy(templates = listOf(EntryTemplate(id(), "t", TemplateType.WEB, customerId = id()))),
                valid.copy(templates = listOf(EntryTemplate(id(), "t", TemplateType.SERVER))),
                valid.copy(templates = listOf(EntryTemplate(id(), "t", TemplateType.DOMAIN,
                    listOf("name", "registrar", "dnsNotes", "url").map { TemplateField(it) }))),
                valid.copy(templates = valid.templates + valid.templates),
                valid.copy(entries = listOf(valid.entries[4].copy(data = (valid.entries[4].data as EntryData.Server).copy(port = 0))))).forEach {
                assertThrows(IllegalArgumentException::class.java) { it.validate() }
            }
        }
    }
}

private fun Any.notesText(): String = when (this) {
    is Customer -> notes.useChars { String(it) }
    is Project -> notes.useChars { String(it) }
    else -> error("No notes")
}
