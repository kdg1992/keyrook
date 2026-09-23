// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

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
