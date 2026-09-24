// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.keyrook.core.model.*
import app.keyrook.core.crypto.Secret
import app.keyrook.core.ssh.SshKeyService
import java.awt.Desktop
import javax.swing.SwingUtilities

@Composable
internal fun Editor(vault: Vault, source: Entry?, externalBusy: Boolean, shortcuts: ShortcutActions, settings: SettingsStore? = null,
                   onCancel: () -> Unit, onSave: (Entry) -> Unit) {
    var type by remember { mutableStateOf(source?.data?.type() ?: EntryType.WEB) }
    val initialData = remember(type) { source?.data ?: blankData(type) }
    var data by remember(initialData) { mutableStateOf(initialData) }
    DisposableEffect(initialData) { onDispose { if (source == null) initialData.fields().forEach { it.value.close() } } }
    var title by remember { mutableStateOf(source?.title.orEmpty()) }
    var tags by remember { mutableStateOf(editorTags(source)) }
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
    val dirty = title != source?.title.orEmpty() || tags != editorTags(source) ||
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
        Text(if (source == null) UiText.text("editor.new") else UiText.text("editor.edit"), Modifier.semantics { heading() },
            style = MaterialTheme.typography.h5)
        if (source == null) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                EntryType.entries.forEach { candidate ->
                    TextButton(enabled = !busy, onClick = { type = candidate }, modifier = Modifier.semantics { selected = type == candidate }) {
                        Text(if (type == candidate) "• ${candidate.label}" else candidate.label)
                    }
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
                        LabeledCheckbox(endpoint != null, name, enabled = !busy) { enabled ->
                            portDrafts = portDrafts - slot
                            update(if (enabled) MailEndpoint(newField(), if (name == "IMAP") 993 else if (name == "POP3") 995 else 465, MailEncryption.TLS) else null)
                        }
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
                Row { SshKeyType.entries.forEach { keyType -> TextButton(enabled = !busy && !generating, onClick = { data = current.copy(keyType = keyType) },
                    modifier = Modifier.semantics { selected = keyType == current.keyType }) { Text(if (keyType == current.keyType) "• $keyType" else "$keyType") } } }
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
                    LabeledCheckbox(server.id in current.serverIds, server.title, enabled = !busy) { selected ->
                        data = current.copy(serverIds = if (selected) current.serverIds + server.id else current.serverIds - server.id)
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
                val hiddenText = UiText.text("common.hidden")
                LabeledCheckbox(hidden[index], hiddenText, enabled = !busy, description = UiText.text("a11y.fieldOption", label, hiddenText),
                    stacked = true) { value -> hidden = hidden.toMutableList().also { it[index] = value } }
                TextButton(enabled = !busy, onClick = {
                    error = runCatching { SecretClipboard.copy(values[index]) }.isFailure
                }, modifier = Modifier.describedAs(UiText.text("a11y.copyValue", label))) { Text(UiText.text("common.copy")) }
                if (data.fields()[index].kind == FieldKind.URL) TextButton(enabled = !busy, onClick = {
                    error = runCatching {
                        val uri = BrowserLinks.parse(values[index])
                        Desktop.getDesktop().browse(uri)
                    }.isFailure
                }, modifier = Modifier.describedAs(UiText.text("a11y.openValue", label))) { Text(UiText.text("common.open")) }
            }
            FieldError(validation.valueMessage(index))
            if (index == data.totpIndex()) TotpFormatHint()
            if (data is EntryData.Custom) {
                val current = data as EntryData.Custom
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val removeText = UiText.text("editor.removeField")
                    TextButton(enabled = !busy, onClick = { replaceData(current.copy(values = current.values - label)) },
                        modifier = Modifier.describedAs(UiText.text("a11y.fieldOption", label, removeText))) { Text(removeText) }
                    val urlText = UiText.text("editor.urlField")
                    LabeledCheckbox(current.values.getValue(label).kind == FieldKind.URL, urlText, enabled = !busy,
                        description = UiText.text("a11y.fieldOption", label, urlText)) { url ->
                        data = current.copy(values = current.values + (label to current.values.getValue(label).copy(kind = if (url) FieldKind.URL else FieldKind.TEXT)))
                    }
                }
            }
            if (data.canGenerateSecret(index)) GeneratorTools(busy, onBusy = { generating = it }, settings = settings) { generated ->
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
