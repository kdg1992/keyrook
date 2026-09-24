// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.keyrook.core.generator.PasswordGenerator
import app.keyrook.core.generator.PasswordOptions
import app.keyrook.core.generator.PasswordPreset
import java.nio.file.Path
import javax.swing.SwingUtilities

/** A null [settings] keeps generator choices for this editor only. Remembered choices are written only after a successful use. */
@Composable
internal fun GeneratorTools(busy: Boolean, onBusy: (Boolean) -> Unit, settings: SettingsStore? = null, generated: (String) -> Unit) {
    val stored = remember { settings?.current()?.generator ?: GeneratorPreferences() }
    var expanded by remember { mutableStateOf(false) }
    var preset by remember { mutableStateOf(stored.preset) }
    var length by remember { mutableStateOf(stored.length.toString()) }
    var lower by remember { mutableStateOf(stored.lowercase) }
    var upper by remember { mutableStateOf(stored.uppercase) }
    var digits by remember { mutableStateOf(stored.digits) }
    var symbols by remember { mutableStateOf(stored.symbols) }
    var noAmbiguous by remember { mutableStateOf(stored.excludeAmbiguous) }
    var passwordError by remember { mutableStateOf(false) }
    var phraseMessage by remember { mutableStateOf<String?>(null) }
    var phraseError by remember { mutableStateOf(false) }
    var separator by remember { mutableStateOf(stored.separator) }
    var wordCount by remember { mutableStateOf(stored.wordCount.toString()) }
    // A missing or unusable remembered list silently falls back to choosing a file.
    var wordList by remember { mutableStateOf(rememberedWordList(stored.wordListPath)) }
    var loading by remember { mutableStateOf(false) }
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    val insert by rememberUpdatedState(generated)
    DisposableEffect(Unit) { onDispose { alive.set(false) } }
    fun rememberChoice(change: (GeneratorPreferences) -> GeneratorPreferences) {
        settings?.update { it.copy(generator = change(it.generator)) }
    }
    fun passphrase(path: Path, remembered: Boolean) {
        phraseMessage = null
        phraseError = false
        val count = wordCount.toIntOrNull()
        val selectedSeparator = separator
        if (count == null || count !in PASSPHRASE_WORD_COUNTS) {
            phraseMessage = UiText.text("generator.wordCountError")
            phraseError = true
            return
        }
        loading = true
        onBusy(true)
        Thread({
            var minimum = 0
            var vocabularySize = 0
            var listFailed = true
            val result = runCatching {
                val words = parsePassphraseWordList(readTransfer(path, MAX_WORD_LIST_BYTES))
                listFailed = false
                vocabularySize = words.size
                minimum = minimumPassphraseWords(words.size)
                require(count >= minimum)
                PasswordGenerator().passphrase(words, count, selectedSeparator)
            }
            SwingUtilities.invokeLater {
                try {
                    result.getOrNull()?.let { deliverGeneratedSecret(it, alive::get, insert) }
                    if (alive.get()) {
                        phraseError = result.isFailure && !(remembered && listFailed)
                        phraseMessage = when {
                            minimum > count -> UiText.text("generator.minimum", vocabularySize, minimum)
                            remembered && listFailed -> UiText.text("generator.wordListUnavailable")
                            result.isFailure -> UiText.text("generator.wordListError")
                            else -> UiText.text("generator.reviewed", vocabularySize, minimum)
                        }
                        if (listFailed && remembered) {
                            wordList = null
                            rememberChoice { it.copy(wordListPath = null) }
                        } else if (result.isSuccess) {
                            wordList = path
                            rememberChoice { it.copy(wordListPath = path.toAbsolutePath().normalize(), wordCount = count, separator = selectedSeparator) }
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
    TextButton(enabled = !busy, onClick = { expanded = !expanded }) { Text(UiText.text("generator.title")) }
    if (expanded) {
        Row(Modifier.selectableGroup()) {
            PasswordPreset.entries.forEach { choice ->
                LabeledRadioButton(preset == choice, UiText.text("generator.preset.${choice.name}"), enabled = !busy) {
                    preset = choice
                    length = GeneratorPreferences.lengthFor(choice, length.toIntOrNull()).toString()
                }
            }
        }
        Text(UiText.text("generator.presetHelp.${preset.name}"))
        OutlinedTextField(length, { length = it }, enabled = !busy,
            label = { Text(UiText.text("generator.length", PasswordGenerator.MIN_LENGTH, preset.maxLength)) })
        Row {
            LabeledCheckbox(lower, UiText.text("generator.lower"), enabled = !busy) { lower = it }
            LabeledCheckbox(upper, UiText.text("generator.upper"), enabled = !busy) { upper = it }
            LabeledCheckbox(digits, UiText.text("generator.digits"), enabled = !busy) { digits = it }
            LabeledCheckbox(symbols, UiText.text("generator.symbols"), enabled = !busy) { symbols = it }
        }
        LabeledCheckbox(noAmbiguous, UiText.text("generator.noAmbiguous"), enabled = !busy) { noAmbiguous = it }
        Button(enabled = !busy, onClick = {
            passwordError = runCatching {
                val options = PasswordOptions(length.toInt(), lower, upper, digits, symbols, preset, noAmbiguous)
                deliverGeneratedSecret(PasswordGenerator().generate(options), alive::get, insert)
                rememberChoice { it.withPasswordOptions(options) }
            }.isFailure
        }) { Text(UiText.text("generator.generate")) }
        if (passwordError) Text(UiText.text("generator.passwordError"), color = MaterialTheme.colors.error)
        Text(UiText.text("generator.wordListHelp"))
        Text(UiText.text("generator.entropyHelp"))
        OutlinedTextField(wordCount, { wordCount = it }, enabled = !busy && !loading, label = { Text(UiText.text("generator.words")) })
        Row(Modifier.selectableGroup()) {
            LabeledRadioButton(separator == '-', UiText.text("generator.hyphen"), enabled = !busy && !loading) { separator = '-' }
            LabeledRadioButton(separator == ' ', UiText.text("generator.space"), enabled = !busy && !loading) { separator = ' ' }
        }
        phraseMessage?.let { Text(it, color = if (phraseError) MaterialTheme.colors.error else MaterialTheme.colors.onSurface) }
        // The full path is shown so a list redirected by someone who can write the settings file stays visible.
        wordList?.let { list ->
            Text(UiText.text("generator.wordListRemembered", list.toString()))
            Button(enabled = !busy && !loading, onClick = { passphrase(list, remembered = true) }) {
                Text(if (loading) UiText.text("generator.loading") else UiText.text("generator.useRemembered"))
            }
        }
        Row {
            Button(enabled = !busy && !loading, onClick = {
                if (wordCount.toIntOrNull()?.takeIf { it in PASSPHRASE_WORD_COUNTS } == null) {
                    phraseMessage = UiText.text("generator.wordCountError")
                    phraseError = true
                } else {
                    val path = chooseOpenFile(DialogFile.WORD_LIST)
                    if (path != null && alive.get()) passphrase(path, remembered = false)
                }
            }) {
                Text(when {
                    loading && wordList == null -> UiText.text("generator.loading")
                    wordList == null -> UiText.text("generator.choose")
                    else -> UiText.text("generator.chooseOther")
                })
            }
            if (wordList != null) TextButton(enabled = !busy && !loading, onClick = {
                wordList = null
                phraseMessage = null
                rememberChoice { it.copy(wordListPath = null) }
            }) { Text(UiText.text("generator.forgetWordList")) }
        }
    }
}
