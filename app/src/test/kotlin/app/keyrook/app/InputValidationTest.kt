// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.Locale

class InputValidationTest {
    private fun valid(text: String) = (ExpiryDates.parse(text) as ExpiryInput.Valid).normalized

    @Test fun `ISO and German dates normalize to ISO`() {
        assertEquals("2026-12-31", valid("2026-12-31"))
        assertEquals("2026-12-31", valid("31.12.2026"))
        assertEquals("2026-02-01", valid("1.2.2026"))
        assertEquals("2026-02-01", valid("01.02.2026"))
        assertEquals("2026-12-31", valid("  31.12.2026 "))
        assertEquals(ExpiryInput.Empty, ExpiryDates.parse(""))
        assertEquals(ExpiryInput.Empty, ExpiryDates.parse("   "))
        assertNull(ExpiryDates.normalize(" "))
        assertEquals("2027-01-05", ExpiryDates.normalize("5.1.27"))
    }

    @Test fun `two digit years are in the twenty-first century`() {
        assertEquals("2026-12-31", valid("31.12.26"))
        assertEquals("2000-01-01", valid("01.01.00"))
        assertEquals("2099-06-30", valid("30.06.99"))
    }

    @Test fun `leap days are accepted only in leap years`() {
        assertEquals("2028-02-29", valid("2028-02-29"))
        assertEquals("2028-02-29", valid("29.02.28"))
        assertEquals("2000-02-29", valid("29.02.2000"))
        assertEquals(ExpiryInput.Invalid, ExpiryDates.parse("2027-02-29"))
        assertEquals(ExpiryInput.Invalid, ExpiryDates.parse("29.02.2100"))
    }

    @Test fun `malformed or impossible dates are invalid`() {
        listOf("2026-2-3", "2026-13-01", "2026-00-10", "2026-02-30", "32.01.2026", "31.04.2026", "0.1.2026", "1.0.2026",
            "31.12.026", "31.12.20266", "31/12/2026", "2026.12.31", "31-12-2026", "tomorrow", "+2026-12-31",
            "0000-01-01", "00.01.0000", "٣١.١٢.٢٠٢٦", "31.12.2026x", "2026-12-31T00:00").forEach { input ->
            assertEquals(ExpiryInput.Invalid, ExpiryDates.parse(input), input)
            assertEquals(InputError(InputProblem.INVALID_DATE), expiryError(input), input)
            assertThrows(IllegalArgumentException::class.java) { ExpiryDates.normalize(input) }
        }
        assertNull(expiryError(""))
        assertNull(expiryError("31.12.26"))
    }

    @Test fun `plus one year uses the entered date or today`() {
        val today = LocalDate.of(2026, 9, 24)
        assertEquals("2027-12-31", ExpiryDates.plusOneYear("31.12.26", today))
        assertEquals("2029-02-28", ExpiryDates.plusOneYear("2028-02-29", today))
        assertEquals("2027-09-24", ExpiryDates.plusOneYear("", today))
        assertEquals("2027-09-24", ExpiryDates.plusOneYear("garbage", today))
    }

    @Test fun `ports accept only 1 to 65535 and name empty input as required`() {
        assertEquals(1, parsePort("1"))
        assertEquals(22, parsePort(" 22 "))
        assertEquals(65535, parsePort("65535"))
        assertEquals(993, parsePort("00993"))
        listOf("", "0", "65536", "99999", "-1", "+22", "2 2", "22a", "1e3", "٢٢", "123456").forEach {
            assertNull(parsePort(it), it)
        }
        assertEquals(InputError(InputProblem.PORT_REQUIRED), portError(""))
        assertEquals(InputError(InputProblem.PORT_REQUIRED), portError("  "))
        assertEquals(InputError(InputProblem.INVALID_PORT), portError("0"))
        assertEquals(InputError(InputProblem.INVALID_PORT), portError("70000"))
        assertEquals(InputError(InputProblem.INVALID_PORT), portError("ssh"))
        assertNull(portError("2222"))
    }

    @Test fun `shown ports prefer drafts and omit disabled mail endpoints`() {
        Secret(charArrayOf()).use { empty ->
            val field = Field(empty)
            val server = EntryData.Server(field, 22, field, field, field, field)
            assertEquals(mapOf(PortSlot.SERVER to "22"), editorPorts(server, emptyMap()))
            assertEquals(mapOf(PortSlot.SERVER to ""), editorPorts(server, mapOf(PortSlot.SERVER to "")))
            val mail = EntryData.Email(field, field, field, imap = MailEndpoint(field, 993, MailEncryption.TLS),
                smtp = MailEndpoint(field, 465, MailEncryption.TLS))
            assertEquals(mapOf(PortSlot.IMAP to "993", PortSlot.SMTP to "70000"),
                editorPorts(mail, mapOf(PortSlot.SMTP to "70000", PortSlot.POP3 to "1")))
            assertEquals(emptyMap<PortSlot, String>(), editorPorts(EntryData.Custom(emptyMap()), mapOf(PortSlot.SERVER to "1")))
        }
    }

