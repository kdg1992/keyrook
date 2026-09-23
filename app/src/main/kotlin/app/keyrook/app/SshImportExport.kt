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
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import javax.swing.JFileChooser
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
    DisposableEffect(Unit) { onDispose { alive.set(false) } }
    Row {
        TextButton(enabled = !busy, onClick = { expanded = !expanded }) { Text("OpenSSH / PEM importieren") }
        TextButton(enabled = !busy && publicKey.isNotBlank(), onClick = {
            val chooser = JFileChooser()
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                val path = chooser.selectedFile.toPath()
                onBusy(true)
                Thread({
                    val success = runCatching {
                        require(!publicKey.contains('\n') && !publicKey.contains('\r'))
                        require(publicKey.startsWith("ssh-ed25519 ") || publicKey.startsWith("ssh-rsa "))
                        Files.writeString(path, publicKey + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    }.isSuccess
                    SwingUtilities.invokeLater { if (alive.get()) { onBusy(false); notice = if (success) "Öffentlicher Schlüssel exportiert." else "Export fehlgeschlagen; Ziel muss eine neue Datei sein." } }
                }, "ssh-export-worker").apply { isDaemon = true; start() }
            }
        }) { Text("Öffentlichen Schlüssel exportieren") }
    }
    if (notice.isNotEmpty()) Text(notice)
    if (expanded) {
        Text("Privaten OpenSSH-/PEM-Schlüssel einfügen. PuTTY-PPK vorher in OpenSSH konvertieren.")
        OutlinedTextField(encoded, { if (it.length <= 65536) encoded = it }, label = { Text("Privater Schlüssel") },
            enabled = !busy, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(oldPhrase, { if (it.length <= 1024) oldPhrase = it }, label = { Text("Bisherige Passphrase (falls verschlüsselt)") }, enabled = !busy, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(newPhrase, { if (it.length <= 1024) newPhrase = it }, label = { Text("Neue Passphrase (mindestens 12 Zeichen)") }, enabled = !busy, visualTransformation = PasswordVisualTransformation())
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
                        result.getOrNull()?.use { key -> if (alive.get()) imported(key, String(replacement)) }
                        if (alive.get()) { error = result.isFailure; onBusy(false); if (result.isSuccess) expanded = false }
                    } finally { replacement.fill('\u0000') }
                }
            }, "ssh-import-worker").apply { isDaemon = true; start() }
        }) { Text("Schlüssel prüfen und importieren") }
        if (error) Text("Import fehlgeschlagen: Format, Passphrase oder Schlüssel prüfen.", color = MaterialTheme.colors.error)
    }
}
