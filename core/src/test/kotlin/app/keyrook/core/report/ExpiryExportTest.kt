// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.report

import app.keyrook.core.DATE
import app.keyrook.core.field
import app.keyrook.core.id
import app.keyrook.core.model.*
import app.keyrook.core.sampleVault
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class ExpiryExportTest {
    private val stamp = Instant.parse("2026-09-24T08:15:30.123Z")

    private fun item(title: String, date: String = "2027-03-01", domain: String? = null) =
        ExpiryItem("0b5c3a8e-1f2d-4e6a-8b9c-0d1e2f3a4b5c", LocalDate.parse(date), title, "domain", "Kunde", null, domain)

    @Test fun `text values escape backslash, semicolon, comma and every line break`() {
        assertEquals("a\\\\b\\;c\\,d\\ne\\nf\\ng", icsText("a\\b;c,d\ne\r\nf\rg"))
        assertEquals("tab\tbell nul del ", icsText("tab\tbell\u0007nul\u0000del\u007f"))
        assertEquals("🔑 �", icsText("🔑 \uD83D"))
    }

    @Test fun `folding keeps every physical line within 75 octets and never splits a character`() {
        listOf("x".repeat(400), "ä".repeat(200), "€".repeat(130), "🔑".repeat(90), "a" + "ö€🔑".repeat(40)).forEach { line ->
            val folded = foldIcsLine("SUMMARY:$line")
            val physical = folded.split("\r\n")
            assertTrue(physical.size > 1)
            physical.forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75, it) }
            physical.drop(1).forEach { assertTrue(it.startsWith(" ")) }
            assertFalse(folded.contains('�'))
            assertEquals("SUMMARY:$line", physical.first() + physical.drop(1).joinToString("") { it.substring(1) })
        }
        assertEquals("x".repeat(75), foldIcsLine("x".repeat(75)))
        assertEquals("x".repeat(75) + "\r\n x", foldIcsLine("x".repeat(76)))
    }

    @Test fun `calendar uses CRLF, all-day dates, stable UIDs and an optional reminder`() {
        val items = listOf(item("Domäne; teuer, \"neu\"\nzweite Zeile", domain = "example.invalid"))
        val ics = expiryIcs(items, reportKeys, stamp, reminderDays = 30)
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertFalse(Regex("(?<!\r)\n").containsMatchIn(ics), "bare LF")
        assertFalse(ics.contains("\r\n\r\n"))
        val unfolded = ics.replace("\r\n ", "")
        val lines = unfolded.split("\r\n")
        assertTrue(lines.containsAll(listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT",
            "UID:0b5c3a8e-1f2d-4e6a-8b9c-0d1e2f3a4b5c-expiry@keyrook", "DTSTAMP:20260924T081530Z",
            "DTSTART;VALUE=DATE:20270301", "DTEND;VALUE=DATE:20270302",
            "SUMMARY:report.expires: Domäne\\; teuer\\, \"neu\"\\nzweite Zeile (example.invalid)",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-P30D", "END:VALARM", "END:VEVENT")), unfolded)
        assertTrue(lines.contains("DESCRIPTION:report.type: entry.type.domain\\ncommon.customer: Kunde\\nfield.domain: example.invalid"))
        ics.split("\r\n").forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75, it) }
        assertEquals(ics, expiryIcs(items, reportKeys, stamp, reminderDays = 30), "stable output")
        assertFalse(expiryIcs(items, reportKeys, stamp).contains("VALARM"))
        assertThrows(IllegalArgumentException::class.java) { expiryIcs(items, reportKeys, stamp, reminderDays = 0) }
    }

    @Test fun `CSV quotes every field and neutralizes formula cells`() {
        assertEquals("\"'=HYPERLINK(\"\"x\"\")\"", csvCell("=HYPERLINK(\"x\")"))
        listOf("+1", "-1", "@SUM(A1)", "\tx", "\rx").forEach { assertEquals("\"'${it}\"", csvCell(it)) }
        assertEquals("\"a,b\r\nc\"", csvCell("a,b\r\nc"))
        assertEquals("\"\"", csvCell(""))
        assertEquals("\"2027-03-01\"", csvCell("2027-03-01"))
        val csv = expiryCsv(listOf(item("=cmd|' /C calc'!A0"), item("x", "2026-12-31")), reportKeys)
        val rows = csv.split("\r\n")
        assertEquals("\"report.expires\",\"report.title\",\"report.type\",\"common.customer\",\"common.project\",\"field.domain\"", rows[0])
        assertEquals("\"2027-03-01\",\"'=cmd|' /C calc'!A0\",\"entry.type.domain\",\"Kunde\",\"\",\"\"", rows[1])
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test fun `items come from active entries with a date and never carry a secret`() {
        sentinelVault().use { vault ->
            closeHiddenSecrets(vault)
            val items = expiryItems(vault)
            assertEquals(16, items.size)
            assertEquals(items.sortedBy { it.date }, items)
            assertEquals(listOf("example.invalid"), items.mapNotNull { it.domain })
            val all = expiryIcs(items, reportKeys, stamp, 14) + expiryCsv(items, reportKeys)
            assertNoSentinel(all)
            assertTrue(all.contains("Kunde & <Co>"))
        }
        sampleVault().use { vault ->
            closeHiddenSecrets(vault)
            val items = expiryItems(vault)
            assertEquals(7, items.size, "trash is left out")
            assertNoSentinel(expiryIcs(items, reportKeys, stamp, 7) + expiryCsv(items, reportKeys))
        }
        val customer = Customer(id(), "C")
        val project = Project(id(), "P", customer.id)
        Vault(customers = listOf(customer), projects = listOf(project), entries = listOf(
            Entry(id(), "no date", EntryData.Custom(emptyMap()), DATE, DATE),
            Entry(id(), "cert", EntryData.Custom(mapOf("x" to field("y"))), DATE, DATE, projectId = project.id, expiresOn = "2026-10-01"),
        )).use { vault ->
            val items = expiryItems(vault)
            assertEquals(listOf("cert"), items.map { it.title })
            assertEquals("C", items.single().customer)
            assertEquals("P", items.single().project)
        }
    }
}
