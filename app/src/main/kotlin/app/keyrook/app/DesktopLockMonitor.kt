// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import java.awt.AWTEvent
import java.awt.Desktop
import java.awt.Frame
import java.awt.Toolkit
import java.awt.Window
import java.awt.desktop.*
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Desktop session events are platform-dependent. Session, sleep and inactivity locking always apply; [windowLock] is read
 * at each window event and decides whether minimizing or leaving this application's windows also locks.
 */
internal class DesktopLockMonitor(
    private val active: () -> Boolean,
    private val timeoutMinutes: () -> Int,
    private val lock: () -> Unit,
    private val deadline: InactivityDeadline,
    private val windowLock: () -> WindowLockPolicy,
) : AutoCloseable {
    @Volatile private var closed = false
    private val toolkit = Toolkit.getDefaultToolkit()
    private val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
    private fun requestLock() {
        if (SwingUtilities.isEventDispatchThread()) { if (!closed && active()) lock() }
        else SwingUtilities.invokeLater { if (!closed && active()) lock() }
    }
    private val events = AWTEventListener { event ->
        if (!closed) when (event) {
            is InputEvent -> handleDesktopActivity(active(), timeoutMinutes(), deadline, ::requestLock)
            is WindowEvent -> if (windowEventLocks(windowLock(), event.id, event.oldState, event.newState) {
                // Swing's null-owner file choosers have a shared JVM owner, not the Compose frame.
                event.oppositeWindow !in Window.getWindows().toSet()
            }) requestLock()
        }
    }

    /** Operating-system session and sleep notifications lock under every [WindowLockPolicy]. */
    internal fun systemLockEvent() = requestLock()
    private val sessionListener = object : UserSessionListener {
        override fun userSessionDeactivated(event: UserSessionEvent) = systemLockEvent()
        override fun userSessionActivated(event: UserSessionEvent) = Unit
    }
    private val screenListener = object : ScreenSleepListener {
        override fun screenAboutToSleep(event: ScreenSleepEvent) = systemLockEvent()
        override fun screenAwoke(event: ScreenSleepEvent) = Unit
    }
    private val sleepListener = object : SystemSleepListener {
        override fun systemAboutToSleep(event: SystemSleepEvent) = systemLockEvent()
        override fun systemAwoke(event: SystemSleepEvent) = Unit
    }
    private val registered = mutableListOf<SystemEventListener>()
    private val timer = Timer(500) { if (!closed && active() && deadline.expired(timeoutMinutes())) requestLock() }
    init {
        try {
            toolkit.addAWTEventListener(events, AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or
                AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK or AWTEvent.WINDOW_EVENT_MASK)
            listOf<Pair<Desktop.Action, SystemEventListener>>(Desktop.Action.APP_EVENT_USER_SESSION to sessionListener,
                Desktop.Action.APP_EVENT_SCREEN_SLEEP to screenListener,
                Desktop.Action.APP_EVENT_SYSTEM_SLEEP to sleepListener).forEach { (action, listener) ->
                if (desktop?.isSupported(action) == true) {
                    registered.add(listener)
                    desktop.addAppEventListener(listener)
                }
            }
            timer.start()
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        timer.stop()
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() }
            catch (cause: Throwable) {
                val previous = failure
                if (previous == null) failure = cause else previous.addSuppressed(cause)
            }
        }
        cleanup { toolkit.removeAWTEventListener(events) }
        registered.forEach {
            cleanup { desktop?.removeAppEventListener(it) }
        }
        registered.clear()
        failure?.let { throw it }
    }
}

/**
 * Decides whether a window event locks under [policy]. Minimizing is recognized from WINDOW_ICONIFIED and, for window
 * managers that only report the state change, from a WINDOW_STATE_CHANGED transition into the iconified state.
 * [leavesApplication] is only consulted for focus loss under [WindowLockPolicy.FOCUS_LOSS].
 */
internal fun windowEventLocks(policy: WindowLockPolicy, id: Int, oldState: Int, newState: Int, leavesApplication: () -> Boolean): Boolean {
    val minimized = id == WindowEvent.WINDOW_ICONIFIED || (id == WindowEvent.WINDOW_STATE_CHANGED &&
        (newState and Frame.ICONIFIED) != 0 && (oldState and Frame.ICONIFIED) == 0)
    return when (policy) {
        WindowLockPolicy.FOCUS_LOSS -> minimized || (id == WindowEvent.WINDOW_DEACTIVATED && leavesApplication())
        WindowLockPolicy.MINIMIZE -> minimized
        WindowLockPolicy.NEVER -> false
    }
}

/**
 * Whether a window event masks values shown in the entry details: whenever the strictest choice,
 * [WindowLockPolicy.FOCUS_LOSS], would lock, independent of the selected policy. Any deactivation counts, including
 * a switch to one of Keyrook's own dialogs.
 */
internal fun windowEventMasksValues(id: Int, oldState: Int, newState: Int): Boolean =
    windowEventLocks(WindowLockPolicy.FOCUS_LOSS, id, oldState, newState) { true }

/** A delayed event loop must not let the first key or mouse event revive an expired session. */
internal fun handleDesktopActivity(active: Boolean, timeoutMinutes: Int, deadline: InactivityDeadline, lock: () -> Unit) {
    if (active) {
        if (!deadline.activityBeforeExpiry(timeoutMinutes)) lock()
    } else deadline.activity()
}
