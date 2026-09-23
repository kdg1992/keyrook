// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import java.awt.Frame
import java.awt.GraphicsEnvironment
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
                monitor = DesktopLockMonitor({ active }, { 5 }, {
                    check(SwingUtilities.isEventDispatchThread())
                    locks++; active = false
                }, InactivityDeadline())
                frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_DEACTIVATED, dialog))
                check(locks == 0) { "An application dialog must not lock its owning vault" }
                dialog.dispatchEvent(WindowEvent(dialog, WindowEvent.WINDOW_DEACTIVATED, frame))
                check(locks == 0)
                frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_DEACTIVATED))
                check(locks == 1) { "Leaving application windows must lock" }
                active = true
                frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_ICONIFIED))
                check(locks == 2) { "Minimizing must lock" }
                monitor.close()
                active = true
                frame.dispatchEvent(WindowEvent(frame, WindowEvent.WINDOW_ICONIFIED))
                check(locks == 2) { "A closed monitor must not receive events" }
                check(toolkit.awtEventListeners.size == before)
            } finally {
                try { monitor?.close() } finally { dialog.dispose(); frame.dispose() }
            }
            check(!frame.isDisplayable && !dialog.isDisplayable)
        }
        println("Keyrook native window lifecycle and synthetic event routing check passed")
    }
}
