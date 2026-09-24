// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import app.keyrook.core.security.BreachCheck
import app.keyrook.core.security.BreachCheckException
import app.keyrook.core.security.EntryHealth
import app.keyrook.core.security.HealthIssue
import app.keyrook.core.security.RangeSource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID

class BreachChecksTest {
    private val date = "2026-09-24T00:00:00Z"
    private val breachedLine = "1E4C9B93F3F0682250B6CF8331B7EE68FD8:42\r\n0000000000000000000000000000000000A:0"

    private fun entry(password: String) = Entry(UUID.randomUUID().toString(), "synthetic",
        EntryData.Web(Field(Secret(charArrayOf()), false), Field(Secret(charArrayOf()), false),
            Field(Secret(password.toCharArray()))), date, date)

    /** Answers every prefix from memory and counts requests; nothing here opens a connection. */
    private class FakeSource(private val body: (String) -> ByteArray) : RangeSource {
        val requested = mutableListOf<String>()
        override fun range(prefix: String): ByteArray { synchronized(requested) { requested += prefix }; return body(prefix) }
    }

    private fun prefix(password: String) = Secret(password.toCharArray()).use { String(BreachCheck.sha1Hex(it), 0, 5) }

    private fun checks(source: RangeSource) = BreachChecks("0.7.0", { source }, background = { it.run() }, deliver = { it.run() })

    @Test fun `request sends only the prefix address, padding and user agent`() {
        val request = rangeRequest("5BAA6", "0.7.0")
        assertEquals("GET", request.method())
        assertEquals(URI("https://api.pwnedpasswords.com/range/5BAA6"), request.uri())
        assertNull(request.uri().rawQuery)
        assertEquals(mapOf("Add-Padding" to listOf("true"), "User-Agent" to listOf("Keyrook/0.7.0")), request.headers().map())
        assertEquals(Duration.ofSeconds(15), request.timeout().get())
        assertEquals(listOf("Keyrook/dev"), rangeRequest("5BAA6", "0.7.0\r\nCookie: x").headers().allValues("User-Agent"))
        assertThrows(IllegalArgumentException::class.java) { rangeRequest("5BAA61E4C9", "0.7.0") }
    }

    @Test fun `client refuses redirects and stores no cookies or credentials`() {
        val client = breachHttpClient()
        try {
            assertEquals(HttpClient.Redirect.NEVER, client.followRedirects())
            assertTrue(client.cookieHandler().isEmpty)
            assertTrue(client.authenticator().isEmpty)
            assertTrue(client.connectTimeout().isPresent)
        } finally { client.shutdownNow() }
    }

    @Test fun `nothing is sent before consent and a declined question sends nothing`() {
        val source = FakeSource { breachedLine.toByteArray() }
        val breaches = checks(source)
        Vault(entries = listOf(entry("password"))).use { vault ->
            breaches.prepare { BreachCheck.plan(vault) }
            val consent = breaches.status as BreachStatus.Consent
            assertEquals(1, consent.plan.requests)
            assertTrue(source.requested.isEmpty())
            breaches.decline()
            assertEquals(BreachStatus.Idle, breaches.status)
            assertTrue(source.requested.isEmpty())
            assertNull(breaches.report)
            breaches.confirm()
            assertTrue(source.requested.isEmpty())
        }
    }

    @Test fun `every run asks again and a confirmed run keeps its report until lock`() {
        val source = FakeSource { breachedLine.toByteArray() }
        val breaches = checks(source)
        Vault(entries = listOf(entry("password"), entry("password"), entry("Synthetic-unique-456!"))).use { vault ->
            breaches.prepare { BreachCheck.plan(vault) }
            breaches.confirm()
            assertEquals(listOf("5BAA6", prefix("Synthetic-unique-456!")).sorted(), source.requested.sorted())
            val report = breaches.report!!
            assertEquals(mapOf(vault.entries[0].id to 42L, vault.entries[1].id to 42L), report.current(vault))
            assertInstanceOf(BreachStatus.Finished::class.java, breaches.status)
            breaches.prepare { BreachCheck.plan(vault) }
            assertInstanceOf(BreachStatus.Consent::class.java, breaches.status)
            assertEquals(2, source.requested.size)
            breaches.clear()
            assertNull(breaches.report)
            assertEquals(BreachStatus.Idle, breaches.status)
        }
    }

