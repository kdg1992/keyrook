// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class VaultControllerTest {
    @TempDir lateinit var directory: Path

    @Test fun `save and lock preserve independently owned snapshots and password arrays are erased`() {
        val file = directory.resolve("test.keyrook")
        VaultController().use { controller ->
            val password = "synthetic-master-passphrase".toCharArray()
            controller.unlock(file, password, null, true).close()
            assertTrue(password.all { it == '\u0000' })
            val data = blankData(EntryType.WEB)
            val entry = editedEntry(null, data, "Synthetic login", "test", "", "",
                listOf("https://example.invalid", "sample", "synthetic-secret", ""), listOf(false, false, true, true))
            data.fields().forEach { it.value.close() }
            Vault(entries = listOf(entry)).use {
                controller.save(entry).use { snapshot ->
                    controller.lock()
                    assertEquals("Synthetic login", snapshot.entries.single().title)
                    assertEquals("synthetic-secret", snapshot.entries.single().data.fields()[2].value.useChars { chars -> String(chars) })
                }
            }
            controller.unlock(file, "synthetic-master-passphrase".toCharArray(), null, false).use {
                assertEquals(1, it.entries.size)
            }
            controller.trash(entry.id, false).use { assertNotNull(it.entries.single().deletedAt) }
            controller.trash(entry.id, true).use { assertNull(it.entries.single().deletedAt) }
            controller.duplicate(entry.id).use {
                assertEquals(2, it.entries.size)
                assertEquals(2, it.entries.map { candidate -> candidate.id }.toSet().size)
                assertEquals("Synthetic login (Kopie)", it.entries.last().title)
            }
        }
    }

    @Test fun `permanent deletion removes trashed entry with history and leaves others untouched`() {
        val file = directory.resolve("purge.keyrook")
        VaultController().use { controller ->
            controller.unlock(file, "synthetic-master-passphrase".toCharArray(), null, true).close()
            fun create(title: String, password: String): Entry {
                val data = blankData(EntryType.WEB)
                val entry = editedEntry(null, data, title, "", "", "", listOf("https://example.invalid", "sample", password, ""),
                    listOf(false, false, true, true))
                data.fields().forEach { it.value.close() }
                Vault(entries = listOf(entry)).use { controller.save(entry).close() }
                return entry
            }
            val removed = create("Removed", "old-removed-secret")
            val kept = create("Kept", "kept-secret")
            controller.session.snapshot().use { current ->
                val source = current.entries.single { it.id == removed.id }
                val edited = editedEntry(source, source.data, "Removed", "", "", "", listOf("https://example.invalid", "sample", "new-removed-secret", ""),
                    listOf(false, false, true, true))
                Vault(entries = listOf(edited)).use { controller.save(edited).close() }
            }
            assertThrows(IllegalArgumentException::class.java) { controller.purge(setOf(removed.id)) }
            controller.trash(removed.id, false).close()
            controller.purge(setOf(removed.id)).use { snapshot ->
                assertEquals(listOf(kept.id), snapshot.entries.map { it.id })
                assertEquals("kept-secret", snapshot.entries.single().data.fields()[2].value.useChars { String(it) })
                assertTrue(snapshot.entries.single().history.isEmpty())
            }
            controller.lock()
            controller.unlock(file, "synthetic-master-passphrase".toCharArray(), null, false).use { reopened ->
                assertEquals(listOf("Kept"), reopened.entries.map { it.title })
                val values = reopened.entries.flatMap { entry ->
                    (entry.data.fields() + entry.history.flatMap { it.data.fields() }).map { field -> field.value.useChars { String(it) } }
                }
                assertFalse(values.any { "removed-secret" in it })
            }
        }
    }

    @Test fun `emptying trash purges every trashed entry only`() {
        VaultController().use { controller ->
            controller.unlock(directory.resolve("empty.keyrook"), "synthetic-master-passphrase".toCharArray(), null, true).close()
            val ids = listOf("First", "Second", "Active").map { title ->
                val data = blankData(EntryType.CUSTOM)
                val entry = editedEntry(null, data, title, "", "", "", listOf("synthetic-$title"), listOf(true))
                data.fields().forEach { it.value.close() }
                Vault(entries = listOf(entry)).use { controller.save(entry).close() }
                entry.id
            }
            controller.trash(ids[0], false).close()
            controller.trash(ids[1], false).close()
            controller.emptyTrash().use { snapshot ->
                assertEquals(listOf(ids[2]), snapshot.entries.map { it.id })
                assertNull(snapshot.entries.single().deletedAt)
                assertEquals("synthetic-Active", snapshot.entries.single().data.fields().single().value.useChars { String(it) })
            }
            assertThrows(IllegalArgumentException::class.java) { controller.emptyTrash() }
        }
    }

    @Test fun `editor duplicates history so closing draft never erases original`() {
        val data = blankData(EntryType.WEB)
        val first = editedEntry(null, data, "Login", "", "", "", listOf("", "", "first", ""), listOf(false, false, true, true))
        data.fields().forEach { it.value.close() }
        Vault(entries = listOf(first)).use {
            val second = editedEntry(first, first.data, "Login", "", "", "", listOf("", "", "second", ""), listOf(false, false, true, true))
            Vault(entries = listOf(second)).use { candidate ->
                candidate.validate()
                assertEquals("first", second.history.single().data.fields()[2].value.useChars { String(it) })
            }
            assertEquals("first", first.data.fields()[2].value.useChars { String(it) })
        }
    }

    @Test fun `all typed editor fields round trip independently`() {
        EntryType.entries.forEach { type ->
            val data = blankData(type)
            val values = data.fields().indices.map { "synthetic-$it" }
            val entry = editedEntry(null, data, type.label, "", "", "", values, data.fields().map { it.hidden })
            data.fields().forEach { it.value.close() }
            Vault(entries = listOf(entry)).use { candidate ->
                candidate.validate()
                assertEquals(values, entry.data.fields().map { field -> field.value.useChars { String(it) } })
            }
        }
    }

    @Test fun `failed authentication also erases submitted password`() {
        val password = "synthetic-password".toCharArray()
        VaultController().use { controller ->
            assertThrows(Exception::class.java) { controller.unlock(directory.resolve("absent.keyrook"), password, null, false) }
            assertTrue(password.all { it == '\u0000' })
        }
    }

    @Test fun `email endpoint and custom field metadata survive edits`() {
        fun field(value: String, hidden: Boolean = false, kind: FieldKind = FieldKind.TEXT) =
            Field(app.keyrook.core.crypto.Secret(value.toCharArray()), hidden, kind)
        val email = EntryData.Email(field("mail@example.invalid"), field("user"), field("secret", true),
            imap = MailEndpoint(field("imap.example.invalid"), 1993, MailEncryption.TLS),
            smtp = MailEndpoint(field("smtp.example.invalid"), 1587, MailEncryption.STARTTLS))
        val custom = EntryData.Custom(linkedMapOf("Website" to field("https://example.invalid", false, FieldKind.URL), "Token" to field("private", true)))
        listOf(email, custom).forEach { data ->
            val values = data.fields().map { it.value.useChars { chars -> String(chars) } }
            val edited = editedEntry(null, data, "Synthetic", "", "", "", values, data.fields().map { it.hidden })
            data.fields().forEach { it.value.close() }
            Vault(entries = listOf(edited)).use {
                it.validate()
                when (val result = edited.data) {
                    is EntryData.Email -> {
                        assertEquals(1993, result.imap!!.port)
                        assertEquals(MailEncryption.STARTTLS, result.smtp!!.encryption)
                    }
                    is EntryData.Custom -> {
                        assertEquals(FieldKind.URL, result.values.getValue("Website").kind)
                        assertTrue(result.values.getValue("Token").hidden)
                    }
                    else -> fail("Unexpected type")
                }
            }
        }
    }
}
