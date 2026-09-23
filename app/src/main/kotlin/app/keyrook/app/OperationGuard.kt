// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

private val operationGuard = ThreadLocal<(() -> Unit)?>()

internal fun <T> withOperationGuard(controller: VaultController, token: Long, action: () -> T): T {
    val previous = operationGuard.get()
    operationGuard.set { controller.sessionEpoch.ensure(token) }
    // Enter even canceled operations so their owned buffers can be cleared in finally blocks.
    return try { action() } finally {
        if (previous == null) operationGuard.remove() else operationGuard.set(previous)
    }
}

internal fun ensureOperationCurrent() { operationGuard.get()?.invoke() }

/** Capture before switching threads; EDT dialogs must check the worker's session too. */
internal fun capturedOperationGuard(): () -> Unit = operationGuard.get() ?: {}
