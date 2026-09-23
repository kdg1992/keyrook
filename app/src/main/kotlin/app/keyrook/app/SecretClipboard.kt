// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A later clipboard owner is never cleared. OS clipboard history cannot be erased by this application. */
internal class ClipboardGuard(private val clipboard: Clipboard) : ClipboardOwner, AutoCloseable {
    private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "clipboard-expiry").apply { isDaemon = true } }
    private var current: StringSelection? = null
    private var expiration: ScheduledFuture<*>? = null
    private var expirySeconds = 20L

    @Synchronized fun configure(seconds: Long) {
        require(seconds in 5..120)
        clear()
        expirySeconds = seconds
    }

    @Synchronized fun copy(text: String) {
        val selection = StringSelection(text)
        clipboard.setContents(selection, this)
        current = selection
        expiration?.cancel(false)
        expiration = timer.schedule({ clearOwned(selection) }, expirySeconds, TimeUnit.SECONDS)
    }

    @Synchronized fun clear() {
        val selection = current ?: return
        clearOwned(selection)
    }

    @Synchronized private fun clearOwned(selection: StringSelection) {
        if (current !== selection) return
        try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor) &&
                clipboard.getData(DataFlavor.stringFlavor) == selection.getTransferData(DataFlavor.stringFlavor)) {
                clipboard.setContents(StringSelection(""), null)
            }
            current = null
            expiration?.cancel(false)
        } catch (_: IllegalStateException) {
            // Another application can temporarily hold the OS clipboard open.
            expiration = timer.schedule({ clearOwned(selection) }, 1, TimeUnit.SECONDS)
        }
    }

    @Synchronized override fun lostOwnership(clipboard: Clipboard, contents: Transferable) {
        if (contents === current) { current = null; expiration?.cancel(false) }
    }

    override fun close() { clear(); timer.shutdown() }
}

internal object SecretClipboard {
    private var expirySeconds = 20L
    private val initializedGuard = lazy { ClipboardGuard(Toolkit.getDefaultToolkit().systemClipboard) }
    private val guard by initializedGuard
    fun configure(seconds: Long) {
        require(seconds in 5..120)
        expirySeconds = seconds
        if (initializedGuard.isInitialized()) guard.configure(seconds)
    }
    fun copy(text: String) { guard.configure(expirySeconds); guard.copy(text) }
    fun clear() { if (initializedGuard.isInitialized()) guard.clear() }
}
