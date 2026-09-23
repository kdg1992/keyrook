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

@Composable
internal fun GeneratorTools(busy: Boolean, onBusy: (Boolean) -> Unit, generated: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var length by remember { mutableStateOf("24") }
    var lower by remember { mutableStateOf(true) }
    var upper by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var passwordError by remember { mutableStateOf(false) }
    var phraseMessage by remember { mutableStateOf<String?>(null) }
    var phraseError by remember { mutableStateOf(false) }
    var separator by remember { mutableStateOf('-') }
    var wordCount by remember { mutableStateOf("6") }
    var loading by remember { mutableStateOf(false) }
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val insert by rememberUpdatedState(generated)
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
            passwordError = runCatching {
                deliverGeneratedSecret(PasswordGenerator().generate(PasswordOptions(length.toInt(), lower, upper, digits, symbols)),
                    alive::get, insert)
            }.isFailure
        }) { Text("Erzeugen und einsetzen") }
        if (passwordError) Text("Länge prüfen und mindestens eine Zeichenklasse wählen.", color = MaterialTheme.colors.error)
        Text("Passphrase: eigene geprüfte Wortliste mit mindestens 1024 verschiedenen Wörtern, ein Wort pro Zeile.")
        Text("Bei 1024 Wörtern mindestens 6 Wörter; ab 4096 Wörtern mindestens 5 Wörter (mindestens 60 Bit).")
        OutlinedTextField(wordCount, { wordCount = it }, enabled = !busy && !loading, label = { Text("Wörter (5–20, mindestens 60 Bit)") })
        Row {
            RadioButton(separator == '-', onClick = { separator = '-' }, enabled = !busy && !loading)
            Text("Bindestrich")
            RadioButton(separator == ' ', onClick = { separator = ' ' }, enabled = !busy && !loading)
            Text("Leerzeichen")
        }
        phraseMessage?.let { Text(it, color = if (phraseError) MaterialTheme.colors.error else MaterialTheme.colors.onSurface) }
        Button(enabled = !busy && !loading, onClick = {
            phraseMessage = null
            phraseError = false
            val count = wordCount.toIntOrNull()
            val selectedSeparator = separator
            if (count == null || count !in 5..20) {
                phraseMessage = "Bitte eine Wortanzahl zwischen 5 und 20 wählen."
                phraseError = true
                return@Button
            }
            val chooser = JFileChooser()
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION && alive.get()) {
                val path = chooser.selectedFile.toPath()
                loading = true
                onBusy(true)
                Thread({
                    var minimum = 0
                    var vocabularySize = 0
                    val result = runCatching {
                        val words = parsePassphraseWordList(readTransfer(path, MAX_WORD_LIST_BYTES))
                        vocabularySize = words.size
                        minimum = minimumPassphraseWords(words.size)
                        require(count >= minimum)
                        PasswordGenerator().passphrase(words, count, selectedSeparator)
                    }
                    SwingUtilities.invokeLater {
                        try {
                            result.getOrNull()?.let { deliverGeneratedSecret(it, alive::get, insert) }
                            if (alive.get()) {
                                phraseError = result.isFailure
                                phraseMessage = when {
                                    minimum > count -> "Diese Wortliste enthält $vocabularySize Wörter. Bitte mindestens $minimum Wörter wählen (60 Bit)."
                                    result.isFailure -> "Wortliste nicht lesbar oder ungültig: UTF-8, 1024–65536 verschiedene Wörter, je 2–32 Buchstaben pro Zeile, höchstens 6,5 MB."
                                    else -> "$vocabularySize Wörter geprüft; Mindestanzahl: $minimum Wörter."
                                }
                            }
                        } catch (_: Exception) {
                            if (alive.get()) { phraseError = true; phraseMessage = "Passphrase konnte nicht eingesetzt werden." }
                        } finally {
                            if (alive.get()) { loading = false; onBusy(false) }
                        }
                    }
                }, "passphrase-worker").apply { isDaemon = true; start() }
            }
        }) { Text(if (loading) "Wortliste wird geprüft …" else "Wortliste wählen und Passphrase erzeugen") }
    }
}
