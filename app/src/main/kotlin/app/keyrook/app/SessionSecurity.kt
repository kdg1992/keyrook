// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import java.util.concurrent.atomic.AtomicLong
import app.keyrook.core.model.Vault

internal class SessionEpoch {
    private val current = AtomicLong()
    fun capture(): Long = current.get()
    fun invalidate(): Long = current.incrementAndGet()
    fun accepts(token: Long): Boolean = current.get() == token
    fun ensure(token: Long) { check(accepts(token)) { "Operation expired" } }
    /** Invalidation and delivery run on the UI thread; rejected plaintext is closed immediately. */
    fun deliver(token: Long, snapshot: Vault?, publish: (Vault?) -> Unit) {
        if (accepts(token)) publish(snapshot) else snapshot?.close()
    }
}

class UnlockDelayedException : Exception("Unlock temporarily delayed")

/** Monotonic in-process throttling; does not claim to prevent offline attacks against copied files. */
class UnlockBackoff(private val nanoTime: () -> Long = System::nanoTime) {
    private var failures = 0
    private var failedAt = 0L
    private var delayNanos = 0L

    @Synchronized fun remainingMillis(): Long {
        if (delayNanos == 0L) return 0
        val remaining = delayNanos - (nanoTime() - failedAt)
        return if (remaining <= 0) 0 else (remaining + 999_999) / 1_000_000
    }
    @Synchronized fun requireReady() { if (remainingMillis() != 0L) throw UnlockDelayedException() }
    @Synchronized fun failed() {
        failures = (failures + 1).coerceAtMost(7)
        failedAt = nanoTime()
        delayNanos = (1L shl (failures - 1)).coerceAtMost(60) * 1_000_000_000
    }
    @Synchronized fun succeeded() { failures = 0; delayNanos = 0 }
}

internal class InactivityDeadline(private val nanoTime: () -> Long = System::nanoTime) {
    private var lastActivity = nanoTime()
    @Synchronized fun activity() { lastActivity = nanoTime() }
    @Synchronized fun activityBeforeExpiry(timeoutMinutes: Int): Boolean {
        if (expired(timeoutMinutes)) return false
        activity()
        return true
    }
    @Synchronized fun expired(timeoutMinutes: Int): Boolean {
        require(timeoutMinutes in 1..30)
        return nanoTime() - lastActivity >= timeoutMinutes * 60_000_000_000L
    }
}
