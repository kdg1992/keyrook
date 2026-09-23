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
import app.keyrook.core.ssh.SshKeyService
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.Executors
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Keyrook") { KeyrookApp(window) }
}

private fun chooseFile(save: Boolean): Path? {
    val chooser = JFileChooser().apply {
        fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Keyrook (*.keyrook)", "keyrook")
    }
    val result = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
    return if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}

@Composable
fun KeyrookApp(window: java.awt.Window? = null) {
    val controller = remember { VaultController() }
    val worker = remember { Executors.newSingleThreadExecutor { task -> Thread(task, "vault-worker").apply { isDaemon = true } } }
    var vault by remember { mutableStateOf<Vault?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var dark by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var locking by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var inactivityMinutes by remember { mutableStateOf(5) }
    var clipboardSeconds by remember { mutableStateOf(20L) }
    var unlockDelay by remember { mutableStateOf(0L) }
    val inactivity = remember { InactivityDeadline() }
    val live = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val shortcuts = remember { ShortcutActions() }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(vault?.id) { rootFocus.requestFocus() }
    val mac = remember { System.getProperty("os.name").startsWith("Mac", ignoreCase = true) }
    val shortcutPrefix = if (mac) "⌘" else "Strg+"
    fun lockNow() {
        if (locking || (vault == null && !busy)) return
        locking = true
        val token = controller.sessionEpoch.invalidate()
        java.awt.Window.getWindows().filterIsInstance<java.awt.Dialog>().filter { it.isVisible }.forEach { it.dispose() }
        editing = null; creating = false; about = false; settings = false
        vault?.close(); vault = null
        runCatching { SecretClipboard.clear() }
        busy = true
        message = "Tresor gesperrt. Nicht gespeicherte Eingaben wurden verworfen."
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
                        message = "Vorgang fehlgeschlagen. Passwort, Schlüsseldatei, Eingaben und Dateizugriff prüfen."
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
            active = { vault != null || (busy && !locking) }, timeoutMinutes = { inactivityMinutes },
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
                    TextButton(onClick = { dark = !dark }) { Text(if (dark) "Hell" else "Dunkel") }
                    TextButton(onClick = { about = true }) { Text("Info") }
                    TextButton(onClick = { settings = !settings }) { Text("Sicherheit") }
                    if (vault != null || (busy && !locking)) Button(onClick = ::lockNow) { Text("Sperren (${shortcutPrefix}L)") }
                }
                if (settings) {
                    Row {
                        Choice("Sperre nach Minuten", inactivityMinutes.toString(), listOf(1, 2, 5, 10, 15, 30).map { it.toString() to it.toString() }, nullable = false) {
                            it?.toInt()?.let { value -> inactivityMinutes = value }
                        }
                        Choice("Zwischenablage Sekunden", clipboardSeconds.toString(), listOf(5L, 10L, 20L, 30L, 60L, 120L).map { it.toString() to it.toString() }, nullable = false) {
                            it?.toLong()?.let { value -> clipboardSeconds = value; runCatching { SecretClipboard.configure(value) } }
                        }
                    }
                    Text("Gilt für diese Sitzung. Beim Wechsel zu einer anderen Anwendung wird gesperrt. Betriebssystem-Ereignisse werden unterstützt, sofern verfügbar.")
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (message.isNotEmpty()) Text(message, color = MaterialTheme.colors.error)
                if (vault == null) {
                    if (unlockDelay > 0) Text("Nächster Entsperrversuch in ${(unlockDelay + 999) / 1000} Sekunden.")
                    UnlockForm(busy || unlockDelay > 0) { path, password, key, create ->
                        operation { controller.unlock(path, password, key, create) }
                    }
                } else if (creating || editing != null) {
                    Editor(vault!!, editing, busy, shortcuts, onCancel = { editing = null; creating = false }) { entry ->
                        operation { Vault(entries = listOf(entry)).use { controller.save(entry) } }
                    }
                } else {
                    HealthTools(vault!!, controller, busy, ::operation)
                    DataTools(controller, busy, ::operation)
                    OrganizationTools(vault!!, controller, busy, ::operation)
                    VaultList(vault!!, controller, busy, shortcuts, onCreate = { creating = true }, onEdit = { editing = it },
                        onDuplicate = { entry -> operation { controller.duplicate(entry.id) } },
                        onTrash = { entry -> operation { controller.trash(entry.id, entry.deletedAt != null) } })
                }
            }
        }
        if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("Keyrook") },
            text = { Text("Version ${System.getProperty("keyrook.version", "Entwicklung")}\nGNU GPL v3 oder neuer\nLokaler verschlüsselter Tresor") },
            confirmButton = { TextButton(onClick = { about = false }) { Text("Schließen") } },
            dismissButton = { TextButton(onClick = {
                runCatching { Desktop.getDesktop().browse(URI("https://github.com/kdg1992/keyrook")) }
            }) { Text("Quellcode öffnen") } })
    }
}

