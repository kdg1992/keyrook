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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
        val saved = settings.update(change)
        preferences = settings.current()
        UiText.select(preferences.language)
        if (!saved) message = UiText.text("settings.saveFailed")
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
            .onKeyEvent { handleShortcut(it, onlyLock = false) }.focusRequester(rootFocus).focusable()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Keyrook", style = MaterialTheme.typography.h4, modifier = Modifier.weight(1f).semantics { heading() })
                    if (vault != null) WarningsBadge(warnings) { warningsOpen = true }
                    TextButton(onClick = { updatePreferences { it.copy(theme = if (dark) ThemeMode.LIGHT else ThemeMode.DARK) } },
                        modifier = Modifier.describedAs(UiText.text(if (dark) "a11y.switchToLight" else "a11y.switchToDark"))) {
                        Text(if (dark) UiText.text("shell.light") else UiText.text("shell.dark"))
                    }
                    TextButton(onClick = { about = true }) { Text(UiText.text("shell.about")) }
                    val settingsState = UiText.text(if (showSettings) "a11y.expanded" else "a11y.collapsed")
                    TextButton(onClick = { showSettings = !showSettings }, modifier = Modifier.semantics { stateDescription = settingsState }) {
                        Text(UiText.text("shell.security"))
                    }
                    if (vault != null || (busy && !locking)) Button(onClick = ::lockNow) { Text(UiText.text("shell.lock", shortcutPrefix)) }
                }
                if (showSettings) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Choice(UiText.text("shell.theme"), preferences.theme.name, ThemeMode.entries.map { it.name to UiText.text("shell.theme.${it.name.lowercase()}") }, nullable = false) {
                            it?.let { value -> updatePreferences { current -> current.copy(theme = ThemeMode.valueOf(value)) } }
                        }
                        Choice(UiText.text("shell.contrast"), preferences.contrast.name, ContrastMode.entries.map { it.name to UiText.text("shell.contrast.${it.name.lowercase()}") }, nullable = false) {
                            it?.let { value -> updatePreferences { current -> current.copy(contrast = ContrastMode.valueOf(value)) } }
                        }
                        Choice(UiText.text("shell.uiScale"), preferences.uiScale.toString(), UI_SCALE_CHOICES.map { it.toString() to UiText.text("shell.uiScaleValue", it) }, nullable = false) {
                            it?.toInt()?.let { value -> updatePreferences { current -> current.copy(uiScale = value) } }
                        }
                        Choice(UiText.text("shell.language"), preferences.language.name, AppLanguage.entries.map { it.name to UiText.text("shell.language.${it.name.lowercase()}") }, nullable = false) {
                            it?.let { value -> updatePreferences { current -> current.copy(language = AppLanguage.valueOf(value)) } }
                        }
                        Choice(UiText.text("shell.lockMinutes"), preferences.inactivityMinutes.toString(), LOCK_MINUTE_CHOICES.map { it.toString() to it.toString() }, nullable = false) {
                            it?.toInt()?.let { value -> updatePreferences { current -> current.copy(inactivityMinutes = value) } }
                        }
                        Choice(UiText.text("shell.windowLock"), preferences.windowLock.name, WindowLockPolicy.entries.map { it.name to UiText.text("shell.windowLock.${it.name.lowercase()}") }, nullable = false) {
                            it?.let { value -> updatePreferences { current -> current.copy(windowLock = WindowLockPolicy.valueOf(value)) } }
                        }
                        Choice(UiText.text("shell.clipboardSeconds"), preferences.clipboardSeconds.toString(), CLIPBOARD_SECOND_CHOICES.map { it.toString() to it.toString() }, nullable = false) {
                            it?.toLong()?.let { value ->
                                runCatching { SecretClipboard.configure(value) }
                                updatePreferences { current -> current.copy(clipboardSeconds = value) }
                            }
                        }
                    }
                    Text(UiText.text("shell.settingsHint"))
                    UpdateCheckSetting(preferences.checkUpdatesOnStart) { value -> updatePreferences { it.copy(checkUpdatesOnStart = value) } }
                }
                UpdateNotice(updates)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotEmpty()) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colors.error)
                if (notice.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(notice, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                    TextButton(onClick = { notice = "" }) { Text(UiText.text("shell.close")) }
                }
                if (vault == null) {
                    UnlockScreen(state, settings, unlockDelay)
                } else if (creating || editing != null) {
                    Editor(vault!!, editing, busy, shortcuts, settings, onCancel = { editing = null; creating = false }) { entry ->
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
        AppDialogs(state, warnings, breached, updates, mac, onCloseAnswered)
    }
}
