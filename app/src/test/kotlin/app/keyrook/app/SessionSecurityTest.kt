// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SessionSecurityTest {
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
}
