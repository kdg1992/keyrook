// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.keyrook.core.crypto.Secret
import app.keyrook.core.ssh.SshKeyMaterial
import app.keyrook.core.ssh.SshKeyService
import javax.swing.SwingUtilities

@Composable
internal fun SshImportExport(busy: Boolean, publicKey: String, onBusy: (Boolean) -> Unit,
                             imported: (SshKeyMaterial, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var encoded by remember { mutableStateOf("") }
    var oldPhrase by remember { mutableStateOf("") }
    var newPhrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val insert by rememberUpdatedState(imported)
    DisposableEffect(Unit) { onDispose { alive.set(false) } }
    Row {
        TextButton(enabled = !busy, onClick = { expanded = !expanded }) { Text(UiText.text("import.ssh.open")) }
        TextButton(enabled = !busy && publicKey.isNotBlank(), onClick = {
            val path = chooseNewFile(DialogFile.PUBLIC_KEY, suggestedPublicKeyName(publicKey))
            if (path != null && alive.get()) {
                onBusy(true)
                Thread({
                    val success = runCatching {
                        exportPublicSshKey(path, publicKey, alive::get)
                    }.isSuccess
                    SwingUtilities.invokeLater { if (alive.get()) { onBusy(false); notice = if (success) UiText.text("import.ssh.exported") else UiText.text("import.ssh.exportFailed") } }
                }, "ssh-export-worker").apply { isDaemon = true; start() }
            }
        }) { Text(UiText.text("import.ssh.export")) }
    }
    if (notice.isNotEmpty()) Text(notice)
    if (expanded) {
        Text(UiText.text("import.ssh.help"))
        OutlinedTextField(encoded, { if (it.length <= 65536) encoded = it }, label = { Text(UiText.text("import.ssh.private")) },
            enabled = !busy, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(oldPhrase, { if (it.length <= 1024) oldPhrase = it }, label = { Text(UiText.text("import.ssh.previous")) }, enabled = !busy, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(newPhrase, { if (it.length <= 1024) newPhrase = it }, label = { Text(UiText.text("import.ssh.replacement")) }, enabled = !busy, visualTransformation = PasswordVisualTransformation())
        Button(enabled = !busy && encoded.isNotBlank() && newPhrase.length in 12..1024, onClick = {
            val input = encoded.toCharArray()
            val previous = oldPhrase.toCharArray()
            val replacement = newPhrase.toCharArray()
            encoded = ""; oldPhrase = ""; newPhrase = ""; onBusy(true); error = false
            Thread({
                val result = try {
                    Secret(input).use { key -> Secret(previous).use { old -> Secret(replacement).use { next ->
                        runCatching { SshKeyService().importKey(key, old, next) }
                    } } }
                } finally { input.fill('\u0000'); previous.fill('\u0000') }
                SwingUtilities.invokeLater {
                    try {
                        deliverImportedSshKey(result.getOrNull(), replacement, alive::get, insert)
                        if (alive.get()) { error = result.isFailure; if (result.isSuccess) expanded = false }
                    } catch (_: Exception) {
                        if (alive.get()) error = true
                    } finally { replacement.fill('\u0000'); if (alive.get()) onBusy(false) }
                }
            }, "ssh-import-worker").apply { isDaemon = true; start() }
        }) { Text(UiText.text("import.ssh.validate")) }
        if (error) Text(UiText.text("import.ssh.failed"), color = MaterialTheme.colors.error)
    }
}
