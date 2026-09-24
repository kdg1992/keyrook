// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class DialogBridgeTest {
    private val worker = Executors.newCachedThreadPool()

    @AfterEach fun stopWorkers() { worker.shutdownNow() }

    private fun DialogBridge.awaitShown(): DialogRequest<*> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            current?.let { return it }
            check(System.nanoTime() < deadline) { "No dialog shown" }
            Thread.sleep(5)
        }
    }

    private fun <T> Future<T>.result(): T = get(5, TimeUnit.SECONDS)

    private fun failure(future: Future<*>): Throwable =
        assertThrows(ExecutionException::class.java) { future.result() }.cause!!

    private fun input(password: String, repeated: String = password) =
        CredentialInput(password.toCharArray(), repeated.toCharArray(), "")

    private fun CredentialInput.erased() = password.all { it == '\u0000' } && repeated.all { it == '\u0000' }

    @Test fun `worker receives the answer of the shown request`() {
        val changes = AtomicInteger()
        val bridge = DialogBridge(changed = { changes.incrementAndGet() })
        val request = ConfirmRequest("Keyrook", "Continue?")
        val answer = worker.submit<Boolean?> { bridge.ask(request) {} }
        assertSame(request, bridge.awaitShown())
        assertFalse(answer.isDone)
        assertTrue(bridge.answer(request, true))
        assertEquals(true, answer.result())
        assertNull(bridge.current)
        assertTrue(changes.get() >= 2)
    }

    @Test fun `requests are shown one at a time in order`() {
        val changes = LinkedBlockingQueue<Unit>()
        val bridge = DialogBridge(changed = { changes.put(Unit) })
        val first = MessageRequest("Keyrook", "first")
        val firstAnswer = worker.submit<Unit?> { bridge.ask(first) {} }
        assertNotNull(changes.poll(5, TimeUnit.SECONDS))
        assertSame(first, bridge.current)
        val second = ChoiceRequest("Keyrook", listOf("a", "b"))
        val secondAnswer = worker.submit<Int?> { bridge.ask(second) {} }
        assertNotNull(changes.poll(5, TimeUnit.SECONDS))
        assertSame(first, bridge.current)
        assertFalse(bridge.answer(second, 1), "Only the shown request can be answered")
        assertTrue(bridge.answer(first, Unit))
        assertEquals(Unit, firstAnswer.result())
        assertSame(second, bridge.current)
        assertTrue(bridge.answer(second, 1))
        assertEquals(1, secondAnswer.result())
    }

    @Test fun `canceling all dialogs answers every waiting worker like cancel`() {
        val changes = LinkedBlockingQueue<Unit>()
        val bridge = DialogBridge(changed = { changes.put(Unit) })
        val confirm = ConfirmRequest("Keyrook", "Continue?")
        val first = worker.submit<Boolean?> { bridge.ask(confirm) {} }
        assertNotNull(changes.poll(5, TimeUnit.SECONDS))
        val choice = ChoiceRequest("Keyrook", listOf("a"))
        val second = worker.submit<Int?> { bridge.ask(choice) {} }
        assertNotNull(changes.poll(5, TimeUnit.SECONDS))
        bridge.cancelAll()
        assertNull(first.result())
        assertNull(second.result())
        assertNull(bridge.current)
        assertFalse(bridge.answer(confirm, true), "A canceled dialog cannot be answered afterwards")
        assertFalse(bridge.answer(choice, 0))
    }

    @Test fun `answer after the session changed is refused and erased`() {
        val session = AtomicBoolean(true)
        val guard = { check(session.get()) { "Operation expired" } }
        val bridge = DialogBridge()
        val request = CredentialRequest("Keyrook", confirm = false, replacing = false)
        val answer = worker.submit<CredentialInput?> { bridge.ask(request, guard) }
        assertSame(request, bridge.awaitShown())
        session.set(false)
        val entered = input("synthetic-password")
        assertTrue(bridge.answer(request, entered))
        assertTrue(failure(answer) is IllegalStateException)
        assertTrue(entered.erased(), "The worker must not keep a password it may no longer use")
    }

    @Test fun `late answer for a canceled request is erased and not delivered`() {
        val bridge = DialogBridge()
        val request = CredentialRequest("Keyrook", confirm = true, replacing = false)
        val answer = worker.submit<CredentialInput?> { bridge.ask(request) {} }
        assertSame(request, bridge.awaitShown())
        bridge.cancelAll()
        assertNull(answer.result())
        val late = input("synthetic-password")
        assertFalse(bridge.answer(request, late))
        assertTrue(late.erased())
    }

    @Test fun `expired session opens no dialog`() {
        val bridge = DialogBridge()
        val result = worker.submit<Boolean?> { bridge.ask(ConfirmRequest("Keyrook", "Continue?")) { error("Operation expired") } }
        assertTrue(failure(result) is IllegalStateException)
        assertNull(bridge.current)
    }

    @Test fun `closed bridge refuses new requests and cancels open ones`() {
        val bridge = DialogBridge()
        val request = MessageRequest("Keyrook", "open")
        val open = worker.submit<Unit?> { bridge.ask(request) {} }
        assertSame(request, bridge.awaitShown())
        bridge.close()
        assertNull(open.result())
        val refused = worker.submit<Unit?> { bridge.ask(MessageRequest("Keyrook", "late")) {} }
        assertTrue(failure(refused) is IllegalStateException)
        assertNull(bridge.current)
    }

    @Test fun `requests from the UI thread and reused requests are refused`() {
        val onUi = DialogBridge(uiThread = { true })
        assertThrows(IllegalStateException::class.java) { onUi.ask(MessageRequest("Keyrook", "blocked")) {} }
        assertNull(onUi.current)
        val bridge = DialogBridge()
        val request = ConfirmRequest("Keyrook", "once")
        val first = worker.submit<Boolean?> { bridge.ask(request) {} }
        assertSame(request, bridge.awaitShown())
        bridge.answer(request, false)
        assertEquals(false, first.result())
        val reused = worker.submit<Boolean?> { bridge.ask(request) {} }
        assertTrue(failure(reused) is IllegalStateException)
    }

    @Test fun `interrupted worker withdraws its dialog`() {
        val bridge = DialogBridge()
        val request = CredentialRequest("Keyrook", confirm = false, replacing = false)
        val waiting = worker.submit<CredentialInput?> { bridge.ask(request) {} }
        assertSame(request, bridge.awaitShown())
        waiting.cancel(true)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (bridge.current != null) { check(System.nanoTime() < deadline); Thread.sleep(5) }
        val late = input("synthetic-password")
        assertFalse(bridge.answer(request, late))
        assertTrue(late.erased())
    }

    @Test fun `credential input erases both passwords on close`() {
        val entered = input("synthetic", "synthetic")
        entered.close()
        assertTrue(entered.erased())
        val request = CredentialRequest("Keyrook", confirm = true, replacing = true)
        val discarded = input("other")
        request.discard(discarded)
        assertTrue(discarded.erased())
    }
}
