// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import app.keyrook.core.model.*
import app.keyrook.core.crypto.Secret
import app.keyrook.core.crypto.KdfParameters
import app.keyrook.core.ssh.SshKeyService
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

fun main(args: Array<String>) {
    if (args.isNotEmpty()) kotlin.system.exitProcess(runRuntimeCheck(args))
    application {
        Window(onCloseRequest = ::exitApplication, title = "Keyrook") { KeyrookApp(window) }
    }
}

private fun chooseFile(save: Boolean): Path? {
    val chooser = JFileChooser().apply {
        fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Keyrook (*.keyrook)", "keyrook")
    }
    val result = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
    return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

@Composable
internal fun KeyrookApp(window: java.awt.Window? = null, settings: SettingsStore = remember { SettingsStore.platform() }) {
    val controller = remember { VaultController() }
    val worker = remember { Executors.newSingleThreadExecutor { task -> Thread(task, "vault-worker").apply { isDaemon = true } } }
    var vault by remember { mutableStateOf<Vault?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var preferences by remember { mutableStateOf(settings.current()) }
    val systemDark = isSystemInDarkTheme()
    val dark = when (preferences.theme) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    var about by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var locking by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    remember { runCatching { SecretClipboard.configure(settings.current().clipboardSeconds) } }
    var unlockDelay by remember { mutableStateOf(0L) }
    val inactivity = remember { InactivityDeadline() }
    val live = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val shortcuts = remember { ShortcutActions() }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(vault?.id) { rootFocus.requestFocus() }
    val mac = remember { System.getProperty("os.name").startsWith("Mac", ignoreCase = true) }
    val shortcutPrefix = if (mac) "⌘" else "Strg+"
    fun updatePreferences(change: (AppSettings) -> AppSettings) {
        val saved = settings.update(change)
        preferences = settings.current()
        if (!saved) message = UiText.text("settings.saveFailed")
    }
    fun lockNow() {
        if (locking || (vault == null && !busy)) return
        locking = true
        val token = controller.sessionEpoch.invalidate()
        java.awt.Window.getWindows().filterIsInstance<java.awt.Dialog>().filter { it.isVisible }.forEach { it.dispose() }
        editing = null; creating = false; about = false; showSettings = false
        vault?.close(); vault = null
        runCatching { SecretClipboard.clear() }
        busy = true
        message = UiText.text("shell.locked")
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
        val key = when (event.key) {
            Key.L -> ShortcutKey.L
            Key.N -> ShortcutKey.N
            Key.F -> ShortcutKey.F
            Key.S -> ShortcutKey.S
            Key.Escape -> ShortcutKey.ESCAPE
            else -> ShortcutKey.OTHER
        }
        val action = keyboardShortcut(key, event.type == KeyEventType.KeyDown, event.isCtrlPressed,
            event.isMetaPressed, event.isAltPressed, event.isShiftPressed, mac,
            ShortcutContext(vault != null, busy, creating || editing != null, locking, about)) ?: return false
        if (onlyLock && action != ShortcutAction.LOCK) return false
        when (action) {
            ShortcutAction.LOCK -> lockNow()
            ShortcutAction.NEW_ENTRY -> shortcuts.newEntry?.invoke() ?: return false
            ShortcutAction.SEARCH -> shortcuts.search?.invoke() ?: return false
            ShortcutAction.SAVE -> shortcuts.save?.invoke() ?: return false
            ShortcutAction.CANCEL -> shortcuts.cancel?.invoke() ?: return false
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
            lock = { latestLock() }, deadline = inactivity)
        val countdown = javax.swing.Timer(250) { unlockDelay = controller.unlockDelayMillis() }.apply { start() }
        onDispose { lockKeys?.close(); monitor?.close(); countdown.stop() }
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
                    TextButton(onClick = { updatePreferences { it.copy(theme = if (dark) ThemeMode.LIGHT else ThemeMode.DARK) } }) { Text(if (dark) UiText.text("shell.light") else UiText.text("shell.dark")) }
                    TextButton(onClick = { about = true }) { Text(UiText.text("shell.about")) }
                    TextButton(onClick = { showSettings = !showSettings }) { Text(UiText.text("shell.security")) }
                    if (vault != null || (busy && !locking)) Button(onClick = ::lockNow) { Text(UiText.text("shell.lock", shortcutPrefix)) }
                }
                if (showSettings) {
                    Row {
                        Choice(UiText.text("shell.theme"), preferences.theme.name, ThemeMode.entries.map { it.name to UiText.text("shell.theme.${it.name.lowercase()}") }, nullable = false) {
                            it?.let { value -> updatePreferences { current -> current.copy(theme = ThemeMode.valueOf(value)) } }
                        }
                        Choice(UiText.text("shell.lockMinutes"), preferences.inactivityMinutes.toString(), LOCK_MINUTE_CHOICES.map { it.toString() to it.toString() }, nullable = false) {
                            it?.toInt()?.let { value -> updatePreferences { current -> current.copy(inactivityMinutes = value) } }
                        }
                        Choice(UiText.text("shell.clipboardSeconds"), preferences.clipboardSeconds.toString(), CLIPBOARD_SECOND_CHOICES.map { it.toString() to it.toString() }, nullable = false) {
                            it?.toLong()?.let { value ->
                                runCatching { SecretClipboard.configure(value) }
                                updatePreferences { current -> current.copy(clipboardSeconds = value) }
                            }
                        }
                    }
                    Text(UiText.text("shell.settingsHint"))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotEmpty()) Text(message, color = MaterialTheme.colors.error)
                if (vault == null) {
                    if (unlockDelay > 0) Text(UiText.text("shell.delay", (unlockDelay + 999) / 1000))
                    UnlockForm(busy || unlockDelay > 0, settings.current(), generateKey = { target, done ->
                        operation { generateKeyFile(target); credentialOnEdt(done); null }
                    }) { path, password, key, create, parameters ->
                        operation {
                            controller.unlock(path, password, key, create, parameters).also {
                                rememberUnlockedPath(settings, path)
                                val token = controller.sessionEpoch.capture()
                                if (!restoreRememberedBackups(controller, settings)) SwingUtilities.invokeLater {
                                    if (live.get() && controller.sessionEpoch.accepts(token)) message = UiText.text("settings.backupRestoreFailed")
                                }
                            }
                        }
                    }
                } else if (creating || editing != null) {
                    Editor(vault!!, editing, busy, shortcuts, onCancel = { editing = null; creating = false }) { entry ->
                        operation { Vault(entries = listOf(entry)).use { controller.save(entry) } }
                    }
                } else {
                    HealthTools(vault!!, controller, busy, ::operation)
                    DataTools(controller, settings, busy, ::operation, settingsFailed = {
                        SwingUtilities.invokeLater { if (live.get()) message = UiText.text("settings.saveFailed") }
                    })
                    OrganizationTools(vault!!, controller, busy, ::operation)
                    VaultList(vault!!, controller, busy, shortcuts, onCreate = { creating = true }, onEdit = { editing = it },
                        onDuplicate = { entry -> operation { controller.duplicate(entry.id) } },
                        onTrash = { id -> operation { controller.trash(id, false) } },
                        onRestore = { id -> operation { controller.trash(id, true) } },
                        onPurge = { id -> operation { controller.purge(setOf(id)) } },
                        onEmptyTrash = { operation { controller.emptyTrash() } })
                }
            }
        }
        if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("Keyrook") },
            text = { Text(UiText.text("shell.aboutBody", System.getProperty("keyrook.version", "dev"))) },
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

