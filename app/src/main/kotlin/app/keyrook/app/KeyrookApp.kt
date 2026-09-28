// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import app.keyrook.core.model.*

@Composable
internal fun KeyrookApp(window: java.awt.Window? = null, settings: SettingsStore = remember { SettingsStore.platform() },
                        closeRequested: Boolean = false, onCloseAnswered: (quit: Boolean) -> Unit = {}) {
    val state = remember { AppState() }
    val controller = state.controller
    val vault by state::vault
    val busy by state::busy
    var message by state::message
    var notice by state::notice
    var preferences by remember { mutableStateOf(settings.current().also { UiText.select(it.language) }) }
    val systemDark = isSystemInDarkTheme()
    val dark = when (preferences.theme) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    var about by state::about
    var editing by state::editing
    var creating by state::creating
    val locking by state::locking
    var showSettings by state::showSettings
    var reveal by state::reveal
    var warningsOpen by state::warningsOpen
    var confirmClose by state::confirmClose
    val breachReport = state.breaches.report
    val breached = remember(vault, breachReport) { vault?.let { breachReport?.current(it) }.orEmpty() }
    val localWarnings = vaultWarnings(vault, controller)
    val warnings = remember(localWarnings, breached) { withBreaches(localWarnings, breached) }
    val warningsByEntry = remember(warnings) { warningIssues(warnings.orEmpty()) }
    val updates = rememberUpdateChecks(settings)
    remember { runCatching { SecretClipboard.configure(settings.current().clipboardSeconds) } }
    var unlockDelay by remember { mutableStateOf(0L) }
    val inactivity = state.inactivity
    val shortcuts = remember { ShortcutActions() }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(vault?.id) { rootFocus.requestFocus() }
    val mac = remember { System.getProperty("os.name").startsWith("Mac", ignoreCase = true) }
    val shortcutPrefix = if (mac) "⌘" else UiText.text("shell.ctrlPrefix")
    fun updatePreferences(change: (AppSettings) -> AppSettings) {
        // The file is written on the settings writer; a failed write is reported here, while this window lives.
        settings.update(onWriteFailure = {
            javax.swing.SwingUtilities.invokeLater { if (state.live.get()) message = UiText.text("settings.saveFailed") }
        }, change)
        preferences = settings.current()
        UiText.select(preferences.language)
    }
    fun lockNow() = state.lockNow(onCloseAnswered)
    fun handleShortcut(event: KeyEvent, onlyLock: Boolean): Boolean {
        // Entry-list shortcuts are resolved by the list itself; here focus is outside it.
        val action = keyboardShortcut(event, mac,
            ShortcutContext(vault != null, busy, creating || editing != null, locking, about || warningsOpen)) ?: return false
        if (onlyLock && action != ShortcutAction.LOCK) return false
        when (action) {
            ShortcutAction.LOCK -> lockNow()
            ShortcutAction.NEW_ENTRY -> shortcuts.newEntry?.invoke() ?: return false
            ShortcutAction.SEARCH -> shortcuts.search?.invoke() ?: return false
            ShortcutAction.SAVE -> shortcuts.save?.invoke() ?: return false
            ShortcutAction.CANCEL -> shortcuts.cancel?.invoke() ?: return false
            else -> return false
        }
        return true
    }
    val latestLock by rememberUpdatedState(::lockNow)
    DisposableEffect(window) {
        val lockKeys = if (window == null) null else LockShortcutDispatcher(mac,
            context = { ShortcutContext(vault != null, busy, creating || editing != null, locking, about) },
            lock = { latestLock() })
        val monitor = if (window == null) null else DesktopLockMonitor(
            active = { vault != null || (busy && !locking) }, timeoutMinutes = { preferences.inactivityMinutes },
            lock = { latestLock() }, deadline = inactivity, windowLock = { preferences.windowLock })
        val countdown = javax.swing.Timer(250) { unlockDelay = controller.unlockDelayMillis() }.apply { start() }
        // Shown detail values are masked whenever the strictest window lock choice would lock, whatever is selected.
        val masking = object : java.awt.event.WindowAdapter() {
            override fun windowDeactivated(event: java.awt.event.WindowEvent) = mask(event)
            override fun windowIconified(event: java.awt.event.WindowEvent) = mask(event)
            override fun windowStateChanged(event: java.awt.event.WindowEvent) = mask(event)
            private fun mask(event: java.awt.event.WindowEvent) {
                if (windowEventMasksValues(event.id, event.oldState, event.newState)) reveal = reveal.cleared()
            }
        }
        window?.addWindowListener(masking)
        window?.addWindowStateListener(masking)
        onDispose {
            window?.removeWindowListener(masking)
            window?.removeWindowStateListener(masking)
            lockKeys?.close(); monitor?.close(); countdown.stop()
        }
    }
    // Quitting stops the worker, so a close request during an operation asks first. When the operation ends before
    // an answer, the question closes and the window stays open to show its result.
    LaunchedEffect(closeRequested) {
        if (closeRequested) { if (busy) confirmClose = true else onCloseAnswered(true) }
    }
    LaunchedEffect(busy) {
        if (!busy && confirmClose) { confirmClose = false; onCloseAnswered(false) }
    }
    DisposableEffect(Unit) {
        onDispose { state.dispose() }
    }
    // A larger interface scale needs a larger window to show the same content; AWT enlarges a smaller window itself.
    LaunchedEffect(window, preferences.uiScale) { window?.minimumSize = minimumWindowSize(preferences.uiScale) }
    KeyrookTheme(dark, preferences.contrast, preferences.uiScale) {
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent { handleShortcut(it, onlyLock = true) }
            .onKeyEvent { handleShortcut(it, onlyLock = false) }.focusRequester(rootFocus).focusable(),
            color = MaterialTheme.colors.background) {
            Column {
                AppBar(dark, unlocked = vault != null, canLock = vault != null || (busy && !locking), shortcutPrefix,
                    warnings = if (vault != null) warnings else null,
                    onWarnings = { warningsOpen = true },
                    onTheme = { updatePreferences { it.copy(theme = if (dark) ThemeMode.LIGHT else ThemeMode.DARK) } },
                    onSettings = { showSettings = true }, onAbout = { about = true }, onLock = ::lockNow)
                Box(Modifier.fillMaxWidth().height(3.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxSize())
                }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    UpdateNotice(updates)
                    if (message.isNotEmpty()) Banner(message, BannerKind.ERROR)
                    if (notice.isNotEmpty()) Banner(notice, BannerKind.INFO, onClose = { notice = "" })
                    if (vault == null) {
                        UnlockScreen(state, settings, unlockDelay)
                    } else if (creating || editing != null) {
                        Editor(vault!!, editing, busy, shortcuts, settings, template = if (editing == null) state.template else null,
                            onCancel = { editing = null; creating = false }) { entry ->
                            // The editor's busy flag lags one composition behind; a second Save in that window is refused here.
                            submitEditedEntry(busy, entry) { candidate ->
                                state.operation { Vault(entries = listOf(candidate)).use { controller.save(candidate) } }
                            }
                        }
                    } else {
                        Workspace(state, vault!!, settings, shortcuts, mac, warningsByEntry)
                    }
                }
            }
        }
        if (showSettings) SettingsDialog(preferences, onDismiss = { showSettings = false }, onChange = ::updatePreferences)
        AppDialogs(state, warnings, breached, updates, mac, onCloseAnswered)
    }
}
