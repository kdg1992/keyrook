// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.keyrook.core.transfer.CsvMapping

/**
 * Shows the current request of [host] as a dialog of the main window, in its theme and the selected language.
 * Each request gets fresh dialog state; closing it by any means answers it, Escape and closing count as cancel.
 */
@Composable
internal fun DialogHostView(host: DialogHost) {
    val request = host.shown ?: return
    key(request) {
        when (request) {
            is MessageRequest -> MessageDialog(request.title, request.text) { host.answer(request, Unit) }
            is ReportRequest -> ReportDialog(request.title, request.text) { host.answer(request, Unit) }
            is ConfirmRequest -> ConfirmDialog(request) { host.answer(request, it) }
            is ChoiceRequest -> ChoiceDialog(request) { host.answer(request, it) }
            is FieldsRequest -> FieldsDialog(request) { host.answer(request, it) }
            is CredentialRequest -> CredentialDialog(request) { host.answer(request, it) }
            is CsvMappingRequest -> CsvMappingDialog(request) { host.answer(request, it) }
        }
    }
}

@Composable
private fun AppDialog(title: String, onCancel: () -> Unit, confirmButton: @Composable () -> Unit,
                      dismissButton: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val latestCancel by rememberUpdatedState(onCancel)
    AlertDialog(
        onDismissRequest = { latestCancel() },
        modifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { latestCancel(); true } else false
        },
        title = { Text(title) },
        text = content,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
    )
}

@Composable
private fun DialogBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.widthIn(max = 560.dp).heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun FocusedButton(label: String, onClick: () -> Unit) {
    val focus = remember { FocusRequester() }
    Button(onClick = onClick, modifier = Modifier.focusRequester(focus)) { Text(label) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
private fun MessageDialog(title: String, text: String, onClose: () -> Unit) {
    AppDialog(title, onCancel = onClose, confirmButton = { FocusedButton(UiText.text("common.ok"), onClose) }) {
        DialogBody { Text(text) }
    }
}

/** Plain text only: report lines are rendered as given and never interpreted as markup. */
@Composable
private fun ReportDialog(title: String, text: String, onClose: () -> Unit) {
    AppDialog(title, onCancel = onClose, confirmButton = { FocusedButton(UiText.text("common.ok"), onClose) }) {
        Box(Modifier.width(640.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            SelectionContainer { Text(text) }
        }
    }
}

/** Questions start focused on No, so Enter alone never confirms. */
@Composable
private fun ConfirmDialog(request: ConfirmRequest, onAnswer: (Boolean?) -> Unit) {
    val noFocus = remember { FocusRequester() }
    AppDialog(request.title, onCancel = { onAnswer(null) },
        confirmButton = { Button(onClick = { onAnswer(true) }) { Text(UiText.text("common.yes")) } },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }, modifier = Modifier.focusRequester(noFocus)) {
                Text(UiText.text("common.no"))
            }
            LaunchedEffect(Unit) { runCatching { noFocus.requestFocus() } }
        }) { DialogBody { Text(request.text) } }
}

