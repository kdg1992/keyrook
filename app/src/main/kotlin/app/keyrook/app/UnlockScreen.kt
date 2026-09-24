// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.keyrook.core.model.*
import app.keyrook.core.crypto.KdfParameters
import java.nio.file.Path
import javax.swing.SwingUtilities

/** The open and create form, with the countdown of the failed-attempt delay; unlocking runs as an operation. */
@Composable
internal fun UnlockScreen(state: AppState, settings: SettingsStore, unlockDelay: Long) {
    val controller = state.controller
    val dialogs = state.dialogs
    val live = state.live
    val busy by state::busy
    var message by state::message
    var notice by state::notice
    fun operation(action: () -> Vault?) = state.operation(action)
    if (unlockDelay > 0) Text(UiText.text("shell.delay", (unlockDelay + 999) / 1000))
    UnlockForm(busy || unlockDelay > 0, settings.current(), generateKey = { done ->
        operation {
            chooseNewKeyFile(dialogs)?.let { target -> generateKeyFile(target); credentialOnEdt { done(target) } }
            null
        }
    }) { path, password, key, create, parameters ->
        operation {
            val unlocked = controller.unlock(path, password, key, create, parameters)
            try {
                rememberUnlockedPath(settings, path)
                val token = controller.sessionEpoch.capture()
                // Restored settings are unauthenticated: always show them, confirm destructive retention.
                val restored = restoreRememberedBackups(controller, settings) { text -> dialogs.confirm(text) }
                // An older file format is upgraded by the next save, which first keeps a copy of the old file.
                val migration = controller.pendingMigration()?.let { UiText.text("migration.pending", it, Vault.SCHEMA_VERSION) }
                if (restored != null || migration != null) SwingUtilities.invokeLater {
                    if (live.get() && controller.sessionEpoch.accepts(token)) {
                        if (restored?.warning == true) message = restored.text
                        notice = listOfNotNull(restored?.takeUnless { it.warning }?.text, migration).joinToString("\n")
                    }
                }
            } catch (failure: Exception) {
                unlocked.close()
                throw failure
            }
            unlocked
        }
    }
}

private fun chooseFile(save: Boolean): Path? =
    if (save) chooseNewFile(DialogFile.VAULT, "vault.keyrook") else chooseOpenFile(DialogFile.VAULT)

@Composable
private fun UnlockForm(busy: Boolean, remembered: AppSettings, generateKey: ((Path) -> Unit) -> Unit,
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
        Text(UiText.text(if (create) "credentials.createTitle" else "credentials.openTitle"), Modifier.semantics { heading() },
            style = MaterialTheme.typography.h5)
        Row(Modifier.selectableGroup()) {
            LabeledRadioButton(!create, UiText.text("credentials.open"), enabled = !busy) { create = false }
            LabeledRadioButton(create, UiText.text("credentials.create"), enabled = !busy) { create = true }
        }
        OutlinedTextField(path, { path = it }, label = { Text(UiText.text("credentials.vaultFile")) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !busy, onClick = { chooseFile(create)?.let { path = it.toString() } }) { Text(UiText.text("credentials.selectFile")) }
        OutlinedTextField(key, { key = it }, label = { Text(UiText.text("credentials.optionalKey")) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(enabled = !busy, onClick = { chooseKeyFile()?.let { key = it.toString() } }) { Text(UiText.text("credentials.selectKey")) }
            TextButton(enabled = !busy, onClick = {
                generateKey { target -> key = target.toString() }
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
