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

    @Test fun `trash and restore never move the change time backwards`() {
        VaultController().use { controller ->
            controller.unlock(directory.resolve("skew.keyrook"), "synthetic-master-passphrase".toCharArray(), null, true,
                app.keyrook.core.crypto.KdfParameters(iterations = 1)).close()
            val data = blankData(EntryType.CUSTOM)
            val future = "2999-01-01T00:00:00Z"
            val entry = editedEntry(null, data, "Future", "", "", "", listOf("synthetic"), listOf(true))
                .copy(modifiedAt = future)
            data.fields().forEach { it.value.close() }
            Vault(entries = listOf(entry)).use { controller.save(entry).close() }
            controller.trash(entry.id, false).use { trashed ->
                assertEquals(future, trashed.entries.single().modifiedAt)
                assertEquals(future, trashed.entries.single().deletedAt)
            }
            controller.trash(entry.id, true).use { restored ->
                assertEquals(future, restored.entries.single().modifiedAt)
                assertNull(restored.entries.single().deletedAt)
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

    @Test fun `bulk trash restore and tag changes are saved as one revision each and fail atomically`() {
        VaultController().use { controller ->
            controller.unlock(directory.resolve("bulk.keyrook"), "synthetic-master-passphrase".toCharArray(), null, true,
                app.keyrook.core.crypto.KdfParameters(iterations = 1)).close()
            val ids = listOf("First", "Second", "Third").map { title ->
                val data = blankData(EntryType.CUSTOM)
                val entry = editedEntry(null, data, title, "", "", "", listOf("synthetic-$title"), listOf(true))
                data.fields().forEach { it.value.close() }
                Vault(entries = listOf(entry)).use { controller.save(entry).close() }
                entry.id
            }
            val marked = setOf(ids[0], ids[1])
            fun revision() = controller.session.snapshot().use { it.revision }
            val start = revision()
            controller.trashAll(marked, restore = false).use { snapshot ->
                assertEquals(start + 1, snapshot.revision)
                assertEquals(listOf(true, true, false), snapshot.entries.map { it.deletedAt != null })
            }
            // A selection that is partly in the wrong list changes nothing and saves nothing.
            assertThrows(IllegalArgumentException::class.java) { controller.trashAll(setOf(ids[0], ids[2]), restore = true) }
            assertEquals(start + 1, revision())
            controller.trashAll(marked, restore = true).use { snapshot ->
                assertEquals(start + 2, snapshot.revision)
                assertTrue(snapshot.entries.all { it.deletedAt == null })
            }
            controller.tagAll(ids.toSet(), " ops ", add = true).use { snapshot ->
                assertEquals(start + 3, snapshot.revision)
                assertTrue(snapshot.entries.all { it.tags == listOf("ops") })
            }
            // Nothing to change: no save.
            controller.tagAll(ids.toSet(), "ops", add = true).use { assertEquals(start + 3, it.revision) }
            controller.tagAll(setOf(ids[2]), "ops", add = false).use { snapshot ->
                assertEquals(start + 4, snapshot.revision)
                assertEquals(listOf(listOf("ops"), listOf("ops"), emptyList()), snapshot.entries.map { it.tags })
            }
            assertThrows(IllegalArgumentException::class.java) { controller.tagAll(marked, "a,b", add = true) }
            assertEquals(start + 4, revision())
        }
    }

    @Test fun `favorites are set and cleared as one revision and typed tags cannot set them`() {
        VaultController().use { controller ->
            controller.unlock(directory.resolve("favorites.keyrook"), "synthetic-master-passphrase".toCharArray(), null, true,
                app.keyrook.core.crypto.KdfParameters(iterations = 1)).close()
            val ids = listOf("First", "Second").map { title ->
                val data = blankData(EntryType.CUSTOM)
                val entry = editedEntry(null, data, title, "ops", "", "", listOf("synthetic-$title"), listOf(true))
                data.fields().forEach { it.value.close() }
                Vault(entries = listOf(entry)).use { controller.save(entry).close() }
                entry.id
            }
            val start = controller.session.snapshot().use { it.revision }
            controller.setFavorite(ids.toSet(), true).use { snapshot ->
                assertEquals(start + 1, snapshot.revision)
                assertTrue(snapshot.entries.all { it.pinned && ReservedTags.visible(it.tags) == listOf("ops") })
            }
            assertThrows(IllegalArgumentException::class.java) { controller.tagAll(ids.toSet(), ReservedTags.LEGACY_FAVORITE, add = true) }
            // Pinning pinned entries again changes nothing and saves nothing.
            controller.setFavorite(ids.toSet(), true).use { assertEquals(start + 1, it.revision) }
            controller.setFavorite(setOf(ids[0]), false).use { snapshot ->
                assertEquals(start + 2, snapshot.revision)
                assertEquals(listOf(false, true), snapshot.entries.map { it.pinned })
            }
        }
    }

    @Test fun `an entry layout is saved as a template without values and templates can be deleted`() {
        VaultController().use { controller ->
            controller.unlock(directory.resolve("templates.keyrook"), "synthetic-master-passphrase".toCharArray(), null, true,
                app.keyrook.core.crypto.KdfParameters(iterations = 1)).close()
            val data = blankData(EntryType.WEB)
            val entry = editedEntry(null, data, "Shop", "ops, shop", "Notes-SENTINEL", "",
                listOf("https://shop.invalid", "User-SENTINEL", "Password-SENTINEL", "Totp-SENTINEL"), listOf(false, true, true, true))
            data.fields().forEach { it.value.close() }
            Vault(entries = listOf(entry)).use { controller.save(entry).close() }
            val template = controller.saveTemplate(entry.id, " Shop-Vorlage ").use { it.templates.single() }
            assertEquals("Shop-Vorlage", template.name)
            assertEquals(TemplateType.WEB, template.type)
            assertEquals(listOf("url", "username", "password", "totp"), template.fields.map { it.name })
            assertEquals(listOf(false, true, true, true), template.fields.map { it.hidden })
            assertEquals(listOf("ops", "shop"), template.tags)
            assertFalse(template.toString().contains("SENTINEL"))
            assertEquals(EntryType.WEB, template.type.entryType())
            assertThrows(IllegalArgumentException::class.java) { controller.saveTemplate(entry.id, " ") }
            assertThrows(IllegalArgumentException::class.java) { controller.saveTemplate(java.util.UUID.randomUUID().toString(), "x") }
            controller.deleteTemplate(template.id).use { assertTrue(it.templates.isEmpty()) }
            assertThrows(IllegalArgumentException::class.java) { controller.deleteTemplate(template.id) }
        }
    }

    @Test fun `every template type maps to the editor type of the same name`() {
        TemplateType.entries.forEach { type ->
            val data = EntryTemplate(java.util.UUID.randomUUID().toString(), "t", type,
                (type.fieldNames.orEmpty() - type.optionalFields).map { TemplateField(it) }).newData()
            assertEquals(type.entryType(), data.type())
            data.fields().forEach { it.value.close() }
        }
        assertEquals(null, templateName("  "))
        assertEquals("Name", templateName(" Name "))
    }
}
