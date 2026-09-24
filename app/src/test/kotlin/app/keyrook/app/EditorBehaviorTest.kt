// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

class EditorBehaviorTest {
    @Test fun `TOTP can be added to imported web entry then removed while retaining owned history`() {
        val data = EntryData.Web(Field(Secret("https://example.invalid".toCharArray()), false, FieldKind.URL),
            Field(Secret("synthetic-user".toCharArray()), false), Field(Secret("synthetic-password".toCharArray())))
        val first = editedEntry(null, data, "Imported", "", "", "",
            data.fields().map { it.value.useChars(::String) }, data.fields().map { it.hidden })
        data.fields().forEach { it.value.close() }
        Vault(entries = listOf(first)).use {
            Secret("synthetic-totp".toCharArray()).use { secret ->
                val withTotp = (first.data as EntryData.Web).copy(totp = Field(secret))
                val second = editedEntry(first, withTotp, first.title, "", "", "",
                    withTotp.fields().map { it.value.useChars(::String) }, withTotp.fields().map { it.hidden })
                Vault(entries = listOf(second)).use { secondVault ->
                    secondVault.validate()
                    val secondWeb = second.data as EntryData.Web
                    assertEquals("synthetic-totp", secondWeb.totp!!.value.useChars(::String))
                    val withoutTotp = secondWeb.copy(totp = null)
                    val third = editedEntry(second, withoutTotp, second.title, "", "", "",
                        withoutTotp.fields().map { it.value.useChars(::String) }, withoutTotp.fields().map { it.hidden })
                    Vault(entries = listOf(third)).use { thirdVault ->
                        thirdVault.validate()
                        assertNull((third.data as EntryData.Web).totp)
                        assertEquals("synthetic-totp", (third.history.last().data as EntryData.Web).totp!!.value.useChars(::String))
                    }
                    assertEquals("synthetic-totp", secondWeb.totp!!.value.useChars(::String))
                }
            }
        }
    }

    @Test fun `edits preserve valid timestamp ordering after clock moves backwards`() {
        val future = Instant.now().plusSeconds(86400).toString()
        val entry = Entry(java.util.UUID.randomUUID().toString(), "Future", EntryData.Custom(emptyMap()), future, future)
        Vault(entries = listOf(entry)).use {
            val changed = editedEntry(entry, entry.data, "Edited", "", "", "", emptyList(), emptyList())
            Vault(entries = listOf(changed)).use { vault ->
                vault.validate()
                assertEquals(future, changed.modifiedAt)
                assertEquals(future, changed.history.single().changedAt)
            }
        }
    }

    @Test fun `invalid field limits tags and dates are rejected before draft allocation`() {
        val data = EntryData.Custom(emptyMap())
        assertThrows(IllegalArgumentException::class.java) {
            editedEntry(null, data, "x".repeat(4097), "", "", "", emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            editedEntry(null, data, "Title", "x".repeat(257), "", "", emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            editedEntry(null, data, "Title", "", "", "2026-02-30", emptyList(), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            editedEntry(null, data, "Title", "", "", "30.02.2026", emptyList(), emptyList())
        }
    }

    @Test fun `German expiry input is stored as ISO date`() {
        val data = EntryData.Custom(emptyMap())
        val entry = editedEntry(null, data, "Title", "", "", " 31.12.26 ", emptyList(), emptyList())
        Vault(entries = listOf(entry)).use { vault ->
            vault.validate()
            assertEquals("2026-12-31", entry.expiresOn)
        }
        val cleared = editedEntry(null, data, "Title", "", "", "  ", emptyList(), emptyList())
        Vault(entries = listOf(cleared)).use { assertNull(cleared.expiresOn) }
    }

    @Test fun `a candidate submitted while an operation runs is erased instead of saved`() {
        val data = blankData(EntryType.WEB)
        fun candidate() = editedEntry(null, data, "Synthetic", "", "synthetic-notes", "",
            listOf("https://example.invalid", "sample", "synthetic-secret", ""), listOf(false, false, true, true))
        val refused = candidate()
        val accepted = candidate()
        data.fields().forEach { it.value.close() }
        val saved = mutableListOf<Entry>()
        assertFalse(submitEditedEntry(true, refused) { saved += it })
        assertTrue(saved.isEmpty())
        assertThrows(IllegalStateException::class.java) { refused.data.fields()[2].value.useChars(::String) }
        assertThrows(IllegalStateException::class.java) { refused.notes.useChars(::String) }
        Vault(entries = listOf(accepted)).use {
            assertTrue(submitEditedEntry(false, accepted) { saved += it })
            assertSame(accepted, saved.single())
            assertEquals("synthetic-secret", accepted.data.fields()[2].value.useChars(::String))
        }
    }
}
