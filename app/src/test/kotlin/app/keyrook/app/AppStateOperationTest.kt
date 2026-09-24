// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities

class AppStateOperationTest {
    @AfterEach fun restoreGerman() { UiText.select(AppLanguage.GERMAN) }

    private fun <T> onEdt(action: () -> T): T {
        var result: T? = null
        SwingUtilities.invokeAndWait { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Waits until the operation's result was delivered on the UI thread. */
    private fun awaitIdle(state: AppState) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (onEdt { state.busy }) {
            assertTrue(System.nanoTime() < deadline, "operation did not finish")
            Thread.sleep(5)
        }
    }

    @Test fun `the success callback runs only after the operation succeeded`() {
        val state = onEdt { AppState() }
        try {
            val calls = mutableListOf<String>()
            onEdt { state.operation(onSuccess = { calls += "refused" }) { throw UserFacingException("error.projectInUse") } }
            awaitIdle(state)
            assertTrue(calls.isEmpty())
            assertEquals(UiText.text("error.projectInUse"), onEdt { state.message })
            onEdt { state.operation(onSuccess = { calls += "unknown" }) { error("/synthetic/path") } }
            awaitIdle(state)
            assertTrue(calls.isEmpty())
            assertEquals(UiText.text("shell.failed"), onEdt { state.message })
            onEdt { state.operation(onSuccess = { calls += "saved" }) { null } }
            awaitIdle(state)
            assertEquals(listOf("saved"), calls)
            assertEquals("", onEdt { state.message })
        } finally { onEdt { state.dispose() } }
    }

    @Test fun `an operation requested while another runs is ignored with its callback`() {
        val state = onEdt { AppState() }
        try {
            val release = java.util.concurrent.CountDownLatch(1)
            val calls = mutableListOf<String>()
            onEdt {
                state.operation(onSuccess = { calls += "first" }) { release.await(); null }
                state.operation(onSuccess = { calls += "second" }) { null }
            }
            release.countDown()
            awaitIdle(state)
            assertEquals(listOf("first"), calls)
        } finally { onEdt { state.dispose() } }
    }
}
