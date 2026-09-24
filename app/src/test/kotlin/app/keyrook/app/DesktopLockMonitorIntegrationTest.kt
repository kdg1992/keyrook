// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** Real AWT dispatch and registration, with a synthetic clock and synthetic input events. */
class DesktopLockMonitorIntegrationTest {
    @Test fun `AWT dispatch refreshes activity but cannot revive an expired session`() {
        SwingUtilities.invokeAndWait {
            var now = 0L
            var active = true
            var locks = 0
            var delivered = 0
            val deadline = InactivityDeadline { now }
            val panel = JPanel().apply {
                addMouseListener(object : MouseAdapter() {
                    override fun mousePressed(event: MouseEvent) { delivered++ }
                })
            }
            DesktopLockMonitor({ active }, { 1 }, {
                assertTrue(SwingUtilities.isEventDispatchThread())
                locks++; active = false
            }, deadline, { WindowLockPolicy.NEVER }).use {
                fun input() = panel.dispatchEvent(MouseEvent(panel, MouseEvent.MOUSE_PRESSED,
                    System.currentTimeMillis(), 0, 0, 0, 1, false, MouseEvent.BUTTON1))
                now = 59_000_000_000L
                input()
                now = 118_000_000_000L
                assertFalse(deadline.expired(1))
                assertEquals(0, locks)
                now = 119_000_000_000L
                input()
                assertEquals(1, locks)
                assertTrue(deadline.expired(1))
                input()
                assertFalse(deadline.expired(1))
                assertEquals(1, locks)
                assertEquals(3, delivered)
            }
        }
    }

    @Test fun `closing monitor unregisters toolkit listener and stops further input callbacks`() {
        SwingUtilities.invokeAndWait {
            val toolkit = Toolkit.getDefaultToolkit()
            val before = toolkit.awtEventListeners.size
            var checks = 0
            var locks = 0
            var now = 0L
            val deadline = InactivityDeadline { now }
            val monitor = DesktopLockMonitor({ checks++; true }, { 1 }, { locks++ }, deadline, { WindowLockPolicy.FOCUS_LOSS })
            assertEquals(before + 1, toolkit.awtEventListeners.size)
            monitor.close()
            monitor.close()
            assertEquals(before, toolkit.awtEventListeners.size)
            now = 60_000_000_000L
            val panel = JPanel()
            panel.dispatchEvent(MouseEvent(panel, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                0, 0, 0, 1, false, MouseEvent.BUTTON1))
            assertEquals(0, locks)
            assertEquals(0, checks)
        }
    }

    @Test fun `operating system session and sleep events lock under every window policy`() {
        SwingUtilities.invokeAndWait {
            WindowLockPolicy.entries.forEach { policy ->
                var active = true
                var locks = 0
                var policyReads = 0
                DesktopLockMonitor({ active }, { 30 }, {
                    assertTrue(SwingUtilities.isEventDispatchThread())
                    locks++; active = false
                }, InactivityDeadline(), { policyReads++; policy }).use { monitor ->
                    monitor.systemLockEvent()
                    assertEquals(1, locks, policy.name)
                    monitor.systemLockEvent()
                    assertEquals(1, locks, "An inactive session is not locked again")
                    active = true
                    monitor.close()
                    monitor.systemLockEvent()
                    assertEquals(1, locks, "A closed monitor must not lock")
                }
                assertEquals(0, policyReads, "Session events must not depend on the window policy")
            }
        }
    }

    @Test fun `real Swing timer forwards idle timeout to EDT without input`() {
        val locked = CountDownLatch(1)
        val onEdt = AtomicBoolean(false)
        var monitor: DesktopLockMonitor? = null
        try {
            SwingUtilities.invokeAndWait {
                var now = 0L
                var active = true
                val deadline = InactivityDeadline { now }
                // The inactivity deadline stays active even when no window event may lock.
                monitor = DesktopLockMonitor({ active }, { 1 }, {
                    active = false
                    onEdt.set(SwingUtilities.isEventDispatchThread())
                    locked.countDown()
                }, deadline, { WindowLockPolicy.NEVER })
                now = 60_000_000_000L
            }
            assertTrue(locked.await(5, TimeUnit.SECONDS), "Swing timer did not deliver the idle timeout")
            assertTrue(onEdt.get())
        } finally { SwingUtilities.invokeAndWait { monitor?.close() } }
    }

    @Test fun `real Swing timer locks on its first tick after a suspend paused the monotonic clock`() {
        val locked = CountDownLatch(1)
        var monitor: DesktopLockMonitor? = null
        try {
            SwingUtilities.invokeAndWait {
                var active = true
                var wall = System.currentTimeMillis()
                val deadline = InactivityDeadline({ wall }, { 0L })
                monitor = DesktopLockMonitor({ active }, { 30 }, {
                    active = false
                    locked.countDown()
                }, deadline, { WindowLockPolicy.NEVER })
                wall += 8 * 3_600_000L
            }
            assertTrue(locked.await(5, TimeUnit.SECONDS), "Swing timer did not lock after the suspend")
        } finally { SwingUtilities.invokeAndWait { monitor?.close() } }
    }
}
