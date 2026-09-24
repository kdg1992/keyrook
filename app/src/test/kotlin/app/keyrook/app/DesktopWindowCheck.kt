// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.WindowEvent
import javax.swing.JDialog
import javax.swing.SwingUtilities

/** CI native-peer lifecycle check. Events are synthetic; this does not test operating-system locking. */
object DesktopWindowCheck {
    @JvmStatic fun main(args: Array<String>) {
        require(args.isEmpty())
        check(!GraphicsEnvironment.isHeadless()) { "A display server is required" }
        SwingUtilities.invokeAndWait {
            val toolkit = Toolkit.getDefaultToolkit()
            val frame = Frame("Keyrook synthetic desktop check")
            val dialog = JDialog(frame)
            var monitor: DesktopLockMonitor? = null
            try {
                // Packing allocates native peers without presenting windows or stealing user focus.
                frame.setSize(160, 120)
                frame.addNotify()
                dialog.pack()
                check(frame.isDisplayable && dialog.isDisplayable)
                check(!frame.isVisible && !dialog.isVisible)
                val before = toolkit.awtEventListeners.size
                var active = true
                var locks = 0
                var policy = WindowLockPolicy.FOCUS_LOSS
                monitor = DesktopLockMonitor({ active }, { 5 }, {
                    check(SwingUtilities.isEventDispatchThread())
                    locks++; active = false
                }, InactivityDeadline(), { policy })
                // Native peers stay hidden, so no window is globally active. Redispatch routes
                // controlled events through Toolkit listeners without fabricating OS activation.
                fun dispatch(event: WindowEvent) = KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .redispatchEvent(event.window, event)
                fun locksOn(event: WindowEvent): Boolean {
                    active = true
                    val previous = locks
                    dispatch(event)
                    return locks > previous
                }
                // The policy is read per event, so changing the setting applies without a new monitor.
                WindowLockPolicy.entries.forEach { current ->
                    policy = current
                    val focusLocks = current == WindowLockPolicy.FOCUS_LOSS
                    val minimizeLocks = current != WindowLockPolicy.NEVER
                    check(!locksOn(WindowEvent(frame, WindowEvent.WINDOW_DEACTIVATED, dialog))) {
                        "An application dialog must not lock its owning vault ($current)"
                    }
                    check(!locksOn(WindowEvent(dialog, WindowEvent.WINDOW_DEACTIVATED, frame))) { "Returning to the frame ($current)" }
                    check(locksOn(WindowEvent(frame, WindowEvent.WINDOW_DEACTIVATED)) == focusLocks) {
                        "Leaving application windows under $current"
                    }
                    check(locksOn(WindowEvent(frame, WindowEvent.WINDOW_ICONIFIED)) == minimizeLocks) { "Minimizing under $current" }
                    check(locksOn(WindowEvent(frame, WindowEvent.WINDOW_STATE_CHANGED, Frame.NORMAL, Frame.ICONIFIED)) == minimizeLocks) {
                        "Iconified state change under $current"
                    }
                    check(!locksOn(WindowEvent(frame, WindowEvent.WINDOW_STATE_CHANGED, Frame.ICONIFIED, Frame.NORMAL))) {
                        "Restoring must not lock ($current)"
                    }
                    active = true
                    val previous = locks
                    checkNotNull(monitor).systemLockEvent()
                    check(locks == previous + 1) { "Session and sleep events must lock under $current" }
                }
                val closedLocks = locks
                monitor.close()
                policy = WindowLockPolicy.FOCUS_LOSS
                check(!locksOn(WindowEvent(frame, WindowEvent.WINDOW_ICONIFIED))) { "A closed monitor must not receive events" }
                check(!locksOn(WindowEvent(frame, WindowEvent.WINDOW_DEACTIVATED)))
                check(locks == closedLocks)
                check(toolkit.awtEventListeners.size == before)
            } finally {
                try { monitor?.close() } finally { dialog.dispose(); frame.dispose() }
            }
            check(!frame.isDisplayable && !dialog.isDisplayable)
        }
        println("Keyrook native window lifecycle and synthetic event routing check passed")
    }
}
