// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Frame
import java.awt.event.WindowEvent

class DesktopActivityTest {
    @Test fun `first input after deadline requests lock instead of reviving session`() {
        var now = 0L
        val deadline = InactivityDeadline { now }
        var locks = 0
        now = 60_000_000_000L
        handleDesktopActivity(true, 1, deadline) { locks++ }
        assertEquals(1, locks)
        assertTrue(deadline.expired(1))
    }

    @Test fun `first input after a suspend requests lock before it can refresh the deadline`() {
        var nanos = 0L
        var wall = 1_700_000_000_000L
        val deadline = InactivityDeadline({ wall }, { nanos })
        var locks = 0
        nanos += 1_000_000_000L
        wall += 8 * 3_600_000L
        handleDesktopActivity(true, 5, deadline) { locks++ }
        assertEquals(1, locks)
        assertTrue(deadline.expired(5), "The input must not have reset the deadline")
        handleDesktopActivity(false, 5, deadline) { fail("No active session") }
        assertFalse(deadline.expired(5))
    }

    @Test fun `input before expiry refreshes deadline and inactive input prepares next session`() {
        var now = 0L
        val deadline = InactivityDeadline { now }
        now = 59_000_000_000L
        handleDesktopActivity(true, 1, deadline) { fail("Session has not expired") }
        now = 60_000_000_000L
        assertFalse(deadline.expired(1))
        now = 119_000_000_000L
        assertTrue(deadline.expired(1))
        handleDesktopActivity(false, 1, deadline) { fail("No active session") }
        assertFalse(deadline.expired(1))
    }

    @Test fun `each window lock policy locks exactly on its window events`() {
        var consulted = 0
        fun locks(policy: WindowLockPolicy, id: Int, oldState: Int = Frame.NORMAL, newState: Int = Frame.NORMAL, leaves: Boolean = true) =
            windowEventLocks(policy, id, oldState, newState) { consulted++; leaves }
        val focusLoss = WindowEvent.WINDOW_DEACTIVATED
        val iconified = WindowEvent.WINDOW_ICONIFIED
        val stateChanged = WindowEvent.WINDOW_STATE_CHANGED
        assertTrue(locks(WindowLockPolicy.FOCUS_LOSS, focusLoss))
        assertFalse(locks(WindowLockPolicy.FOCUS_LOSS, focusLoss, leaves = false), "Own dialogs must not lock")
        assertTrue(locks(WindowLockPolicy.FOCUS_LOSS, iconified))
        assertTrue(locks(WindowLockPolicy.FOCUS_LOSS, stateChanged, newState = Frame.ICONIFIED))
        assertEquals(2, consulted)
        assertFalse(locks(WindowLockPolicy.MINIMIZE, focusLoss))
        assertTrue(locks(WindowLockPolicy.MINIMIZE, iconified))
        assertTrue(locks(WindowLockPolicy.MINIMIZE, stateChanged, newState = Frame.ICONIFIED or Frame.MAXIMIZED_BOTH))
        assertFalse(locks(WindowLockPolicy.NEVER, focusLoss))
        assertFalse(locks(WindowLockPolicy.NEVER, iconified))
        assertFalse(locks(WindowLockPolicy.NEVER, stateChanged, newState = Frame.ICONIFIED))
        assertEquals(2, consulted, "Focus loss is only inspected when it can lock")
        WindowLockPolicy.entries.forEach { policy ->
            assertFalse(locks(policy, stateChanged, oldState = Frame.ICONIFIED, newState = Frame.NORMAL), "Restoring must not lock")
            assertFalse(locks(policy, stateChanged, oldState = Frame.ICONIFIED, newState = Frame.ICONIFIED))
            assertFalse(locks(policy, stateChanged, newState = Frame.MAXIMIZED_BOTH))
            listOf(WindowEvent.WINDOW_ACTIVATED, WindowEvent.WINDOW_DEICONIFIED, WindowEvent.WINDOW_OPENED,
                WindowEvent.WINDOW_LOST_FOCUS, WindowEvent.WINDOW_CLOSING).forEach { assertFalse(locks(policy, it), "$policy $it") }
        }
        assertEquals(WindowLockPolicy.MINIMIZE, AppSettings().windowLock)
    }

    @Test fun `expired activity stays expired across signed monotonic clock wrap`() {
        var now = Long.MAX_VALUE - 10_000_000_000L
        val deadline = InactivityDeadline { now }
        now += 60_000_000_000L
        var locked = false
        handleDesktopActivity(true, 1, deadline) { locked = true }
        assertTrue(locked)
        assertTrue(deadline.expired(1))
    }
}
