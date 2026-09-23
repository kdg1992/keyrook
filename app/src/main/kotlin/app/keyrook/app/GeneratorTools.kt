// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.Row
import androidx.compose.material.*
import androidx.compose.runtime.*
import app.keyrook.core.generator.PasswordGenerator
import app.keyrook.core.generator.PasswordOptions
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import java.nio.file.Files

@Composable
internal fun GeneratorTools(busy: Boolean, onBusy: (Boolean) -> Unit, generated: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var length by remember { mutableStateOf("24") }
    var lower by remember { mutableStateOf(true) }
    var upper by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var wordCount by remember { mutableStateOf("6") }
    var loading by remember { mutableStateOf(false) }
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { alive.set(false) } }
    TextButton(enabled = !busy, onClick = { expanded = !expanded }) { Text("Passwortgenerator") }
    if (expanded) {
        OutlinedTextField(length, { length = it }, enabled = !busy, label = { Text("Länge (12–256)") })
        Row {
            Checkbox(lower, enabled = !busy, onCheckedChange = { lower = it }); Text("a–z")
            Checkbox(upper, enabled = !busy, onCheckedChange = { upper = it }); Text("A–Z")
            Checkbox(digits, enabled = !busy, onCheckedChange = { digits = it }); Text("0–9")
            Checkbox(symbols, enabled = !busy, onCheckedChange = { symbols = it }); Text("Sonderzeichen")
        }
        Button(enabled = !busy, onClick = {
            error = runCatching {
                PasswordGenerator().generate(PasswordOptions(length.toInt(), lower, upper, digits, symbols)).use {
                    generated(it.useChars { chars -> String(chars) })
                }
            }.isFailure
        }) { Text("Erzeugen und einsetzen") }
        if (error) Text("Länge prüfen und mindestens eine Zeichenklasse wählen.", color = MaterialTheme.colors.error)
        Text("Passphrase: eigene geprüfte Wortliste mit mindestens 1024 verschiedenen Wörtern, ein Wort pro Zeile.")
        OutlinedTextField(wordCount, { wordCount = it }, enabled = !busy && !loading, label = { Text("Wörter (5–20, mindestens 60 Bit)") })
        Button(enabled = !busy && !loading, onClick = {
            val chooser = JFileChooser()
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val path = chooser.selectedFile.toPath()
                val count = wordCount.toIntOrNull()
                loading = true
                onBusy(true)
                Thread({
                    val result = runCatching {
                        require(count != null)
                        val bytes = readTransfer(path, 2_200_000)
                        val words = try {
                            bytes.toString(Charsets.UTF_8).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
                        } finally { bytes.fill(0) }
                        PasswordGenerator().passphrase(words, count)
                    }
                    SwingUtilities.invokeLater {
                        result.getOrNull()?.use { if (alive.get()) generated(it.useChars { chars -> String(chars) }) }
                        if (alive.get()) { loading = false; error = result.isFailure; onBusy(false) }
                    }
                }, "passphrase-worker").apply { isDaemon = true; start() }
            }
        }) { Text(if (loading) "Wortliste wird geprüft …" else "Wortliste wählen und Passphrase erzeugen") }
    }
}
