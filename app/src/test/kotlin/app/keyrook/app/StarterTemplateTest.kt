// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StarterTemplateTest {
    @Test fun `starter templates create empty valid layouts with only the password hidden`() {
        StarterTemplate.entries.forEach { starter ->
            val data = starter.newData()
            try {
                assertEquals(starter.type, data.type(), starter.name)
                assertTrue(data.fields().all { it.value.useChars { chars -> chars.isEmpty() } }, starter.name)
                assertTrue(starter.label.isNotBlank())
                val entry = editedEntry(null, data, "Synthetic", "", "", "", data.fields().map { "" }, data.fields().map { it.hidden })
                Vault(entries = listOf(entry)).use { it.validate() }
            } finally { data.fields().forEach { it.value.close() } }
        }
        val mailbox = StarterTemplate.MAILBOX.newData() as EntryData.Email
        try {
            assertEquals(993 to MailEncryption.TLS, mailbox.imap!!.port to mailbox.imap!!.encryption)
            assertEquals(465, mailbox.smtp!!.port)
            assertNull(mailbox.pop3)
        } finally { mailbox.fields().forEach { it.value.close() } }
        val database = StarterTemplate.DATABASE.newData() as EntryData.Custom
        try {
            assertEquals(listOf(false, false, false, false, true), database.values.values.map { it.hidden })
        } finally { database.fields().forEach { it.value.close() } }
    }
}