@Composable
private fun UnlockForm(busy: Boolean, onOpen: (Path, CharArray, Path?, Boolean) -> Unit) {
    var path by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var create by remember { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 640.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (create) "Neuen Tresor anlegen" else "Tresor öffnen", style = MaterialTheme.typography.h5)
        Row {
            RadioButton(!create, onClick = { create = false }, enabled = !busy)
            Text("Öffnen", Modifier.padding(top = 12.dp))
            RadioButton(create, onClick = { create = true }, enabled = !busy)
            Text("Anlegen", Modifier.padding(top = 12.dp))
        }
        OutlinedTextField(path, { path = it }, label = { Text("Tresordatei (.keyrook)") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !busy, onClick = { chooseFile(create)?.let { path = it.toString() } }) { Text("Datei auswählen") }
        OutlinedTextField(key, { key = it }, label = { Text("Schlüsseldatei (optional, genau 32 Byte)") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Master-Passwort") }, singleLine = true,
            enabled = !busy, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (create) OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Master-Passwort wiederholen") },
            singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text("Passwort und Schlüsseldatei sicher aufbewahren. Ohne sie gibt es keine Wiederherstellung.")
        Button(enabled = !busy && path.isNotBlank() && password.isNotEmpty() && (!create || password == confirmation), onClick = {
            val target = runCatching { Path.of(path) }.getOrNull()
            val keyPath = if (key.isBlank()) null else runCatching { Path.of(key) }.getOrNull()
            if (target != null && (key.isBlank() || keyPath != null)) {
                val chars = password.toCharArray(); password = ""; confirmation = ""
                onOpen(target, chars, keyPath, create)
            }
        }) { Text(if (create) "Anlegen" else "Entsperren") }
    }
}

@Composable
private fun VaultList(vault: Vault, controller: VaultController, busy: Boolean, shortcuts: ShortcutActions,
                      onCreate: () -> Unit, onEdit: (Entry) -> Unit,
                      onDuplicate: (Entry) -> Unit, onTrash: (Entry) -> Unit) {
    var search by remember { mutableStateOf("") }
    var trash by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf<String?>(null) }
    var customer by remember { mutableStateOf<String?>(null) }
    var tag by remember { mutableStateOf<String?>(null) }
    var expiring by remember { mutableStateOf(false) }
    var includeHidden by remember { mutableStateOf(false) }
    val matches = searchResults(vault, controller, search, includeHidden)
    val searchFocus = remember { FocusRequester() }
    val latestNew by rememberUpdatedState({ if (!busy && !trash) onCreate() })
    DisposableEffect(shortcuts) {
        shortcuts.newEntry = { latestNew() }
        shortcuts.search = { searchFocus.requestFocus() }
        onDispose { shortcuts.newEntry = null; shortcuts.search = null }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(search, { if (it.length <= 256) search = it }, label = { Text("Volltextsuche (bis 256 Zeichen)") }, modifier = Modifier.weight(1f).focusRequester(searchFocus), singleLine = true)
        Button(onClick = onCreate, enabled = !busy && !trash) { Text("Neuer Eintrag") }
        TextButton(onClick = { trash = !trash }, enabled = !busy) { Text(if (trash) "Alle Einträge" else "Papierkorb") }
    }
    Row {
        Checkbox(includeHidden, onCheckedChange = { includeHidden = it })
        Text("Verborgene Felder durchsuchen (Treffer bleiben maskiert)")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("Typ", type, EntryType.entries.map { it.name to it.label }) { type = it }
        Choice("Kunde", customer, vault.customers.map { it.id to it.name }) { customer = it }
        Choice("Tag", tag, vault.entries.flatMap { it.tags }.distinct().sorted().map { it to it }) { tag = it }
        Checkbox(expiring, onCheckedChange = { expiring = it }); Text("Ablauf binnen 30 Tagen")
    }
    val entries = vault.entries.filter { (it.deletedAt != null) == trash &&
        (type == null || it.data.type().name == type) && (customer == null || it.customerId == customer) &&
        (tag == null || tag in it.tags) && (!expiring || it.expiresOn?.let { date ->
            java.time.LocalDate.parse(date) <= java.time.LocalDate.now().plusDays(30)
        } == true) &&
        it.id in matches.orEmpty() }
    if (matches == null) Text("Suche läuft …")
    else if (entries.isEmpty()) Text("Keine passenden Einträge.")
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entries, key = { it.id }) { entry ->
            Card(Modifier.fillMaxWidth(), elevation = 2.dp) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.h6)
                        Text(entry.data.type().label)
                        if (entry.tags.isNotEmpty()) Text(entry.tags.joinToString(", "))
                    }
                    if (!trash) TextButton(enabled = !busy, onClick = { onEdit(entry) }) { Text("Bearbeiten") }
                    if (!trash) TextButton(enabled = !busy, onClick = { onDuplicate(entry) }) { Text("Duplizieren") }
                    TextButton(enabled = !busy, onClick = { onTrash(entry) }) { Text(if (trash) "Wiederherstellen" else "In Papierkorb") }
                }
            }
        }
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
        if (busy || confirmDiscard) return
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
        Text(if (source == null) "Neuer Eintrag" else "Eintrag bearbeiten", style = MaterialTheme.typography.h5)
        if (source == null) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                EntryType.entries.forEach { candidate ->
                    TextButton(enabled = !busy, onClick = { type = candidate }) { Text(if (type == candidate) "• ${candidate.label}" else candidate.label) }
                }
            }
        }
        OutlinedTextField(title, { title = it }, label = { Text("Titel") }, enabled = !busy, modifier = Modifier.fillMaxWidth().focusRequester(titleFocus))
        Row {
            Choice("Kunde", customerId, vault.customers.map { it.id to it.name }, !busy) {
                customerId = it
                if (vault.projects.find { project -> project.id == projectId }?.customerId?.let { id -> id != it } == true) projectId = null
            }
            Choice("Projekt", projectId, vault.projects.filter { customerId == null || it.customerId == null || it.customerId == customerId }.map { it.id to it.name }, !busy) { projectId = it }
        }
        when (val current = data) {
            is EntryData.Transfer -> {
                OutlinedTextField(current.port.toString(), { value -> value.toIntOrNull()?.let { data = current.copy(port = it) } }, label = { Text("Port") }, enabled = !busy)
                Row { TransferProtocol.entries.forEach { protocol -> TextButton(enabled = !busy, onClick = { data = current.copy(protocol = protocol) }) { Text(if (protocol == current.protocol) "• $protocol" else "$protocol") } } }
                if (current.protocol == TransferProtocol.SFTP) CommandCopyButton("SFTP", busy) {
                    ConnectionCommands.sftp(values[0], current.port, values[1])
                }
            }
            is EntryData.Server -> {
                OutlinedTextField(current.port.toString(), { value -> value.toIntOrNull()?.let { data = current.copy(port = it) } }, label = { Text("Port") }, enabled = !busy)
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
                            OutlinedTextField(endpoint.port.toString(), { value -> value.toIntOrNull()?.let { update(endpoint.copy(port = it)) } }, label = { Text("Port") }, enabled = !busy, modifier = Modifier.width(110.dp))
                            Choice("Verschlüsselung", endpoint.encryption.name, MailEncryption.entries.map { it.name to it.name }, !busy, nullable = false) {
                                it?.let { update(endpoint.copy(encryption = MailEncryption.valueOf(it))) }
                            }
                        }
                    }
                }
            }
            is EntryData.Domain -> Choice("Registrar-Login", current.registrarLoginId, vault.entries.filter { it.id != source?.id && it.deletedAt == null }.map { it.id to it.title }, !busy) { data = current.copy(registrarLoginId = it) }
            is EntryData.Custom -> {
                Row {
                    OutlinedTextField(customLabel, { customLabel = it }, label = { Text("Neues Feld") }, enabled = !busy)
                    Button(enabled = !busy && customLabel.isNotBlank() && customLabel !in current.values && current.values.size < 100, onClick = {
                        replaceData(current.copy(values = current.values + (customLabel to newField())))
                        customLabel = ""
                    }) { Text("Feld hinzufügen") }
                }
            }
            is EntryData.Ssh -> {
                Row { SshKeyType.entries.forEach { keyType -> TextButton(enabled = !busy && !generating, onClick = { data = current.copy(keyType = keyType) }) { Text(if (keyType == current.keyType) "• $keyType" else "$keyType") } } }
                Text("Erzeugen ersetzt die Schlüsselfelder. Passphrase zuerst eingeben (mindestens 12 Zeichen).")
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
                }) { Text(if (generating) "Schlüssel wird erzeugt …" else "SSH-Schlüssel erzeugen") }
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
                    Text("Verborgen")
                }
                TextButton(enabled = !busy, onClick = {
                    error = runCatching { SecretClipboard.copy(values[index]) }.isFailure
                }) { Text("Kopieren") }
                if (data.fields()[index].kind == FieldKind.URL) TextButton(enabled = !busy, onClick = {
                    error = runCatching {
                        val uri = BrowserLinks.parse(values[index])
                        Desktop.getDesktop().browse(uri)
                    }.isFailure
                }) { Text("Öffnen") }
            }
            if (data is EntryData.Custom) {
                val current = data as EntryData.Custom
                Row {
                    TextButton(enabled = !busy, onClick = { replaceData(current.copy(values = current.values - label)) }) { Text("Feld entfernen") }
                    Checkbox(current.values.getValue(label).kind == FieldKind.URL, enabled = !busy, onCheckedChange = { url ->
                        data = current.copy(values = current.values + (label to current.values.getValue(label).copy(kind = if (url) FieldKind.URL else FieldKind.TEXT)))
                    }); Text("URL-Feld")
                }
            }
            if (label == "Passwort" || label == "Passphrase") GeneratorTools(busy, onBusy = { generating = it }) { generated ->
                values = values.toMutableList().also { it[index] = generated }
            }
        }
        OutlinedTextField(tags, { tags = it }, label = { Text("Tags (Komma getrennt)") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Text("Kopierte Werte werden nach der eingestellten Frist entfernt (Standard: 20 Sekunden), sofern die Zwischenablage noch uns gehört. Betriebssystem-Verläufe können bestehen bleiben.")
        OutlinedTextField(notes, { notes = it }, label = { Text("Notizen") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(expires, { expires = it }, label = { Text("Ablaufdatum (JJJJ-MM-TT, optional)") }, enabled = !busy)
        if (error) Text("Eingaben prüfen: Titel erforderlich; Ablaufdatum im Format JJJJ-MM-TT.", color = MaterialTheme.colors.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = ::saveDraft) { Text("Speichern") }
            TextButton(enabled = !busy, onClick = ::requestCancel) { Text("Abbrechen") }
            if (source?.history?.isNotEmpty() == true) TextButton(onClick = { history = !history }) { Text("Verlauf (${source.history.size})") }
        }
        if (history) source?.history?.asReversed()?.forEach { item ->
            Text(item.changedAt)
            item.data.labels().zip(item.data.fields()).forEach { (label, field) ->
                Text("$label: " + if (field.hidden) "••••••••" else field.value.useChars { String(it) })
            }
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
        title = { Text("Änderungen verwerfen?") },
        text = { Text("Dieser Eintrag enthält ungespeicherte Änderungen. Möchtest du sie verwerfen?") },
        confirmButton = { TextButton(enabled = !busy, onClick = { confirmDiscard = false; onCancel() }) { Text("Verwerfen") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Weiter bearbeiten") } })
}
