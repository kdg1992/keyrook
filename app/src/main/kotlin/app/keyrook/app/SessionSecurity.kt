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

/**
 * Measures elapsed time with the monotonic clock and, because that clock can pause while the machine is suspended,
 * also with forward wall-clock steps. Backward wall-clock steps are ignored, so they never extend a limit. A wall clock
 * running at least [SUSPEND_GAP_MILLIS] ahead of the monotonic clock between two observations counts as a suspend and
 * reaches every limit until [restart].
 */
internal class ElapsedTime(private val nanoTime: () -> Long, private val wallMillis: () -> Long) {
    private var startNanos = 0L
    private var lastNanos = 0L
    private var lastWall = 0L
    private var wallElapsedMillis = 0L
    private var suspended = false
    init { restart() }

    fun restart() {
        startNanos = nanoTime()
        lastNanos = startNanos
        lastWall = wallMillis()
        wallElapsedMillis = 0
        suspended = false
    }

    fun reached(limitNanos: Long): Boolean {
        val nanos = nanoTime()
        val wall = wallMillis()
        val wallDelta = wall - lastWall
        if (wallDelta > 0) {
            wallElapsedMillis = if (wallDelta > Long.MAX_VALUE - wallElapsedMillis) Long.MAX_VALUE else wallElapsedMillis + wallDelta
            if (wallDelta - (nanos - lastNanos) / 1_000_000 >= SUSPEND_GAP_MILLIS) suspended = true
        }
        lastNanos = nanos
        lastWall = wall
        return suspended || nanos - startNanos >= limitNanos || wallElapsedMillis >= limitNanos / 1_000_000
    }

    companion object {
        const val SUSPEND_GAP_MILLIS = 30_000L
    }
}

/** [wallMillis] precedes [nanoTime] so a single trailing lambda still supplies the monotonic clock. */
internal class InactivityDeadline(
    wallMillis: () -> Long = System::currentTimeMillis,
    nanoTime: () -> Long = System::nanoTime,
) {
    private val elapsed = ElapsedTime(nanoTime, wallMillis)
    @Synchronized fun activity() = elapsed.restart()
    @Synchronized fun activityBeforeExpiry(timeoutMinutes: Int): Boolean {
        if (expired(timeoutMinutes)) return false
        activity()
        return true
    }
    @Synchronized fun expired(timeoutMinutes: Int): Boolean {
        require(timeoutMinutes in 1..30)
        return elapsed.reached(timeoutMinutes * 60_000_000_000L)
    }
}
