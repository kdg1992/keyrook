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
    TextButton(enabled = !busy, onClick = { expanded = !expanded }) { Text(UiText.text("generator.title")) }
    if (expanded) {
        OutlinedTextField(length, { length = it }, enabled = !busy, label = { Text(UiText.text("generator.length")) })
        Row {
            Checkbox(lower, enabled = !busy, onCheckedChange = { lower = it }); Text(UiText.text("generator.lower"))
            Checkbox(upper, enabled = !busy, onCheckedChange = { upper = it }); Text(UiText.text("generator.upper"))
            Checkbox(digits, enabled = !busy, onCheckedChange = { digits = it }); Text(UiText.text("generator.digits"))
            Checkbox(symbols, enabled = !busy, onCheckedChange = { symbols = it }); Text(UiText.text("generator.symbols"))
        }
        Button(enabled = !busy, onClick = {
            passwordError = runCatching {
                deliverGeneratedSecret(PasswordGenerator().generate(PasswordOptions(length.toInt(), lower, upper, digits, symbols)),
                    alive::get, insert)
            }.isFailure
        }) { Text(UiText.text("generator.generate")) }
        if (passwordError) Text(UiText.text("generator.passwordError"), color = MaterialTheme.colors.error)
        Text(UiText.text("generator.wordListHelp"))
        Text(UiText.text("generator.entropyHelp"))
        OutlinedTextField(wordCount, { wordCount = it }, enabled = !busy && !loading, label = { Text(UiText.text("generator.words")) })
        Row {
            RadioButton(separator == '-', onClick = { separator = '-' }, enabled = !busy && !loading)
            Text(UiText.text("generator.hyphen"))
            RadioButton(separator == ' ', onClick = { separator = ' ' }, enabled = !busy && !loading)
            Text(UiText.text("generator.space"))
        }
        phraseMessage?.let { Text(it, color = if (phraseError) MaterialTheme.colors.error else MaterialTheme.colors.onSurface) }
        Button(enabled = !busy && !loading, onClick = {
            phraseMessage = null
            phraseError = false
            val count = wordCount.toIntOrNull()
            val selectedSeparator = separator
            if (count == null || count !in 5..20) {
                phraseMessage = UiText.text("generator.wordCountError")
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
                                    minimum > count -> UiText.text("generator.minimum", vocabularySize, minimum)
                                    result.isFailure -> UiText.text("generator.wordListError")
                                    else -> UiText.text("generator.reviewed", vocabularySize, minimum)
                                }
                            }
                        } catch (_: Exception) {
                            if (alive.get()) { phraseError = true; phraseMessage = UiText.text("generator.insertFailed") }
                        } finally {
                            if (alive.get()) { loading = false; onBusy(false) }
                        }
                    }
                }, "passphrase-worker").apply { isDaemon = true; start() }
            }
        }) { Text(if (loading) UiText.text("generator.loading") else UiText.text("generator.choose")) }
    }
}