    @Test fun `editor validation names each failing field`() {
        val ok = validateEditor("Title", "a, b", "", "31.12.26", listOf("x"), mapOf(PortSlot.SERVER to "22"))
        assertTrue(ok.valid)
        val failed = validateEditor(" ", "x".repeat(257), "n".repeat(Vault.MAX_FIELD_CHARS + 1), "30.02.2026",
            listOf("fine", "v".repeat(Vault.MAX_FIELD_CHARS + 1)), mapOf(PortSlot.IMAP to "", PortSlot.SMTP to "0", PortSlot.POP3 to "995"))
        assertFalse(failed.valid)
        assertEquals(InputError(InputProblem.TITLE_REQUIRED), failed.title)
        assertEquals(InputError(InputProblem.TAG_TOO_LONG, 256), failed.tags)
        assertEquals(InputError(InputProblem.TOO_LONG, Vault.MAX_FIELD_CHARS), failed.notes)
        assertEquals(InputError(InputProblem.INVALID_DATE), failed.expiry)
        assertEquals(mapOf(1 to InputError(InputProblem.TOO_LONG, Vault.MAX_FIELD_CHARS)), failed.values)
        assertEquals(mapOf(PortSlot.IMAP to InputError(InputProblem.PORT_REQUIRED), PortSlot.SMTP to InputError(InputProblem.INVALID_PORT)),
            failed.ports)
        assertEquals(InputError(InputProblem.TOO_LONG, 4096), titleError("t".repeat(4097)))
        assertNull(titleError("t".repeat(4096)))
        assertEquals(InputError(InputProblem.TOO_MANY_TAGS, 100), tagsError((1..101).joinToString(",")))
        assertNull(tagsError((1..100).joinToString(",") + ", , "))
        assertNull(tagsError("x".repeat(256)))
        assertEquals(InputError(InputProblem.FIELD_EXISTS), customFieldNameError("PIN", listOf("PIN")))
        assertEquals(InputError(InputProblem.TOO_LONG, 256), customFieldNameError("n".repeat(257), emptyList()))
        assertNull(customFieldNameError("", emptyList()))
    }

    @Test fun `validation limits match what editedEntry accepts`() {
        val data = EntryData.Custom(emptyMap())
        val title = "t".repeat(4096)
        val tags = (1..100).joinToString(",") { "x".repeat(256) }
        assertTrue(validateEditor(title, tags, "", "29.02.28", emptyList(), emptyMap()).valid)
        val entry = editedEntry(null, data, title, tags, "", "29.02.28", emptyList(), emptyList())
        Vault(entries = listOf(entry)).use { vault ->
            vault.validate()
            assertEquals("2028-02-29", entry.expiresOn)
        }
    }

    @Test fun `every problem has a distinct message in both languages`() {
        listOf(Locale.GERMAN, Locale.ENGLISH).forEach { locale ->
            val messages = InputProblem.entries.map { problem ->
                val error = if (problem.key.contains("tooLong") || problem.key.contains("tag")) InputError(problem, 256) else InputError(problem)
                error.message(locale).also { message ->
                    assertTrue(message.isNotBlank(), "$locale ${problem.name}")
                    assertFalse(message.contains("%"), "$locale ${problem.name}")
                }
            }
            assertEquals(messages.size, messages.toSet().size, locale.toString())
        }
        assertEquals("Höchstens 256 Zeichen erlaubt.", InputError(InputProblem.TOO_LONG, 256).message(Locale.GERMAN))
        assertEquals("At most 100 tags are allowed.", InputError(InputProblem.TOO_MANY_TAGS, 100).message(Locale.ENGLISH))
        assertEquals("Titel ist erforderlich.", InputError(InputProblem.TITLE_REQUIRED).message(Locale.GERMAN))
        assertEquals("A port is required (1–65535).", InputError(InputProblem.PORT_REQUIRED).message(Locale.ENGLISH))
    }

    @Test fun `bulk tags must be non-blank short and free of commas`() {
        assertNull(bulkTagError("ops"))
        assertNull(bulkTagError("x".repeat(MAX_TAG_CHARS)))
        assertEquals(InputProblem.TAG_REQUIRED, bulkTagError("")?.problem)
        assertEquals(InputProblem.TAG_REQUIRED, bulkTagError("  ")?.problem)
        assertEquals(InputError(InputProblem.TAG_TOO_LONG, MAX_TAG_CHARS), bulkTagError("x".repeat(MAX_TAG_CHARS + 1)))
        assertEquals(InputProblem.TAG_COMMA, bulkTagError("a,b")?.problem)
    }
}
