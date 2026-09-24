// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.keyrook.core.model.*
import app.keyrook.core.crypto.Secret
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.security.HealthIssue
import app.keyrook.core.ssh.SshKeyService
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    if (args.isNotEmpty()) kotlin.system.exitProcess(runRuntimeCheck(args))
    val settings = SettingsStore.platform()
    // Select the language before the first composition so no text is rendered in the wrong language.
    UiText.select(settings.current().language)
    val icon = loadWindowIcon()
    application {
        val windowState = rememberMainWindowState(settings)
        Window(
            onCloseRequest = { saveWindowGeometry(settings, windowState); exitApplication() },
            state = windowState, title = "Keyrook", icon = icon,
        ) {
            LaunchedEffect(window) { window.minimumSize = minimumWindowSize() }
            PersistWindowGeometry(windowState, settings)
            KeyrookApp(window, settings)
        }
    }
}

private fun chooseFile(save: Boolean): Path? =
    if (save) chooseNewFile(DialogFile.VAULT, "vault.keyrook") else chooseOpenFile(DialogFile.VAULT)

@Composable
internal fun KeyrookApp(window: java.awt.Window? = null, settings: SettingsStore = remember { SettingsStore.platform() }) {
    val controller = remember { VaultController() }
    val worker = remember { Executors.newSingleThreadExecutor { task -> Thread(task, "vault-worker").apply { isDaemon = true } } }
    var vault by remember { mutableStateOf<Vault?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    // Visible information that is not an error, such as a restored backup configuration; cleared on dismiss or lock.
    var notice by remember { mutableStateOf("") }
    var preferences by remember { mutableStateOf(settings.current().also { UiText.select(it.language) }) }
    val systemDark = isSystemInDarkTheme()
    val dark = when (preferences.theme) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    var about by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var locking by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // List view, selection and shown detail values live here so they survive the editor; lock resets them.
    var listView by remember { mutableStateOf(ListView()) }
    var selection by remember { mutableStateOf(EntrySelection()) }
    var reveal by remember { mutableStateOf(RevealState()) }
    var organizer by remember { mutableStateOf(false) }
    var warningsOpen by remember { mutableStateOf(false) }
    val warnings = vaultWarnings(vault, controller)
    val warningsByEntry = remember(warnings) { warningIssues(warnings.orEmpty()) }
    val updates = rememberUpdateChecks(settings)
    remember { runCatching { SecretClipboard.configure(settings.current().clipboardSeconds) } }
    var unlockDelay by remember { mutableStateOf(0L) }
    val inactivity = remember { InactivityDeadline() }
    val live = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
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
    fun lockNow() {
        if (locking || (vault == null && !busy)) return
        locking = true
        val token = controller.sessionEpoch.invalidate()
        java.awt.Window.getWindows().filterIsInstance<java.awt.Dialog>().filter { it.isVisible }.forEach { it.dispose() }
        editing = null; creating = false; about = false; showSettings = false
        reveal = RevealState(); listView = ListView(); selection = EntrySelection(); organizer = false; warningsOpen = false
        vault?.close(); vault = null
        runCatching { SecretClipboard.clear() }
        busy = true
        message = UiText.text("shell.locked")
        notice = ""
        worker.execute {
            controller.lock()
            SwingUtilities.invokeLater {
                if (live.get() && controller.sessionEpoch.accepts(token)) { locking = false; busy = false }
            }
        }
    }
    fun operation(action: () -> Vault?) {
        if (busy) return
        busy = true
        message = ""
        val token = controller.sessionEpoch.capture()
        worker.execute {
            val result = runCatching { withOperationGuard(controller, token) { action() } }
            SwingUtilities.invokeLater {
                if (!live.get()) { result.getOrNull()?.close() }
                else controller.sessionEpoch.deliver(token, result.getOrNull()) { snapshot ->
                    if (result.isSuccess) {
                        vault?.close()
                        vault = snapshot
                        editing = null
                        creating = false
                        inactivity.activity()
                    } else {
                        // Exceptions can contain paths or decrypted input. Never display their messages.
                        message = UiText.text("shell.failed")
                    }
                    busy = false
                }
            }
        }
    }
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
    DisposableEffect(Unit) {
        onDispose {
            live.set(false)
            controller.sessionEpoch.invalidate()
            runCatching { SecretClipboard.clear() }
            vault?.close()
            worker.execute { controller.close() }
            worker.shutdown()
        }
    }
    MaterialTheme(colors = if (dark) darkColors() else lightColors()) {
        Surface(Modifier.fillMaxSize().onPreviewKeyEvent { handleShortcut(it, onlyLock = true) }
            .onKeyEvent { handleShortcut(it, onlyLock = false) }.focusRequester(rootFocus).focusable()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Keyrook", style = MaterialTheme.typography.h4, modifier = Modifier.weight(1f))
                    if (vault != null) WarningsBadge(warnings) { warningsOpen = true }
                    TextButton(onClick = { updatePreferences { it.copy(theme = if (dark) ThemeMode.LIGHT else ThemeMode.DARK) } }) { Text(if (dark) UiText.text("shell.light") else UiText.text("shell.dark")) }
                    TextButton(onClick = { about = true }) { Text(UiText.text("shell.about")) }
                    TextButton(onClick = { showSettings = !showSettings }) { Text(UiText.text("shell.security")) }
                    if (vault != null || (busy && !locking)) Button(onClick = ::lockNow) { Text(UiText.text("shell.lock", shortcutPrefix)) }
                }
                if (showSettings) {
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Choice(UiText.text("shell.theme"), preferences.theme.name, ThemeMode.entries.map { it.name to UiText.text("shell.theme.${it.name.lowercase()}") }, nullable = false) {
                            it?.let { value -> updatePreferences { current -> current.copy(theme = ThemeMode.valueOf(value)) } }
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
                if (message.isNotEmpty()) Text(message, color = MaterialTheme.colors.error)
                if (notice.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(notice, Modifier.weight(1f))
                    TextButton(onClick = { notice = "" }) { Text(UiText.text("shell.close")) }
                }
                if (vault == null) {
                    if (unlockDelay > 0) Text(UiText.text("shell.delay", (unlockDelay + 999) / 1000))
                    UnlockForm(busy || unlockDelay > 0, settings.current(), generateKey = { target, done ->
                        operation { generateKeyFile(target); credentialOnEdt(done); null }
                    }) { path, password, key, create, parameters ->
                        operation {
                            val unlocked = controller.unlock(path, password, key, create, parameters)
                            try {
                                rememberUnlockedPath(settings, path)
                                val token = controller.sessionEpoch.capture()
                                // Restored settings are unauthenticated: always show them, confirm destructive retention.
                                val restored = restoreRememberedBackups(controller, settings) { text -> confirm(text) }
                                if (restored != null) SwingUtilities.invokeLater {
                                    if (live.get() && controller.sessionEpoch.accepts(token)) {
                                        if (restored.warning) message = restored.text else notice = restored.text
                                    }
                                }
                            } catch (failure: Exception) {
                                unlocked.close()
                                throw failure
                            }
                            unlocked
                        }
                    }
                } else if (creating || editing != null) {
                    Editor(vault!!, editing, busy, shortcuts, onCancel = { editing = null; creating = false }) { entry ->
                        operation { Vault(entries = listOf(entry)).use { controller.save(entry) } }
                    }
                } else {
                    val current = vault!!
                    AppToolbar(dataActions(controller, settings, ::operation, settingsFailed = {
                        SwingUtilities.invokeLater { if (live.get()) message = UiText.text("settings.saveFailed") }
                    }), busy, organizer, onOrganizer = { organizer = !organizer })
                    if (organizer) OrganizationTools(current, controller, busy, ::operation, onClose = { organizer = false })
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val entryList: @Composable (Boolean) -> Unit = { compact ->
                            VaultList(current, controller, busy, shortcuts, mac, listView, { listView = it }, selection,
                                { selection = it }, warningsByEntry, compact, onCreate = { creating = true },
                                onEdit = { editing = it },
                                onDuplicate = { entry -> operation { controller.duplicate(entry.id) } },
                                onTrash = { id -> operation { controller.trash(id, false) } },
                                onRestore = { id -> operation { controller.trash(id, true) } },
                                onPurge = { id -> operation { controller.purge(setOf(id)) } },
                                onEmptyTrash = { operation { controller.emptyTrash() } })
                        }
                        if (workspaceLayout(maxWidth.value) == WorkspaceLayout.LIST_DETAIL) {
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.weight(0.45f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    entryList(true)
                                }
                                Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colors.onSurface.copy(alpha = 0.12f)))
                                val selected = current.entries.firstOrNull { it.id == selection.selectedId }
                                EntryDetailPane(current, selected, selected?.let { warningsByEntry[it.id] }.orEmpty(), reveal, busy,
                                    onReveal = { reveal = it }, onEdit = { editing = it },
                                    modifier = Modifier.weight(0.55f).fillMaxHeight())
                            }
                        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { entryList(false) }
                    }
                }
            }
        }
        val jump: ((String) -> Unit)? = if (busy || creating || editing != null) null else ({ id ->
            warningsOpen = false
            listView = listView.showing(id, selection.visible)
            selection = selection.jump(id)
        })
        val shownVault = vault
        if (warningsOpen && shownVault != null) HealthDialog(shownVault, warnings, jump) { warningsOpen = false }
        if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("Keyrook") },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(UiText.text("shell.aboutBody", System.getProperty("keyrook.version", "dev")))
                    UpdateCheckPanel(updates)
                    ShortcutHelpTable(mac)
                }
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text(UiText.text("shell.close")) } },
            dismissButton = { TextButton(onClick = {
                runCatching { Desktop.getDesktop().browse(URI("https://github.com/kdg1992/keyrook")) }
            }) { Text(UiText.text("shell.source")) } })
    }
}