@Composable
private fun ChoiceDialog(request: ChoiceRequest, onAnswer: (Int?) -> Unit) {
    var selected by remember { mutableStateOf(0) }
    AppDialog(request.title, onCancel = { onAnswer(null) },
        confirmButton = { FocusedButton(UiText.text("common.ok")) { onAnswer(selected) } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text(UiText.text("common.cancel")) } }) {
        DialogBody(Modifier.selectableGroup()) {
            request.labels.forEachIndexed { index, label ->
                Row(Modifier.fillMaxWidth().selectable(selected = index == selected, onClick = { selected = index },
                    role = Role.RadioButton), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = index == selected, onClick = null)
                    Text(label, Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun FieldsDialog(request: FieldsRequest, onAnswer: (List<String>?) -> Unit) {
    val values = remember { request.fields.map { it.initial }.toMutableStateList() }
    val focus = remember { FocusRequester() }
    val submit = { onAnswer(values.toList()) }
    AppDialog(request.title, onCancel = { onAnswer(null) },
        confirmButton = { Button(onClick = submit) { Text(UiText.text("common.ok")) } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text(UiText.text("common.cancel")) } }) {
        DialogBody {
            request.header.forEach { Text(it) }
            request.fields.forEachIndexed { index, field ->
                OutlinedTextField(values[index], { if (it.length <= 32) values[index] = it }, label = { Text(field.label) },
                    singleLine = true, modifier = Modifier.fillMaxWidth().submitOnEnter(submit)
                        .then(if (index == 0) Modifier.focusRequester(focus) else Modifier))
            }
            request.footer.forEach { Text(it, style = MaterialTheme.typography.caption) }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}

/**
 * Compose text fields hold the passwords as immutable strings that cannot be erased (see SECURITY.md). They exist
 * only while this dialog is shown and are cleared when it answers or leaves the composition, including on lock.
 * The answer carries owned char arrays that the receiver erases; an undelivered answer is erased by the bridge.
 */
@Composable
private fun CredentialDialog(request: CredentialRequest, onAnswer: (CredentialInput?) -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { password = ""; repeat = ""; key = "" } }
    val focus = remember { FocusRequester() }
    val mismatch = request.confirm && repeat.isNotEmpty() && password != repeat
    val valid = password.isNotEmpty() && (!request.confirm || password == repeat)
    fun clear() { password = ""; repeat = ""; key = "" }
    fun cancel() { clear(); onAnswer(null) }
    fun submit() {
        if (!valid) return
        val input = CredentialInput(password.toCharArray(), if (request.confirm) repeat.toCharArray() else CharArray(0), key)
        clear()
        onAnswer(input)
    }
    AppDialog(request.title, onCancel = { cancel() },
        confirmButton = { Button(enabled = valid, onClick = { submit() }) { Text(UiText.text("common.ok")) } },
        dismissButton = { TextButton(onClick = { cancel() }) { Text(UiText.text("common.cancel")) } }) {
        DialogBody {
            OutlinedTextField(password, { if (it.length <= 1024) password = it }, label = { Text(UiText.text("dialog.password")) },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).submitOnEnter { submit() })
            if (request.confirm) OutlinedTextField(repeat, { if (it.length <= 1024) repeat = it },
                label = { Text(UiText.text("dialog.repeatPassword")) }, singleLine = true, isError = mismatch,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().submitOnEnter { submit() })
            // Shown once the repetition is typed, so the disabled OK button is never unexplained.
            if (mismatch) Text(UiText.text("dialog.passwordMismatch"), color = MaterialTheme.colors.error,
                style = MaterialTheme.typography.caption)
            OutlinedTextField(key, { if (it.length <= 4096) key = it }, label = { Text(UiText.text("credentials.optionalKey")) },
                singleLine = true, modifier = Modifier.fillMaxWidth().submitOnEnter { submit() })
            if (request.replacing) Text(UiText.text("credentials.replaceKeyHelp"), style = MaterialTheme.typography.caption)
            TextButton(onClick = { chooseKeyFile()?.let { key = it.toString() } }) { Text(UiText.text("credentials.selectKey")) }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
}

/** Column names are untrusted and shown as plain text only; no data row is previewed. */
@Composable
private fun CsvMappingDialog(request: CsvMappingRequest, onAnswer: (CsvMapping?) -> Unit) {
    val columns = request.columns
    fun position(name: String?): Int? = name?.let { columns.indexOf(it) }?.takeIf { it >= 0 }
    var title by remember { mutableStateOf(position(request.suggested.title) ?: 0) }
    var url by remember { mutableStateOf(position(request.suggested.url)) }
    var username by remember { mutableStateOf(position(request.suggested.username)) }
    var password by remember { mutableStateOf(position(request.suggested.password)) }
    var notes by remember { mutableStateOf(position(request.suggested.notes)) }
    var tags by remember { mutableStateOf(position(request.suggested.tags)) }
    var pinned by remember { mutableStateOf(position(request.suggested.pinned)) }
    AppDialog(UiText.text("csv.title"), onCancel = { onAnswer(null) },
        confirmButton = {
            FocusedButton(UiText.text("common.ok")) {
                onAnswer(CsvMapping(columns[title], url?.let(columns::get), username?.let(columns::get),
                    password?.let(columns::get), notes?.let(columns::get), tags?.let(columns::get), pinned?.let(columns::get)))
            }
        },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text(UiText.text("common.cancel")) } }) {
        DialogBody {
            Text(UiText.text("csv.headers"))
            Text(UiText.text("csv.skipped"))
            ColumnSelector(UiText.text("csv.requiredTitle"), columns, title, optional = false) { title = it ?: title }
            ColumnSelector(UiText.text("field.url"), columns, url, optional = true) { url = it }
            ColumnSelector(UiText.text("field.username"), columns, username, optional = true) { username = it }
            ColumnSelector(UiText.text("field.password"), columns, password, optional = true) { password = it }
            ColumnSelector(UiText.text("editor.notes"), columns, notes, optional = true) { notes = it }
            ColumnSelector(UiText.text("editor.tags"), columns, tags, optional = true) { tags = it }
            ColumnSelector(UiText.text("csv.pinned"), columns, pinned, optional = true) { pinned = it }
            Text(UiText.text("csv.pinnedHint"), style = MaterialTheme.typography.caption)
        }
    }
}

@Composable
private fun ColumnSelector(label: String, columns: List<String>, selected: Int?, optional: Boolean, onSelect: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(180.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selected?.let { csvColumnLabel(columns, it) } ?: UiText.text("csv.unmapped"), maxLines = 1)
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                if (optional) DropdownMenuItem(onClick = { onSelect(null); expanded = false }) { Text(UiText.text("csv.unmapped")) }
                columns.indices.forEach { index ->
                    DropdownMenuItem(onClick = { onSelect(index); expanded = false }) { Text(csvColumnLabel(columns, index), maxLines = 1) }
                }
            }
        }
    }
}
