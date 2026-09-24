// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core

import app.keyrook.core.crypto.Secret
import app.keyrook.core.crypto.SecretSerializer
import app.keyrook.core.model.*
import app.keyrook.core.security.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BreachCheckTest {
    private val password = "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8"
    private val unicode = "F87F18B3E609F51960945E5D6BCC450C93029FDE"

    private fun web(value: String, deleted: Boolean = false) =
        Entry(id(), "synthetic", EntryData.Web(field(""), field(""), field(value)), DATE, DATE,
            deletedAt = if (deleted) DATE else null)

    private fun line(hash: String, count: Long) = "${hash.substring(5)}:$count"

    /** A fake service answering from [ranges] and recording every requested prefix; no network is involved. */
    private class FakeRanges(private val ranges: Map<String, String> = emptyMap()) : RangeSource {
        val requested: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun range(prefix: String): ByteArray {
            requested += prefix
            return (ranges[prefix] ?: "0000000000000000000000000000000000A:0").toByteArray(Charsets.US_ASCII)
        }
    }

    @Test fun `hashes known vectors as uppercase hexadecimal SHA-1 of UTF-8`() {
        Secret("password".toCharArray()).use { assertEquals(password, String(BreachCheck.sha1Hex(it))) }
        Secret("Pässwörd-€".toCharArray()).use { assertEquals(unicode, String(BreachCheck.sha1Hex(it))) }
        assertEquals("https://api.pwnedpasswords.com/range/5BAA6", BreachCheck.rangeAddress("5BAA6"))
        assertThrows<IllegalArgumentException> { BreachCheck.rangeAddress("5baa6") }
        assertThrows<IllegalArgumentException> { BreachCheck.rangeAddress("5BAA6/../x") }
    }

    @Test fun `plans active non-empty passwords once per prefix without serializing secrets`() {
        Vault(entries = listOf(web("password"), web("password"), web(""), web("Pässwörd-€"), web("letmein", deleted = true),
            Entry(id(), "synthetic", EntryData.Custom(mapOf("Passwort" to field("password"), "note" to field("letmein"))), DATE, DATE),
            Entry(id(), "synthetic", EntryData.Domain(field("example.invalid"), field("password"), field("")), DATE, DATE))).use { vault ->
            val before = SecretSerializer.conversionsOnThisThread()
            BreachCheck.plan(vault).use { plan ->
                assertEquals(SecretSerializer.conversionsOnThisThread(), before)
                assertEquals(4, plan.passwords)
                assertEquals(2, plan.requests)
                assertEquals("BreachPlan(passwords=4, requests=2)", plan.toString())
                val source = FakeRanges()
                BreachCheck.run(plan, source, parallelism = 1)
                assertEquals(listOf("5BAA6", "F87F1"), source.requested)
            }
        }
    }

    @Test fun `empty vault needs no request`() {
        Vault(entries = listOf(web(""))).use { vault ->
            BreachCheck.plan(vault).use { plan ->
                val report = BreachCheck.run(plan, { error("no request expected") })
                assertEquals(0, report.requests)
                assertTrue(report.findings.isEmpty())
            }
        }
    }

    @Test fun `matches suffixes locally, ignores padding and keeps counts per entry`() {
        val breached = web("password")
        val padded = web("Pässwörd-€")
        val clean = web("letmein")
        val ranges = mapOf(
            "5BAA6" to "0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n${line(password, 52256179)}\r\n" +
                "00D4F6E8FA6EECAD2A3AA415EEC418D38EC:0\r\n",
            "F87F1" to "${line(unicode, 0)}\n0000000000000000000000000000000000A:3",
        )
        Vault(entries = listOf(breached, padded, clean)).use { vault ->
            BreachCheck.plan(vault).use { plan ->
                val progress = mutableListOf<Int>()
                val report = BreachCheck.run(plan, FakeRanges(ranges), progress = { done, total ->
                    assertEquals(3, total); progress += done })
                assertEquals(listOf(1, 2, 3), progress)
                assertEquals(listOf(BreachFinding(breached.id, DATE, 52256179)), report.findings)
                assertEquals(mapOf(breached.id to 52256179L), report.current(vault))
                assertEquals(3, report.passwords)
                assertFalse(Regex("password(?!s=)").containsMatchIn(report.toString()))
                assertFalse(report.toString().contains(password.substring(5)))
                assertFalse(report.toString().contains("5BAA6"))
            }
        }
    }

    @Test fun `lowercase suffixes in the response still match`() {
        val result = BreachCheck.matchRange(line(password, 7).lowercase().toByteArray(),
            listOf(password.substring(5).toCharArray()))
        assertArrayEquals(longArrayOf(7), result)
    }

    @Test fun `findings expire when the entry changes, is trashed or another vault is shown`() {
        val entry = web("password")
        val report = BreachReport("vault", 1, 1, 1, listOf(BreachFinding(entry.id, DATE, 3)))
        Vault(id = "00000000-0000-4000-8000-000000000000", entries = listOf(entry)).use {
            assertTrue(report.copy(vaultId = it.id).current(it).isNotEmpty())
            assertTrue(report.current(it).isEmpty())
        }
        val edited = entry.copy(modifiedAt = "2026-09-24T12:00:00Z")
        val trashed = entry.copy(deletedAt = DATE)
        listOf(edited, trashed).forEach { changed ->
            Vault(id = "00000000-0000-4000-8000-000000000000", entries = listOf(changed)).use {
                assertTrue(report.copy(vaultId = it.id).current(it).isEmpty())
            }
        }
    }

    @Test fun `malformed and oversized responses are refused`() {
        val suffix = listOf(password.substring(5).toCharArray())
        val bad = listOf(
            "",
            "\n",
            "${line(password, 1)}\n\n${line(password, 1)}",
            "${password.substring(5, 39)}:1",
            "${password.substring(5)}0:1",
            "${password.substring(5)}:",
            "${password.substring(5)}:-1",
            "${password.substring(5)}:+1",
            "${password.substring(5)}:1x",
            "${password.substring(5)} 1",
            "${password.substring(5)}:1234567890123",
            "G${password.substring(6)}:1",
            "<html>error</html>",
        )
        bad.forEach { body ->
            val error = assertThrows<BreachCheckException>(body) { BreachCheck.matchRange(body.toByteArray(), suffix) }
            assertEquals(BreachCheckException.Reason.INVALID_RESPONSE, error.reason)
        }
        val nonAscii = "${password.substring(5, 39)}ä:1".toByteArray(Charsets.UTF_8)
        assertThrows<BreachCheckException> { BreachCheck.matchRange(nonAscii, suffix) }
        val oversized = ByteArray(BreachCheck.MAX_RANGE_BYTES + 1) { 'A'.code.toByte() }
        assertThrows<BreachCheckException> { BreachCheck.matchRange(oversized, suffix) }
        val tooManyLines = (0..BreachCheck.MAX_RANGE_LINES).joinToString("\n") { line(password, 0) }
        assertThrows<BreachCheckException> { BreachCheck.matchRange(tooManyLines.toByteArray(), suffix) }
    }

    @Test fun `a transport or parse error ends the run without a report`() {
        Vault(entries = listOf(web("password"), web("Pässwörd-€"))).use { vault ->
            BreachCheck.plan(vault).use { plan ->
                val network = assertThrows<BreachCheckException> {
                    BreachCheck.run(plan, { prefix -> if (prefix == "F87F1") throw java.io.IOException("status 503") else
                        line(password, 1).toByteArray() }, parallelism = 1)
                }
                assertEquals(BreachCheckException.Reason.NETWORK, network.reason)
                assertFalse(network.message.orEmpty().contains("503"))
                val parse = assertThrows<BreachCheckException> {
                    BreachCheck.run(plan, { "not a range".toByteArray() })
                }
                assertEquals(BreachCheckException.Reason.INVALID_RESPONSE, parse.reason)
            }
        }
    }

    @Test fun `cancellation interrupts running requests and discards results`() {
        Vault(entries = listOf(web("password"), web("Pässwörd-€"), web("letmein"))).use { vault ->
            BreachCheck.plan(vault).use { plan ->
                val started = CountDownLatch(1)
                val interrupted = CountDownLatch(1)
                val cancel = AtomicBoolean(false)
                val source = RangeSource {
                    started.countDown()
                    try { Thread.sleep(60_000) } catch (e: InterruptedException) { interrupted.countDown(); throw e }
                    ByteArray(0)
                }
                Thread { started.await(); cancel.set(true) }.start()
                val error = assertThrows<BreachCheckException> { BreachCheck.run(plan, source, cancelled = cancel::get) }
                assertEquals(BreachCheckException.Reason.CANCELLED, error.reason)
                assertTrue(interrupted.await(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `overall timeout ends a stalled run`() {
        Vault(entries = listOf(web("password"))).use { vault ->
            BreachCheck.plan(vault).use { plan ->
                val error = assertThrows<BreachCheckException> {
                    BreachCheck.run(plan, { Thread.sleep(60_000); ByteArray(0) }, timeout = Duration.ofMillis(200))
                }
                assertEquals(BreachCheckException.Reason.TIMEOUT, error.reason)
            }
        }
    }

    @Test fun `requests never exceed the concurrency cap`() {
        val passwords = (1..12).map { "synthetic-$it" }
        Vault(entries = passwords.map { web(it) }).use { vault ->
            BreachCheck.plan(vault).use { plan ->
                val running = AtomicInteger()
                val peak = AtomicInteger()
                BreachCheck.run(plan, {
                    peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    Thread.sleep(20)
                    running.decrementAndGet()
                    "0000000000000000000000000000000000A:0".toByteArray()
                }, parallelism = 2)
                assertTrue(peak.get() in 1..2)
                assertThrows<IllegalArgumentException> { BreachCheck.run(plan, { ByteArray(0) }, parallelism = 5) }
            }
        }
    }

    @Test fun `closing a plan erases the hash suffixes`() {
        Vault(entries = listOf(web("password"))).use { vault ->
            val plan = BreachCheck.plan(vault)
            plan.close()
            val report = BreachCheck.run(plan, { line(password, 9).toByteArray() })
            assertTrue(report.findings.isEmpty())
        }
    }
}
