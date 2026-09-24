// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class SessionSecurityTest {
    @TempDir lateinit var directory: Path

    @Test fun `failure delays grow cap at sixty seconds and success resets them`() {
        var now = 0L
        val backoff = UnlockBackoff { now }
        assertEquals(0L, backoff.remainingMillis())
        listOf(1, 2, 4, 8, 16, 32, 60, 60).forEach { seconds ->
            backoff.failed()
            assertEquals(seconds * 1000L, backoff.remainingMillis())
            assertThrows(UnlockDelayedException::class.java) { backoff.requireReady() }
            now += seconds * 1_000_000_000L - 1
            assertEquals(1L, backoff.remainingMillis())
            now += 1
            backoff.requireReady()
        }
        backoff.succeeded()
        backoff.failed()
        assertEquals(1000L, backoff.remainingMillis())
    }

    @Test fun `monotonic timers survive nanoTime signed wrap`() {
        var now = Long.MAX_VALUE - 500_000_000
        val backoff = UnlockBackoff { now }
        backoff.failed()
        now += 1_000_000_000
        assertEquals(0L, backoff.remainingMillis())
        val inactivity = InactivityDeadline { now }
        now += 59_000_000_000
        assertFalse(inactivity.expired(1))
        now += 1_000_000_000
        assertTrue(inactivity.expired(1))
        inactivity.activity()
        assertFalse(inactivity.expired(1))
    }

    @Test fun `suspend gap expires the deadline immediately and activity resets it`() {
        var nanos = 0L
        var wall = 1_700_000_000_000L
        val inactivity = InactivityDeadline({ wall }, { nanos })
        fun awake(millis: Long) { nanos += millis * 1_000_000; wall += millis }
        awake(60_000)
        wall += 29_000
        awake(500)
        assertFalse(inactivity.expired(5), "A gap below the suspend threshold only counts toward the timeout")
        wall += ElapsedTime.SUSPEND_GAP_MILLIS
        awake(500)
        assertTrue(inactivity.expired(5), "The first check after a suspend expires regardless of remaining time")
        assertTrue(inactivity.expired(5))
        inactivity.activity()
        assertFalse(inactivity.expired(5))
        awake(299_999)
        assertFalse(inactivity.expired(5))
        awake(1)
        assertTrue(inactivity.expired(5))
    }

    @Test fun `wall clock time elapsed during a short suspend counts toward the timeout`() {
        var nanos = 0L
        var wall = 1_700_000_000_000L
        val inactivity = InactivityDeadline({ wall }, { nanos })
        nanos += 50_000_000_000
        wall += 50_000
        assertFalse(inactivity.expired(1))
        wall += 10_000
        assertTrue(inactivity.expired(1))
    }

    @Test fun `backward wall clock steps never extend the monotonic deadline`() {
        var nanos = 0L
        var wall = 1_700_000_000_000L
        val inactivity = InactivityDeadline({ wall }, { nanos })
        nanos += 240_000_000_000
        wall -= 3_600_000
        assertFalse(inactivity.expired(5))
        nanos += 59_999_999_999
        wall += 59_999
        assertFalse(inactivity.expired(5))
        nanos += 1
        assertTrue(inactivity.expired(5))
    }

    @Test fun `small forward wall clock adjustments only advance the timeout by their size`() {
        var nanos = 0L
        var wall = 1_700_000_000_000L
        val inactivity = InactivityDeadline({ wall }, { nanos })
        fun awake(millis: Long) { nanos += millis * 1_000_000; wall += millis }
        awake(60_000)
        wall += 2_000
        assertFalse(inactivity.expired(5))
        awake(237_000)
        assertFalse(inactivity.expired(5))
        awake(999)
        assertFalse(inactivity.expired(5))
        awake(1)
        assertTrue(inactivity.expired(5), "Five wall-clock minutes have passed")
    }

    @Test fun `lock discards late plaintext result and never calls its UI publisher`() {
        val epochs = SessionEpoch()
        val operation = epochs.capture()
        epochs.invalidate()
        val secret = Secret("synthetic-result".toCharArray())
        val timestamp = Instant.now().toString()
        val result = Vault(entries = listOf(Entry(UUID.randomUUID().toString(), "Synthetic",
            EntryData.Custom(mapOf("secret" to Field(secret))), timestamp, timestamp)))
        var shown = false
        epochs.deliver(operation, result) { shown = true }
        assertFalse(shown)
        assertThrows(IllegalStateException::class.java) { secret.useChars { } }
        assertThrows(IllegalStateException::class.java) { epochs.ensure(operation) }
        val next = epochs.capture()
        epochs.deliver(next, Vault()) { snapshot -> shown = true; snapshot!!.close() }
        assertTrue(shown)
    }

    @Test fun `cancelled operation failure does not overwrite locked screen`() {
        val epochs = SessionEpoch()
        val operation = epochs.capture()
        epochs.invalidate()
        epochs.deliver(operation, null) { fail("Expired callback must not alter lock state") }
    }

    @Test fun `locking a controller preserves attempt delay and rejected passwords are erased`() {
        var now = 0L
        val backoff = UnlockBackoff { now }
        VaultController(backoff = backoff).use { controller ->
            backoff.failed()
            controller.lock()
            assertEquals(1000L, controller.unlockDelayMillis())
            val chars = "synthetic-password".toCharArray()
            assertThrows(UnlockDelayedException::class.java) {
                controller.unlock(java.nio.file.Path.of("never-read.keyrook"), chars, null, false)
            }
            assertTrue(chars.all { it == '\u0000' })
            assertEquals(1000L, controller.unlockDelayMillis())
            now += 1_000_000_000
            assertEquals(0L, controller.unlockDelayMillis())
        }
    }

    @Test fun `only rejected credentials delay the next unlock attempt`() {
        val backoff = UnlockBackoff { 0L }
        VaultController(backoff = backoff).use { controller ->
            val file = directory.resolve("backoff.keyrook")
            controller.unlock(file, "synthetic-master-passphrase".toCharArray(), null, true,
                app.keyrook.core.crypto.KdfParameters(iterations = 1)).close()
            controller.lock()
            assertThrows(Exception::class.java) {
                controller.unlock(directory.resolve("absent.keyrook"), "synthetic-password".toCharArray(), null, false)
            }
            val corrupt = Files.write(directory.resolve("corrupt.keyrook"), ByteArray(64))
            assertThrows(Exception::class.java) { controller.unlock(corrupt, "synthetic-password".toCharArray(), null, false) }
            val shortKey = Files.write(directory.resolve("short.key"), ByteArray(8))
            assertThrows(Exception::class.java) { controller.unlock(file, "synthetic-master-passphrase".toCharArray(), shortKey, false) }
            assertEquals(0L, controller.unlockDelayMillis())
            assertThrows(app.keyrook.core.crypto.AuthenticationException::class.java) {
                controller.unlock(file, "wrong-synthetic-passphrase".toCharArray(), null, false)
            }
            assertEquals(1000L, controller.unlockDelayMillis())
        }
    }
}