    @Test fun `failures keep no partial results and name no detail`() {
        val breaches = checks(FakeSource { prefix -> if (prefix == "5BAA6") breachedLine.toByteArray() else throw IOException("503") })
        Vault(entries = listOf(entry("password"), entry("Synthetic-unique-456!"))).use { vault ->
            breaches.prepare { BreachCheck.plan(vault) }
            breaches.confirm()
            assertEquals(BreachStatus.Failed(BreachCheckException.Reason.NETWORK), breaches.status)
            assertNull(breaches.report)
        }
        val invalid = checks(FakeSource { "<html>".toByteArray() })
        Vault(entries = listOf(entry("password"))).use { vault ->
            invalid.prepare { BreachCheck.plan(vault) }
            invalid.confirm()
            assertEquals(BreachStatus.Failed(BreachCheckException.Reason.INVALID_RESPONSE), invalid.status)
        }
        val local = checks(FakeSource { error("no request expected") })
        local.prepare { throw IllegalStateException("locked") }
        assertEquals(BreachStatus.Failed(null), local.status)
    }

    @Test fun `a vault without passwords needs no question`() {
        val breaches = checks(FakeSource { error("no request expected") })
        Vault(entries = listOf(entry(""))).use { vault ->
            breaches.prepare { BreachCheck.plan(vault) }
            assertEquals(BreachStatus.NothingToCheck, breaches.status)
        }
    }

    @Test fun `lock during a run discards its late result`() {
        val pending = ArrayDeque<Runnable>()
        val breaches = BreachChecks("0.7.0", { FakeSource { breachedLine.toByteArray() } },
            background = { pending.addLast(it) }, deliver = { it.run() })
        Vault(entries = listOf(entry("password"))).use { vault ->
            breaches.prepare { BreachCheck.plan(vault) }
            pending.removeFirst().run()
            breaches.confirm()
            assertInstanceOf(BreachStatus.Running::class.java, breaches.status)
            breaches.clear()
            pending.removeFirst().run()
            assertNull(breaches.report)
            assertEquals(BreachStatus.Idle, breaches.status)
        }
    }

    @Test fun `breach findings join the warning list, counts and markers`() {
        val local = listOf(EntryHealth("a", setOf(HealthIssue.REUSED_PASSWORD)), EntryHealth("b", setOf(HealthIssue.EXPIRED)))
        val merged = withBreaches(local, mapOf("a" to 3L, "c" to 1L))!!
        assertEquals(mapOf("a" to setOf(HealthIssue.REUSED_PASSWORD, HealthIssue.BREACHED_PASSWORD),
            "b" to setOf(HealthIssue.EXPIRED), "c" to setOf(HealthIssue.BREACHED_PASSWORD)), warningIssues(merged))
        assertEquals(WarningCounts(expired = 1, reused = 1, entries = 3, breached = 2), warningCounts(merged))
        assertSame(local, withBreaches(local, emptyMap()))
        assertNull(withBreaches(null, mapOf("a" to 3L)))
        assertEquals(listOf(HealthIssue.BREACHED_PASSWORD, HealthIssue.REUSED_PASSWORD),
            passwordMarkers(setOf(HealthIssue.REUSED_PASSWORD, HealthIssue.BREACHED_PASSWORD)))
        assertTrue(severeIssue(HealthIssue.BREACHED_PASSWORD))
        assertFalse(severeIssue(HealthIssue.REUSED_PASSWORD))
    }
}