@Composable
private fun UnlockForm(busy: Boolean, remembered: AppSettings, generateKey: (Path, () -> Unit) -> Unit,
                       onOpen: (Path, CharArray, Path?, Boolean, KdfParameters) -> Unit) {
    var path by remember { mutableStateOf(remembered.lastVaultPath?.toString().orEmpty()) }
    var key by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var create by remember { mutableStateOf(false) }
    var memory by remember { mutableStateOf("65536") }
    var rounds by remember { mutableStateOf("3") }
    var lanes by remember { mutableStateOf("4") }
    val parameters = if (create) parseKdfParameters(memory, rounds, lanes) else KdfParameters()
    Column(Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(UiText.text(if (create) "credentials.createTitle" else "credentials.openTitle"), style = MaterialTheme.typography.h5)
        Row {
            RadioButton(!create, onClick = { create = false }, enabled = !busy)
            Text(UiText.text("credentials.open"), Modifier.padding(top = 12.dp))
            RadioButton(create, onClick = { create = true }, enabled = !busy)
            Text(UiText.text("credentials.create"), Modifier.padding(top = 12.dp))
        }
        OutlinedTextField(path, { path = it }, label = { Text(UiText.text("credentials.vaultFile")) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !busy, onClick = { chooseFile(create)?.let { path = it.toString() } }) { Text(UiText.text("credentials.selectFile")) }
        OutlinedTextField(key, { key = it }, label = { Text(UiText.text("credentials.optionalKey")) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(enabled = !busy, onClick = { chooseKeyFile(false)?.let { key = it.toString() } }) { Text(UiText.text("credentials.selectKey")) }
            TextButton(enabled = !busy, onClick = {
                chooseKeyFile(true)?.let { target -> generateKey(target) { key = target.toString() } }
            }) { Text(UiText.text("credentials.generateKey")) }
            TextButton(enabled = !busy && key.isNotEmpty(), onClick = { key = "" }) { Text(UiText.text("credentials.noKey")) }
        }
        OutlinedTextField(password, { if (it.length <= 1024) password = it }, label = { Text(UiText.text("credentials.password")) }, singleLine = true,
            enabled = !busy, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (create) OutlinedTextField(confirmation, { if (it.length <= 1024) confirmation = it }, label = { Text(UiText.text("credentials.repeatPassword")) },
            singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (create) {
            Text(UiText.text("credentials.kdfTitle"))
            OutlinedTextField(memory, { if (it.length <= 10) memory = it }, label = { Text(UiText.text("credentials.memory")) }, singleLine = true, enabled = !busy)
            OutlinedTextField(rounds, { if (it.length <= 10) rounds = it }, label = { Text(UiText.text("credentials.iterations")) }, singleLine = true, enabled = !busy)
            OutlinedTextField(lanes, { if (it.length <= 10) lanes = it }, label = { Text(UiText.text("credentials.parallelism")) }, singleLine = true, enabled = !busy)
            Text(UiText.text("credentials.kdfExplanation"))
            if (parameters == null) Text(UiText.text("credentials.kdfInvalid"), color = MaterialTheme.colors.error)
        }
        Text(UiText.text("credentials.recoveryWarning"))
        Button(enabled = !busy && parameters != null && path.isNotBlank() && password.length in 1..1024 && (!create || password == confirmation), onClick = {
            val target = runCatching { Path.of(path) }.getOrNull()
            val keyPath = if (key.isBlank()) null else runCatching { Path.of(key) }.getOrNull()
            if (target != null && (key.isBlank() || keyPath != null)) {
                val chars = password.toCharArray(); password = ""; confirmation = ""
                onOpen(target, chars, keyPath, create, requireNotNull(parameters))
            }
        }) { Text(UiText.text(if (create) "credentials.create" else "credentials.unlock")) }
    }
}

/**
 * Search, filters and entry cards. [view] and [selectionState] are owned by the caller so they survive the editor.
 * [compact] arranges the controls for the narrower list pane next to the entry details.
 */
@Composable
private fun VaultList(vault: Vault, controller: VaultController, busy: Boolean, shortcuts: ShortcutActions, mac: Boolean,
                      view: ListView, onView: (ListView) -> Unit, selectionState: EntrySelection,
                      onSelection: (EntrySelection) -> Unit, issues: Map<String, Set<HealthIssue>>, compact: Boolean,
                      onCreate: () -> Unit, onEdit: (Entry) -> Unit,
                      onDuplicate: (Entry) -> Unit, onTrash: (String) -> Unit, onRestore: (String) -> Unit,
                      onPurge: (String) -> Unit, onEmptyTrash: () -> Unit) {
    val search = view.search
    val includeHidden = view.includeHidden
    val activeFilters = view.filters.normalized(vault)
    val trash = activeFilters.trash
    SideEffect { if (view.filters != activeFilters) onView(view.copy(filters = activeFilters)) }
    fun applyFilters(next: EntryListFilters) = onView(view.copy(filters = next))
    val matches = searchResults(vault, controller, search, includeHidden)
    val searchFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    var listFocused by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<ListConfirmation?>(null) }
    var notice by remember { mutableStateOf("") }
    val trashCount = vault.entries.count { it.deletedAt != null }
    val latestNew by rememberUpdatedState({ if (!busy && !trash && confirmation == null) onCreate() })
    DisposableEffect(shortcuts) {
        shortcuts.newEntry = { latestNew() }
        shortcuts.search = { searchFocus.requestFocus() }
        onDispose { shortcuts.newEntry = null; shortcuts.search = null }
    }
    // The list takes the focus when shown and again after its dialogs close, so arrow keys work immediately.
    val listIdle = confirmation == null && !help
    LaunchedEffect(listIdle) { if (listIdle) runCatching { listFocus.requestFocus() } }
    val today by produceState(java.time.LocalDate.now()) {
        while (true) { kotlinx.coroutines.delay(60_000); value = java.time.LocalDate.now() }
    }
    val entries = activeFilters.select(vault, matches.orEmpty(), today)
    val selection = selectionState.update(view.query(activeFilters), matches?.let { entries.map { it.id } })
    SideEffect { if (selectionState != selection) onSelection(selection) }
    val selectedEntry = entries.firstOrNull { it.id == selection.selectedId }
    fun quickAction(entry: Entry, kind: QuickField) {
        if (kind == QuickField.TOTP) { notice = EntryQuickActions.copyTotpNotice(entry.data); return }
        val field = entry.data.quickField(kind)?.takeIf { EntryQuickActions.available(it) }
        notice = when {
            field == null -> UiText.text("list.noQuickField")
            kind == QuickField.URL ->
                if (runCatching { EntryQuickActions.open(field) }.isSuccess) "" else UiText.text("list.openFailed")
            runCatching { EntryQuickActions.copy(field) }.isSuccess -> UiText.text("list.copied", entry.data.quickLabel(field))
            else -> UiText.text("list.copyFailed")
        }
    }
    fun shortcutContext(focus: ShortcutFocus) = ShortcutContext(unlocked = true, busy = busy, editing = false,
        modal = confirmation != null || help, focus = focus, selection = selectedEntry != null, trash = trash)
    fun handleListKey(event: KeyEvent): Boolean {
        val action = keyboardShortcut(event, mac, shortcutContext(ShortcutFocus.LIST)) ?: return false
        when (action) {
            ShortcutAction.SELECT_PREVIOUS -> onSelection(selection.previous())
            ShortcutAction.SELECT_NEXT -> onSelection(selection.next())
            ShortcutAction.SELECT_FIRST -> onSelection(selection.first())
            ShortcutAction.SELECT_LAST -> onSelection(selection.last())
            else -> {
                val entry = selectedEntry ?: return false
                when (action) {
                    ShortcutAction.COPY_PASSWORD -> quickAction(entry, QuickField.SECRET)
                    ShortcutAction.COPY_USERNAME -> quickAction(entry, QuickField.USERNAME)
                    ShortcutAction.COPY_TOTP -> quickAction(entry, QuickField.TOTP)
                    ShortcutAction.OPEN_URL -> quickAction(entry, QuickField.URL)
                    ShortcutAction.EDIT_ENTRY -> onEdit(entry)
                    ShortcutAction.TRASH_ENTRY -> confirmation = ListConfirmation.Trash(entry.id, entry.title)
                    else -> return false
                }
            }
        }
        return true
    }
    val searchField: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(search, { if (it.length <= 256) onView(view.copy(search = it)) }, label = { Text(UiText.text("shell.search")) },
            modifier = modifier.focusRequester(searchFocus).onPreviewKeyEvent { event ->
                if (keyboardShortcut(event, mac, shortcutContext(ShortcutFocus.SEARCH)) != ShortcutAction.FOCUS_LIST) false
                else { runCatching { listFocus.requestFocus() }.isSuccess }
            }, singleLine = true)
    }
    val listButtons: @Composable () -> Unit = {
        Button(onClick = onCreate, enabled = !busy && !trash) { Text(UiText.text("shell.newEntry")) }
        TextButton(onClick = { applyFilters(activeFilters.copy(trash = !trash)) }, enabled = !busy) { Text(if (trash) UiText.text("shell.active") else UiText.text("shell.trash")) }
        if (trash) TextButton(enabled = !busy && trashCount > 0, onClick = { confirmation = ListConfirmation.EmptyTrash(trashCount) }) {
            Text(UiText.text("list.emptyTrash"))
        }
        TextButton(onClick = { help = true }) { Text(UiText.text("shortcuts.title")) }
    }
    if (compact) {
        searchField(Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) { listButtons() }
    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        searchField(Modifier.weight(1f))
        listButtons()
    }
    Row {
        Checkbox(includeHidden, onCheckedChange = { onView(view.copy(includeHidden = it)) })
        Text(UiText.text("shell.hiddenSearch"))
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(UiText.text("shell.type"), activeFilters.type?.name, EntryType.entries.map { it.name to it.label }) {
            applyFilters(activeFilters.copy(type = it?.let(EntryType::valueOf)))
        }
        Choice(UiText.text("shell.customer"), activeFilters.customerId, vault.customers.map { it.id to it.name }) {
            applyFilters(activeFilters.copy(customerId = it).normalized(vault))
        }
        Choice(UiText.text("shell.project"), activeFilters.projectId, activeFilters.projects(vault).map { it.id to it.name }) {
            applyFilters(activeFilters.copy(projectId = it))
        }
        Choice(UiText.text("shell.tag"), activeFilters.tag, vault.entries.flatMap { it.tags }.distinct().sorted().map { it to it }) {
            applyFilters(activeFilters.copy(tag = it))
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(UiText.text("shell.expiry"), activeFilters.expiry.name, ExpiryFilter.entries.map { it.name to it.label }, nullable = false) {
            applyFilters(activeFilters.copy(expiry = ExpiryFilter.valueOf(requireNotNull(it))))
        }
        Choice(UiText.text("shell.sort"), activeFilters.sort.name, EntrySort.entries.map { it.name to it.label }, nullable = false) {
            applyFilters(activeFilters.copy(sort = EntrySort.valueOf(requireNotNull(it))))
        }
        TextButton(onClick = { onView(ListView()) }) { Text(UiText.text("shell.reset")) }
    }
    if (notice.isNotEmpty()) Text(notice)
    if (matches == null) Text(UiText.text("shell.searching"))
    else if (entries.isEmpty()) Text(UiText.text("shell.noEntries"))
    val listState = rememberLazyListState()
    val selectedIndex = entries.indexOfFirst { it.id == selection.selectedId }
    LaunchedEffect(selectedIndex, selection.selectedId) {
        if (selectedIndex < 0) return@LaunchedEffect
        val layout = listState.layoutInfo
        val shown = layout.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        if (shown == null || shown.offset < layout.viewportStartOffset || shown.offset + shown.size > layout.viewportEndOffset) {
            listState.scrollToItem(selectedIndex)
        }
    }
    LazyColumn(Modifier.focusRequester(listFocus).onFocusChanged { listFocused = it.hasFocus }
        .onKeyEvent { handleListKey(it) }.focusable(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entries, key = { it.id }) { entry ->
            val info = remember(entry.id, entry.modifiedAt, vault.id, vault.revision, today, UiText.locale) {
                entryCardInfo(entry, vault, today)
            }
            EntryCardView(entry, info, isSelected = entry.id == selection.selectedId, listFocused = listFocused,
                trash = trash, busy = busy,
                onClick = { onSelection(selection.select(entry.id)); runCatching { listFocus.requestFocus() } },
                onFocusInside = { onSelection(selection.select(entry.id)) },
                onQuick = { kind -> quickAction(entry, kind) },
                onEdit = { onEdit(entry) }, onDuplicate = { onDuplicate(entry) }, onRestore = { onRestore(entry.id) },
                onPurge = { confirmation = ListConfirmation.Purge(entry.id, entry.title) },
                onTrash = { confirmation = ListConfirmation.Trash(entry.id, entry.title) },
                markers = passwordMarkers(issues[entry.id].orEmpty()), compact = compact)
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(UiText.text("shortcuts.title")) },
        text = { ShortcutHelpTable(mac) },
        confirmButton = { TextButton(onClick = { help = false }) { Text(UiText.text("shell.close")) } })
    fun dismiss() { confirmation = null }
    when (val pending = confirmation) {
        is ListConfirmation.Trash -> ConfirmationDialog(UiText.text("list.trashTitle"), UiText.text("list.trashBody", pending.title),
            UiText.text("list.trashConfirm"), busy, irreversible = false,
            onConfirm = { dismiss(); onTrash(pending.id) }, onDismiss = ::dismiss)
        is ListConfirmation.Purge -> ConfirmationDialog(UiText.text("list.purgeTitle"), UiText.text("list.purgeBody", pending.title),
            UiText.text("list.purgeConfirm"), busy, irreversible = true,
            onConfirm = { dismiss(); onPurge(pending.id) }, onDismiss = ::dismiss)
        is ListConfirmation.EmptyTrash -> ConfirmationDialog(UiText.text("list.emptyTrashTitle"),
            UiText.text("list.emptyTrashBody", pending.count), UiText.text("list.emptyTrashConfirm"), busy, irreversible = true,
            onConfirm = { dismiss(); onEmptyTrash() }, onDismiss = ::dismiss)
        null -> Unit
    }
}

@Composable
private fun Editor(vault: Vault, source: Entry?, externalBusy: Boolean, shortcuts: ShortcutActions,
                   onCancel: () -> Unit, onSave: (Entry) -> Unit) {
    var type by remember { mutableStateOf(source?.data?.type() ?: EntryType.WEB) }
    val initialData = remember(type) { source?.data ?: blankData(type) }
    var data by remember(initialData) { mutableStateOf(initialData) }
    DisposableEffect(initialData) { onDispose { if (source == null) initialData.fields().forEach { it.value.close() } } }
    var title by remember { mutableStateOf(source?.title.orEmpty()) }
    var tags by remember { mutableStateOf(source?.tags?.joinToString(", ").orEmpty()) }
    val originalNotes = remember { source?.notes?.useChars { String(it) }.orEmpty() }
    var notes by remember { mutableStateOf(originalNotes) }
    var expires by remember { mutableStateOf(source?.expiresOn.orEmpty()) }
    val originalValues = remember(initialData) { data.fields().map { it.value.useChars { chars -> String(chars) } } }
    val originalHidden = remember(initialData) { data.fields().map { it.hidden } }
    var values by remember(initialData) { mutableStateOf(originalValues) }
    var hidden by remember(initialData) { mutableStateOf(originalHidden) }
    var error by remember { mutableStateOf(false) }
    var rejected by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var titleEdited by remember { mutableStateOf(false) }
    var portDrafts by remember(initialData) { mutableStateOf(emptyMap<PortSlot, String>()) }
    fun editPort(slot: PortSlot, text: String, apply: (Int) -> Unit) {
        portDrafts = portDrafts + (slot to text)
        parsePort(text)?.let(apply)
    }
    var customerId by remember { mutableStateOf(source?.customerId) }
    var projectId by remember { mutableStateOf(source?.projectId) }
    var history by remember { mutableStateOf(false) }
    var confirmRemoveTotp by remember { mutableStateOf(false) }
    var customLabel by remember { mutableStateOf("") }
    val ownedFields = remember { mutableListOf<Secret>() }
    fun newField() = Field(Secret(charArrayOf()).also { ownedFields.add(it) }, hidden = true)
    fun replaceData(next: EntryData) {
        val previous = data.labels().mapIndexed { index, label -> label to (values[index] to hidden[index]) }.toMap()
        values = next.labels().map { previous[it]?.first.orEmpty() }
        hidden = next.labels().map { previous[it]?.second ?: true }
        data = next
    }
    var generating by remember { mutableStateOf(false) }
    val busy = externalBusy || generating
    var confirmDiscard by remember { mutableStateOf(false) }
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { titleFocus.requestFocus() }
    val shownPorts = editorPorts(data, portDrafts)
    val validation = validateEditor(title, tags, notes, expires, values, shownPorts, data.totpSlot(initialData, originalValues))
    val dirty = title != source?.title.orEmpty() || tags != source?.tags?.joinToString(", ").orEmpty() ||
        notes != originalNotes || expires != source?.expiresOn.orEmpty() || customerId != source?.customerId ||
        projectId != source?.projectId || values != originalValues || hidden != originalHidden ||
        data != initialData || type != (source?.data?.type() ?: EntryType.WEB) || customLabel.isNotEmpty() ||
        shownPorts.values.any { parsePort(it) == null }
    fun saveDraft() {
        if (busy || confirmDiscard || confirmRemoveTotp) return
        attempted = true
        rejected = false
        if (!validateEditor(title, tags, notes, expires, values, editorPorts(data, portDrafts), data.totpSlot(initialData, originalValues)).valid) return
        var candidate: Entry? = null
        try {
            candidate = editedEntry(source, data, title, tags, notes, expires, values, hidden).copy(customerId = customerId, projectId = projectId)
            val context = Vault(entries = listOf(candidate.copy(customerId = null, projectId = null,
                data = when (val d = candidate.data) {
                    is EntryData.Ssh -> d.copy(serverIds = emptyList())
                    is EntryData.Domain -> d.copy(registrarLoginId = null)
                    else -> d
                })))
            context.validate()
            onSave(candidate)
        } catch (_: Exception) { candidate?.let { Vault(entries = listOf(it)).close() }; rejected = true }
    }
    fun requestCancel() {
        if (busy) return
        if (dirty) confirmDiscard = true else onCancel()
    }
    val latestSave by rememberUpdatedState(::saveDraft)
    val latestCancel by rememberUpdatedState(::requestCancel)
    DisposableEffect(shortcuts) {
        shortcuts.save = { latestSave() }
        shortcuts.cancel = { latestCancel() }
        onDispose { shortcuts.save = null; shortcuts.cancel = null }
    }
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { alive.set(false); ownedFields.forEach { it.close() } } }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (source == null) UiText.text("editor.new") else UiText.text("editor.edit"), style = MaterialTheme.typography.h5)
        if (source == null) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                EntryType.entries.forEach { candidate ->
                    TextButton(enabled = !busy, onClick = { type = candidate }) { Text(if (type == candidate) "• ${candidate.label}" else candidate.label) }
                }
            }
        }
        val titleError = validation.title?.takeIf { attempted || titleEdited || it.problem != InputProblem.TITLE_REQUIRED }
        OutlinedTextField(title, { title = it; titleEdited = true }, label = { Text(UiText.text("editor.title")) }, enabled = !busy,
            isError = titleError != null, modifier = Modifier.fillMaxWidth().focusRequester(titleFocus))
        FieldError(titleError)
        Row {
            Choice(UiText.text("common.customer"), customerId, vault.customers.map { it.id to it.name }, !busy) {
                customerId = it
                if (vault.projects.find { project -> project.id == projectId }?.customerId?.let { id -> id != it } == true) projectId = null
            }
            Choice(UiText.text("common.project"), projectId, vault.projects.filter { customerId == null || it.customerId == null || it.customerId == customerId }.map { it.id to it.name }, !busy) {
                projectId = it
                vault.projects.find { project -> project.id == it }?.customerId?.let { owner -> customerId = owner }
            }
        }
        when (val current = data) {
            is EntryData.Web -> TextButton(enabled = !busy, onClick = {
                if (current.totp == null) replaceData(current.copy(totp = newField()))
                else confirmRemoveTotp = true
            }) { Text(UiText.text(if (current.totp == null) "editor.addTotp" else "editor.removeTotp")) }
            is EntryData.Transfer -> {
                PortField(shownPorts[PortSlot.TRANSFER].orEmpty(), !busy, { value -> editPort(PortSlot.TRANSFER, value) { data = current.copy(port = it) } })
                Row { TransferProtocol.entries.forEach { protocol -> TextButton(enabled = !busy, onClick = { data = current.copy(protocol = protocol) }) { Text(if (protocol == current.protocol) "• $protocol" else "$protocol") } } }
                if (current.protocol == TransferProtocol.SFTP) CommandCopyButton("SFTP", busy || PortSlot.TRANSFER in validation.ports) {
                    ConnectionCommands.sftp(values[0], current.port, values[1])
                }
            }
            is EntryData.Server -> {
                PortField(shownPorts[PortSlot.SERVER].orEmpty(), !busy, { value -> editPort(PortSlot.SERVER, value) { data = current.copy(port = it) } })
                CommandCopyButton("SSH", busy || PortSlot.SERVER in validation.ports) { ConnectionCommands.ssh(values[0], current.port, values[1]) }
            }
            is EntryData.Email -> {
                listOf("IMAP" to current.imap, "POP3" to current.pop3, "SMTP" to current.smtp).forEach { (name, endpoint) ->
                    fun update(next: MailEndpoint?) = replaceData(when (name) {
                        "IMAP" -> current.copy(imap = next)
                        "POP3" -> current.copy(pop3 = next)
                        else -> current.copy(smtp = next)
                    })
                    val slot = PortSlot.valueOf(name)
                    Row {
                        Checkbox(endpoint != null, enabled = !busy, onCheckedChange = { enabled ->
                            portDrafts = portDrafts - slot
                            update(if (enabled) MailEndpoint(newField(), if (name == "IMAP") 993 else if (name == "POP3") 995 else 465, MailEncryption.TLS) else null)
                        })
                        Text(name)
                        if (endpoint != null) {
                            PortField(shownPorts[slot].orEmpty(), !busy, { value -> editPort(slot, value) { update(endpoint.copy(port = it)) } },
                                Modifier.width(160.dp))
                            Choice(UiText.text("editor.encryption"), endpoint.encryption.name, MailEncryption.entries.map { it.name to if (it == MailEncryption.NONE) UiText.text("editor.encryptionNone") else it.name }, !busy, nullable = false) {
                                it?.let { update(endpoint.copy(encryption = MailEncryption.valueOf(it))) }
                            }
                        }
                    }
                }
            }
            is EntryData.Domain -> Choice(UiText.text("editor.registrarLogin"), current.registrarLoginId, vault.entries.filter { it.id != source?.id && it.deletedAt == null }.map { it.id to it.title }, !busy) { data = current.copy(registrarLoginId = it) }
            is EntryData.Custom -> {
                val labelError = customFieldNameError(customLabel, current.values.keys)
                Row {
                    Column {
                        OutlinedTextField(customLabel, { customLabel = it }, label = { Text(UiText.text("editor.newField")) }, enabled = !busy,
                            isError = labelError != null)
                        FieldError(labelError)
                    }
                    Button(enabled = !busy && customLabel.isNotBlank() && labelError == null && current.values.size < 100, onClick = {
                        replaceData(current.copy(values = current.values + (customLabel to newField())))
                        customLabel = ""
                    }) { Text(UiText.text("editor.addField")) }
                }
            }
            is EntryData.Ssh -> {
                Row { SshKeyType.entries.forEach { keyType -> TextButton(enabled = !busy && !generating, onClick = { data = current.copy(keyType = keyType) }) { Text(if (keyType == current.keyType) "• $keyType" else "$keyType") } } }
                Text(UiText.text("editor.sshHint"))
                Button(enabled = !busy && !generating && values[2].length >= 12, onClick = {
                    generating = true
                    val chars = values[2].toCharArray()
                    Thread({
                        val result = runCatching {
                            Secret(chars).use { phrase -> SshKeyService().generate(
                                app.keyrook.core.ssh.SshKeyType.valueOf(current.keyType.name), phrase) }
                        }
                        chars.fill('\u0000')
                        SwingUtilities.invokeLater {
                            result.getOrNull()?.use { material ->
                                if (alive.get()) values = values.toMutableList().also {
                                    it[0] = material.privateKey.useChars { key -> String(key) }
                                    it[1] = material.publicKey
                                    it[3] = material.fingerprint
                                }
                                if (alive.get()) hidden = listOf(true, false, true, false)
                            }
                            if (alive.get()) { generating = false; error = result.isFailure }
                        }
                    }, "ssh-key-worker").apply { isDaemon = true; start() }
                }) { Text(if (generating) UiText.text("editor.sshGenerating") else UiText.text("editor.sshGenerate")) }
                SshImportExport(busy, values[1], onBusy = { generating = it }) { material, phrase ->
                    data = current.copy(keyType = SshKeyType.valueOf(material.type.name))
                    values = listOf(material.privateKey.useChars { String(it) }, material.publicKey, phrase, material.fingerprint)
                    hidden = listOf(true, false, true, false)
                }
                vault.entries.filter { it.data is EntryData.Server && it.deletedAt == null }.forEach { server ->
                    Row {
                        Checkbox(server.id in current.serverIds, enabled = !busy, onCheckedChange = { selected ->
                            data = current.copy(serverIds = if (selected) current.serverIds + server.id else current.serverIds - server.id)
                        }); Text(server.title)
                    }
                }
            }
            else -> Unit
        }
        data.labels().forEachIndexed { index, label ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(values[index], { value -> values = values.toMutableList().also { it[index] = value } },
                    label = { Text(label) }, enabled = !busy, isError = index in validation.values, modifier = Modifier.weight(1f),
                    visualTransformation = if (hidden[index]) PasswordVisualTransformation() else VisualTransformation.None)
                Column {
                    Checkbox(hidden[index], enabled = !busy, onCheckedChange = { value -> hidden = hidden.toMutableList().also { it[index] = value } })
                    Text(UiText.text("common.hidden"))
                }
                TextButton(enabled = !busy, onClick = {
                    error = runCatching { SecretClipboard.copy(values[index]) }.isFailure
                }) { Text(UiText.text("common.copy")) }
                if (data.fields()[index].kind == FieldKind.URL) TextButton(enabled = !busy, onClick = {
                    error = runCatching {
                        val uri = BrowserLinks.parse(values[index])
                        Desktop.getDesktop().browse(uri)
                    }.isFailure
                }) { Text(UiText.text("common.open")) }
            }
            FieldError(validation.valueMessage(index))
            if (index == data.totpIndex()) TotpFormatHint()
            if (data is EntryData.Custom) {
                val current = data as EntryData.Custom
                Row {
                    TextButton(enabled = !busy, onClick = { replaceData(current.copy(values = current.values - label)) }) { Text(UiText.text("editor.removeField")) }
                    Checkbox(current.values.getValue(label).kind == FieldKind.URL, enabled = !busy, onCheckedChange = { url ->
                        data = current.copy(values = current.values + (label to current.values.getValue(label).copy(kind = if (url) FieldKind.URL else FieldKind.TEXT)))
                    }); Text(UiText.text("editor.urlField"))
                }
            }
            if (data.canGenerateSecret(index)) GeneratorTools(busy, onBusy = { generating = it }) { generated ->
                values = values.toMutableList().also { it[index] = generated }
            }
        }
        OutlinedTextField(tags, { tags = it }, label = { Text(UiText.text("editor.tags")) }, enabled = !busy,
            isError = validation.tags != null, modifier = Modifier.fillMaxWidth())
        FieldError(validation.tags)
        Text(UiText.text("editor.clipboard"))
        OutlinedTextField(notes, { notes = it }, label = { Text(UiText.text("editor.notes")) }, enabled = !busy,
            isError = validation.notes != null, modifier = Modifier.fillMaxWidth())
        FieldError(validation.notes)
        ExpiryField(expires, !busy) { expires = it }
        if (attempted && !validation.valid) Text(UiText.text("editor.invalid"), color = MaterialTheme.colors.error)
        else if (rejected) Text(UiText.text("editor.rejected"), color = MaterialTheme.colors.error)
        if (error) Text(UiText.text("editor.actionFailed"), color = MaterialTheme.colors.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = ::saveDraft) { Text(UiText.text("common.save")) }
            TextButton(enabled = !busy, onClick = ::requestCancel) { Text(UiText.text("common.cancel")) }
            if (source?.history?.isNotEmpty() == true) TextButton(onClick = { history = !history }) { Text(UiText.text("editor.history", source.history.size)) }
        }
        if (history) source?.history?.asReversed()?.forEach { item ->
            Text(UiText.text("editor.historyVersion", item.changedAt))
            item.data.labels().zip(item.data.fields()).forEach { (label, field) ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$label: " + if (field.hidden) "••••••••" else field.value.useChars { String(it) })
                    TextButton(enabled = !busy, onClick = {
                        error = runCatching { field.value.useChars { SecretClipboard.copy(String(it)) } }.isFailure
                    }) { Text(UiText.text("editor.historyCopy", label)) }
                }
            }
        }
    }
    if (confirmRemoveTotp) AlertDialog(onDismissRequest = { confirmRemoveTotp = false },
        title = { Text(UiText.text("editor.removeTotpTitle")) },
        text = { Text(UiText.text("editor.removeTotpBody")) },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            val current = data as? EntryData.Web
            if (current != null) replaceData(current.copy(totp = null))
            confirmRemoveTotp = false
        }) { Text(UiText.text("editor.removeTotp")) } },
        dismissButton = { TextButton(onClick = { confirmRemoveTotp = false }) { Text(UiText.text("common.cancel")) } })
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text(UiText.text("editor.discardTitle")) },
        text = { Text(UiText.text("editor.discardBody")) },
        confirmButton = { TextButton(enabled = !busy, onClick = { confirmDiscard = false; onCancel() }) { Text(UiText.text("editor.discard")) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(UiText.text("editor.keepEditing")) } })
}
