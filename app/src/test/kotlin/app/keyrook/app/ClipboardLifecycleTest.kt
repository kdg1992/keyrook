// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.util.concurrent.Delayed
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClipboardLifecycleTest {
    @Test fun `expiry clears through wrapper and erases locally retained characters`() {
        val clipboard = TestClipboard()
        val timer = ManualTimer()
        timer.guard(clipboard).use { guard ->
            guard.copy("synthetic-secret")
            val owned = clipboard.getContents(null)!!
            assertEquals(1000L, timer.tasks.single().millis)
            assertEquals("synthetic-secret", clipboard.text())
            val markerFlavor = owned.transferDataFlavors.single { it != DataFlavor.stringFlavor }
            assertTrue(markerFlavor.isMimeTypeEqual(DataFlavor.javaJVMLocalObjectMimeType))
            assertFalse(owned.getTransferData(markerFlavor) is java.io.Serializable)
            timer.elapse(19)
            assertEquals("synthetic-secret", clipboard.text())
            assertEquals(1, timer.pending())
            timer.elapse(1)
            assertEquals("", clipboard.text())
            assertErased(owned)
            assertEquals(0, timer.pending())
        }
        assertTrue(timer.isShutdown)
    }

    @Test fun `identical text from another owner survives before delayed ownership callback`() {
        val clipboard = TestClipboard()
        val timer = ManualTimer()
        timer.guard(clipboard).use { guard ->
            guard.copy("synthetic-secret")
            val owned = clipboard.getContents(null)!!
            clipboard.setContents(StringSelection("synthetic-secret"), null)
            val replacement = clipboard.getContents(null)
            guard.clear()
            assertSame(replacement, clipboard.getContents(null))
            assertEquals("synthetic-secret", clipboard.text())
            clipboard.deliverCallbacks()
            assertErased(owned)
        }
    }

    @Test fun `wrapped ownership callbacks erase old content without canceling newer expiry`() {
        val clipboard = TestClipboard()
        val timer = ManualTimer()
        timer.guard(clipboard).use { guard ->
            guard.copy("first-secret")
            val first = clipboard.getContents(null)!!
            guard.copy("second-secret")
            clipboard.deliverCallbacks()
            assertErased(first)
            assertEquals("second-secret", clipboard.text())
            assertEquals(1, timer.pending())
            val second = clipboard.getContents(null)!!
            clipboard.setContents(StringSelection("external"), null)
            clipboard.deliverCallbacks()
            assertErased(second)
            assertEquals(0, timer.pending())
            assertEquals("external", clipboard.text())
        }
    }

    @Test fun `close retries busy clipboard before shutting down and refuses further writes`() {
        val clipboard = TestClipboard()
        val timer = ManualTimer()
        val guard = timer.guard(clipboard)
        guard.copy("synthetic-secret")
        val owned = clipboard.getContents(null)!!
        clipboard.busy = true
        guard.close()
        assertErased(owned)
        assertFalse(timer.isShutdown)
        repeat(3) { timer.runNext() }
        clipboard.busy = false
        timer.runNext()
        assertEquals("", clipboard.text())
        assertTrue(timer.isShutdown)
        assertThrows(IllegalStateException::class.java) { guard.copy("must-not-appear") }
        assertThrows(IllegalStateException::class.java) { guard.configure(20) }
        guard.close()
        assertEquals("", clipboard.text())
        assertEquals(0, timer.pending())
    }

    @Test fun `permanently busy clipboard has bounded close retries and releases owned secret`() {
        val clipboard = TestClipboard()
        val timer = ManualTimer()
        val guard = timer.guard(clipboard)
        guard.copy("synthetic-secret")
        val owned = clipboard.getContents(null)!!
        clipboard.busy = true
        guard.close()
        repeat(10) { timer.runNext() }
        assertTrue(timer.isShutdown)
        assertEquals(0, timer.pending())
        assertErased(owned)
        guard.close()
    }

    @Test fun `repeated busy clears keep one retry and expired old task cannot clear new copy`() {
        val clipboard = TestClipboard()
        val timer = ManualTimer()
        timer.guard(clipboard).use { guard ->
            guard.copy("first-secret")
            val oldExpiry = timer.tasks.single()
            clipboard.busy = true
            repeat(5) { guard.clear(); assertEquals(1, timer.pending()) }
            clipboard.busy = false
            guard.copy("second-secret")
            oldExpiry.action.run()
            clipboard.deliverCallbacks()
            assertEquals("second-secret", clipboard.text())
            assertEquals(1, timer.pending())
        }
    }

    @Test fun `failed replacement preserves original expiry and clears previous native content`() {
        val clipboard = TestClipboard()
        val timer = ManualTimer()
        timer.guard(clipboard).use { guard ->
            guard.copy("first-secret")
            val original = clipboard.getContents(null)
            clipboard.busy = true
            assertThrows(IllegalStateException::class.java) { guard.copy("rejected-secret") }
            assertSame(original, clipboard.getContents(null))
            assertEquals("first-secret", clipboard.nativeText)
            assertEquals(1, timer.pending())
            clipboard.busy = false
            timer.elapse(20)
            assertEquals("", clipboard.nativeText)
            assertEquals("", clipboard.text())
        }
    }

    @Test fun `expiry that elapsed during a suspend clears on the first check after resume`() {
        val clipboard = TestClipboard()
        val timer = ManualTimer()
        timer.guard(clipboard).use { guard ->
            guard.copy("synthetic-secret")
            val owned = clipboard.getContents(null)!!
            timer.elapse(5)
            timer.suspend(20)
            assertEquals("synthetic-secret", clipboard.text(), "Nothing runs while the machine is suspended")
            timer.runNext()
            assertEquals("", clipboard.text())
            assertErased(owned)
            assertEquals(0, timer.pending())
        }
    }

    @Test fun `backward wall clock does not extend clipboard expiry`() {
        val clipboard = TestClipboard()
        val timer = ManualTimer()
        timer.guard(clipboard).use { guard ->
            guard.copy("synthetic-secret")
            timer.elapse(10)
            timer.wall -= 3_600_000
            timer.elapse(9)
            assertEquals("synthetic-secret", clipboard.text())
            timer.elapse(1)
            assertEquals("", clipboard.text())
        }
    }

    private fun assertErased(contents: Transferable) {
        assertTrue((contents.getTransferData(DataFlavor.stringFlavor) as String).all { it == '\u0000' })
    }

    /** Native AWT implementations wrap transfers and dispatch ownership callbacks later. */
    private class TestClipboard : Clipboard("synthetic-only") {
        private var value: Transferable? = null
        private var holder: ClipboardOwner? = null
        private val callbacks = mutableListOf<() -> Unit>()
        var busy = false
        var nativeText = ""
            private set
        override fun getContents(requestor: Any?): Transferable? = value
        override fun setContents(contents: Transferable, owner: ClipboardOwner?) {
            if (busy) throw IllegalStateException("Clipboard is busy")
            nativeText = contents.getTransferData(DataFlavor.stringFlavor) as String
            val previous = value
            val previousOwner = holder
            value = object : Transferable by contents {}
            holder = owner
            if (previousOwner != null && previousOwner !== owner) {
                callbacks += { previousOwner.lostOwnership(this, previous) }
            }
        }
        fun text(): String = value!!.getTransferData(DataFlavor.stringFlavor) as String
        fun deliverCallbacks() {
            val pending = callbacks.toList()
            callbacks.clear()
            pending.forEach { it() }
        }
    }

    /** Runs tasks on a synthetic monotonic clock; the wall clock follows it except during a simulated suspend. */
    private class ManualTimer : ScheduledThreadPoolExecutor(1) {
        val tasks = mutableListOf<Task>()
        var nanos = 0L
        var wall = 1_700_000_000_000L
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            if (isShutdown) throw RejectedExecutionException()
            return Task(command, unit.toMillis(delay), nanos + unit.toNanos(delay)).also { tasks += it }
        }
        fun guard(clipboard: Clipboard) = ClipboardGuard(clipboard, this, { nanos }, { wall })
        fun pending(): Int = tasks.count { !it.isDone }
        fun runNext() {
            val next = tasks.filter { !it.isDone }.minBy { it.due }
            advanceTo(next.due)
            next.run()
        }
        fun elapse(seconds: Long) {
            val until = nanos + seconds * 1_000_000_000
            while (true) {
                val next = tasks.filter { !it.isDone && it.due <= until }.minByOrNull { it.due } ?: break
                advanceTo(next.due)
                next.run()
            }
            advanceTo(until)
        }
        fun suspend(seconds: Long) { wall += seconds * 1000 }
        private fun advanceTo(target: Long) {
            if (target <= nanos) return
            wall += (target - nanos) / 1_000_000
            nanos = target
        }
    }

    private class Task(val action: Runnable, val millis: Long, val due: Long) : FutureTask<Unit>(action, Unit), ScheduledFuture<Unit> {
        override fun getDelay(unit: TimeUnit): Long = unit.convert(millis, TimeUnit.MILLISECONDS)
        override fun compareTo(other: Delayed): Int = getDelay(TimeUnit.NANOSECONDS).compareTo(other.getDelay(TimeUnit.NANOSECONDS))
    }
}