@Composable
private fun VaultList(vault: Vault, controller: VaultController, busy: Boolean, shortcuts: ShortcutActions,
                      onCreate: () -> Unit, onEdit: (Entry) -> Unit,
                      onDuplicate: (Entry) -> Unit, onTrash: (String) -> Unit, onRestore: (String) -> Unit,
                      onPurge: (String) -> Unit, onEmptyTrash: () -> Unit) {
    var search by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(EntryListFilters()) }
    val activeFilters = filters.normalized(vault)
    val trash = activeFilters.trash
    LaunchedEffect(activeFilters) { filters = activeFilters }
    var includeHidden by remember { mutableStateOf(false) }
    val matches = searchResults(vault, controller, search, includeHidden)
    val searchFocus = remember { FocusRequester() }
    var confirmation by remember { mutableStateOf<ListConfirmation?>(null) }
    var notice by remember { mutableStateOf("") }
    val trashCount = vault.entries.count { it.deletedAt != null }
    val latestNew by rememberUpdatedState({ if (!busy && !trash && confirmation == null) onCreate() })
    DisposableEffect(shortcuts) {
        shortcuts.newEntry = { latestNew() }
        shortcuts.search = { searchFocus.requestFocus() }
        onDispose { shortcuts.newEntry = null; shortcuts.search = null }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(search, { if (it.length <= 256) search = it }, label = { Text(UiText.text("shell.search")) }, modifier = Modifier.weight(1f).focusRequester(searchFocus), singleLine = true)
        Button(onClick = onCreate, enabled = !busy && !trash) { Text(UiText.text("shell.newEntry")) }
        TextButton(onClick = { filters = activeFilters.copy(trash = !trash) }, enabled = !busy) { Text(if (trash) UiText.text("shell.active") else UiText.text("shell.trash")) }
        if (trash) TextButton(enabled = !busy && trashCount > 0, onClick = { confirmation = ListConfirmation.EmptyTrash(trashCount) }) {
            Text(UiText.text("list.emptyTrash"))
        }
    }
    Row {
        Checkbox(includeHidden, onCheckedChange = { includeHidden = it })
        Text(UiText.text("shell.hiddenSearch"))
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(UiText.text("shell.type"), activeFilters.type?.name, EntryType.entries.map { it.name to it.label }) {
            filters = activeFilters.copy(type = it?.let(EntryType::valueOf))
        }
        Choice(UiText.text("shell.customer"), activeFilters.customerId, vault.customers.map { it.id to it.name }) {
            filters = activeFilters.copy(customerId = it).normalized(vault)
        }
        Choice(UiText.text("shell.project"), activeFilters.projectId, activeFilters.projects(vault).map { it.id to it.name }) {
            filters = activeFilters.copy(projectId = it)
        }
        Choice(UiText.text("shell.tag"), activeFilters.tag, vault.entries.flatMap { it.tags }.distinct().sorted().map { it to it }) {
            filters = activeFilters.copy(tag = it)
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(UiText.text("shell.expiry"), activeFilters.expiry.name, ExpiryFilter.entries.map { it.name to it.label }, nullable = false) {
            filters = activeFilters.copy(expiry = ExpiryFilter.valueOf(requireNotNull(it)))
        }
        Choice(UiText.text("shell.sort"), activeFilters.sort.name, EntrySort.entries.map { it.name to it.label }, nullable = false) {
            filters = activeFilters.copy(sort = EntrySort.valueOf(requireNotNull(it)))
        }
        TextButton(onClick = { filters = EntryListFilters(); search = ""; includeHidden = false }) { Text(UiText.text("shell.reset")) }
    }
    val today by produceState(java.time.LocalDate.now()) {
        while (true) { kotlinx.coroutines.delay(60_000); value = java.time.LocalDate.now() }
    }
    val entries = activeFilters.select(vault, matches.orEmpty(), today)
    if (notice.isNotEmpty()) Text(notice)
    if (matches == null) Text(UiText.text("shell.searching"))
    else if (entries.isEmpty()) Text(UiText.text("shell.noEntries"))
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entries, key = { it.id }) { entry ->
            Card(Modifier.fillMaxWidth(), elevation = 2.dp) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.h6)
                        Text(entry.data.type().label)
                        if (entry.tags.isNotEmpty()) Text(entry.tags.joinToString(", "))
                        if (!trash) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            QuickField.entries.forEach { kind ->
                                val field = entry.data.quickField(kind)
                                val present = remember(field) { field != null && EntryQuickActions.available(field) }
                                if (field != null && present) {
                                    val label = entry.data.quickLabel(field)
                                    TextButton(enabled = !busy, onClick = {
                                        notice = if (kind == QuickField.URL) {
                                            if (runCatching { EntryQuickActions.open(field) }.isSuccess) "" else UiText.text("list.openFailed")
                                        } else if (runCatching { EntryQuickActions.copy(field) }.isSuccess) UiText.text("list.copied", label)
                                        else UiText.text("list.copyFailed")
                                    }) { Text(UiText.text(if (kind == QuickField.URL) "list.openField" else "list.copyField", label)) }
                                }
                            }
                        }
                    }
                    if (!trash) TextButton(enabled = !busy, onClick = { onEdit(entry) }) { Text(UiText.text("shell.edit")) }
                    if (!trash) TextButton(enabled = !busy, onClick = { onDuplicate(entry) }) { Text(UiText.text("shell.duplicate")) }
                    if (trash) {
                        TextButton(enabled = !busy, onClick = { onRestore(entry.id) }) { Text(UiText.text("shell.restore")) }
                        TextButton(enabled = !busy, onClick = { confirmation = ListConfirmation.Purge(entry.id, entry.title) }) {
                            Text(UiText.text("list.purge"), color = MaterialTheme.colors.error)
                        }
                    } else TextButton(enabled = !busy, onClick = { confirmation = ListConfirmation.Trash(entry.id, entry.title) }) {
                        Text(UiText.text("list.moveToTrash"))
                    }
                }
            }
        }
    }
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
    val dirty = title != source?.title.orEmpty() || tags != source?.tags?.joinToString(", ").orEmpty() ||
        notes != originalNotes || expires != source?.expiresOn.orEmpty() || customerId != source?.customerId ||
        projectId != source?.projectId || values != originalValues || hidden != originalHidden ||
        data != initialData || type != (source?.data?.type() ?: EntryType.WEB) || customLabel.isNotEmpty()
    fun saveDraft() {
        if (busy || confirmDiscard || confirmRemoveTotp) return
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
        } catch (_: Exception) { candidate?.let { Vault(entries = listOf(it)).close() }; error = true }
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
        OutlinedTextField(title, { title = it }, label = { Text(UiText.text("editor.title")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().focusRequester(titleFocus))
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
                OutlinedTextField(current.port.toString(), { value -> value.toIntOrNull()?.let { data = current.copy(port = it) } }, label = { Text(UiText.text("editor.port")) }, enabled = !busy)
                Row { TransferProtocol.entries.forEach { protocol -> TextButton(enabled = !busy, onClick = { data = current.copy(protocol = protocol) }) { Text(if (protocol == current.protocol) "• $protocol" else "$protocol") } } }
                if (current.protocol == TransferProtocol.SFTP) CommandCopyButton("SFTP", busy) {
                    ConnectionCommands.sftp(values[0], current.port, values[1])
                }
            }
            is EntryData.Server -> {
                OutlinedTextField(current.port.toString(), { value -> value.toIntOrNull()?.let { data = current.copy(port = it) } }, label = { Text(UiText.text("editor.port")) }, enabled = !busy)
                CommandCopyButton("SSH", busy) { ConnectionCommands.ssh(values[0], current.port, values[1]) }
            }
            is EntryData.Email -> {
                listOf("IMAP" to current.imap, "POP3" to current.pop3, "SMTP" to current.smtp).forEach { (name, endpoint) ->
                    fun update(next: MailEndpoint?) = replaceData(when (name) {
                        "IMAP" -> current.copy(imap = next)
                        "POP3" -> current.copy(pop3 = next)
                        else -> current.copy(smtp = next)
                    })
                    Row {
                        Checkbox(endpoint != null, enabled = !busy, onCheckedChange = { enabled ->
                            update(if (enabled) MailEndpoint(newField(), if (name == "IMAP") 993 else if (name == "POP3") 995 else 465, MailEncryption.TLS) else null)
                        })
                        Text(name)
                        if (endpoint != null) {
                            OutlinedTextField(endpoint.port.toString(), { value -> value.toIntOrNull()?.let { update(endpoint.copy(port = it)) } }, label = { Text(UiText.text("editor.port")) }, enabled = !busy, modifier = Modifier.width(110.dp))
                            Choice(UiText.text("editor.encryption"), endpoint.encryption.name, MailEncryption.entries.map { it.name to it.name }, !busy, nullable = false) {
                                it?.let { update(endpoint.copy(encryption = MailEncryption.valueOf(it))) }
                            }
                        }
                    }
                }
            }
            is EntryData.Domain -> Choice(UiText.text("editor.registrarLogin"), current.registrarLoginId, vault.entries.filter { it.id != source?.id && it.deletedAt == null }.map { it.id to it.title }, !busy) { data = current.copy(registrarLoginId = it) }
            is EntryData.Custom -> {
                Row {
                    OutlinedTextField(customLabel, { customLabel = it }, label = { Text(UiText.text("editor.newField")) }, enabled = !busy)
                    Button(enabled = !busy && customLabel.isNotBlank() && customLabel !in current.values && current.values.size < 100, onClick = {
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
                    label = { Text(label) }, enabled = !busy, modifier = Modifier.weight(1f),
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
        OutlinedTextField(tags, { tags = it }, label = { Text(UiText.text("editor.tags")) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Text(UiText.text("editor.clipboard"))
        OutlinedTextField(notes, { notes = it }, label = { Text(UiText.text("editor.notes")) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(expires, { expires = it }, label = { Text(UiText.text("editor.expiry")) }, enabled = !busy)
        if (error) Text(UiText.text("editor.invalid"), color = MaterialTheme.colors.error)
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
