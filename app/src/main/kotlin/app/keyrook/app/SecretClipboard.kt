// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Ownership checks are best effort: AWT has no atomic OS clipboard compare-and-clear. Scheduler delays can pause while
 * the machine is suspended, so expiry is checked at least every [CHECK_MILLIS] against [ElapsedTime] instead of relying
 * on a single delay.
 */
internal class ClipboardGuard(
    private val clipboard: Clipboard,
    private val timer: ScheduledExecutorService = ScheduledThreadPoolExecutor(1) {
        Thread(it, "clipboard-expiry").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true },
    private val nanoTime: () -> Long = System::nanoTime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private var current: OwnedSelection? = null
    private var copied: ElapsedTime? = null
    private var expiration: ScheduledFuture<*>? = null
    private var expirySeconds = 20L
    private var closing = false
    private var closeRetries = 0

    @Synchronized fun configure(seconds: Long) {
        check(!closing) { "Clipboard guard is closed" }
        require(seconds in 5..120)
        clear()
        expirySeconds = seconds
    }

    @Synchronized fun copy(text: String) {
        check(!closing) { "Clipboard guard is closed" }
        val selection = OwnedSelection(text)
        try {
            // Native AWT clipboards wrap Transferable objects, so callback object identity is unreliable.
            clipboard.setContents(selection, ClipboardOwner { _, _ -> relinquish(selection) })
        } catch (failure: RuntimeException) {
            selection.erase()
            throw failure
        }
        expiration?.cancel(false)
        current?.erase()
        current = selection
        copied = ElapsedTime(nanoTime, wallMillis)
        try {
            expiration = timer.schedule({ expireIfDue(selection) }, CHECK_MILLIS, TimeUnit.MILLISECONDS)
        } catch (failure: RuntimeException) {
            clearOwned(selection)
            throw failure
        }
    }

    @Synchronized private fun expireIfDue(selection: OwnedSelection) {
        if (current !== selection) return
        if (copied?.reached(expirySeconds * 1_000_000_000L) != false) return clearOwned(selection)
        try {
            expiration = timer.schedule({ expireIfDue(selection) }, CHECK_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: RuntimeException) {
            clearOwned(selection)
        }
    }

    @Synchronized fun clear() { current?.let { clearOwned(it) } }

    @Synchronized private fun clearOwned(selection: OwnedSelection) {
        if (current !== selection) return
        expiration?.cancel(false)
        expiration = null
        // The OS can retain its own copy, but our local array need not survive a busy clipboard.
        selection.erase()
        try {
            val contents = clipboard.getContents(null)
            if (contents?.isDataFlavorSupported(OWNERSHIP_FLAVOR) == true &&
                contents.getTransferData(OWNERSHIP_FLAVOR) === selection.token) {
                clipboard.setContents(StringSelection(""), null)
            }
            relinquish(selection)
        } catch (_: IllegalStateException) {
            // Keep at most one retry pending. Closing is bounded even if another process never releases it.
            if ((!closing || closeRetries++ < 10) && !timer.isShutdown) {
                expiration = timer.schedule({ clearOwned(selection) }, 1, TimeUnit.SECONDS)
            } else relinquish(selection)
        } catch (_: java.io.IOException) {
            relinquish(selection)
        } catch (_: UnsupportedFlavorException) {
            relinquish(selection)
        }
    }

    @Synchronized private fun relinquish(selection: OwnedSelection) {
        selection.erase()
        if (current !== selection) return
        current = null
        copied = null
        expiration?.cancel(false)
        expiration = null
        if (closing) timer.shutdown()
    }

    @Synchronized override fun close() {
        if (closing) return
        closing = true
        clear()
        if (current == null) timer.shutdown()
    }

    private class OwnedSelection(text: String) : Transferable {
        private val chars = text.toCharArray()
        val token = Any()
        @Synchronized fun erase() { chars.fill('\u0000') }
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.stringFlavor, OWNERSHIP_FLAVOR)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
            flavor == DataFlavor.stringFlavor || flavor == OWNERSHIP_FLAVOR
        @Synchronized override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
            DataFlavor.stringFlavor -> String(chars)
            OWNERSHIP_FLAVOR -> token
            else -> throw UnsupportedFlavorException(flavor)
        }
    }

    private companion object {
        const val CHECK_MILLIS = 1_000L
        val OWNERSHIP_FLAVOR = DataFlavor("${DataFlavor.javaJVMLocalObjectMimeType};class=java.lang.Object", "Keyrook clipboard ownership")
    }
}

internal object SecretClipboard {
    private var expirySeconds = 20L
    private val initializedGuard = lazy { ClipboardGuard(Toolkit.getDefaultToolkit().systemClipboard) }
    private val guard by initializedGuard
    @Synchronized fun configure(seconds: Long) {
        require(seconds in 5..120)
        expirySeconds = seconds
        if (initializedGuard.isInitialized()) guard.configure(seconds)
    }
    @Synchronized fun copy(text: String) { guard.configure(expirySeconds); guard.copy(text) }
    @Synchronized fun clear() { if (initializedGuard.isInitialized()) guard.clear() }
}
