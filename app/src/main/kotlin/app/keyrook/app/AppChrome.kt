// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.keyrook.core.security.EntryHealth

/**
 * The window bar in the brand colours: name, vault warnings, theme, settings, about and lock. [warnings] is null while
 * locked, which hides the warning button.
 */
@Composable
internal fun AppBar(dark: Boolean, unlocked: Boolean, canLock: Boolean, shortcutPrefix: String, warnings: List<EntryHealth>?,
                    onWarnings: () -> Unit, onTheme: () -> Unit, onSettings: () -> Unit, onAbout: () -> Unit, onLock: () -> Unit) {
    val chrome = LocalChrome.current
    val icon = remember { loadWindowIcon() }
    Surface(color = chrome.bar, contentColor = chrome.onBar, elevation = 0.dp) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(30.dp).clip(MaterialTheme.shapes.small))
            Spacer(Modifier.width(8.dp))
            Text("Keyrook", Modifier.semantics { heading() }, style = MaterialTheme.typography.h6.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            if (unlocked) WarningsBadge(warnings, contentColor = chrome.onBar, onClick = onWarnings)
            val barButton = ButtonDefaults.textButtonColors(contentColor = chrome.onBar)
            TextButton(onClick = onTheme, colors = barButton,
                modifier = Modifier.describedAs(UiText.text(if (dark) "a11y.switchToLight" else "a11y.switchToDark"))) {
                ButtonIcon(KeyrookIcons.Theme)
                Text(if (dark) UiText.text("shell.light") else UiText.text("shell.dark"))
            }
            TextButton(onClick = onSettings, colors = barButton) { ButtonIcon(KeyrookIcons.Settings); Text(UiText.text("shell.security")) }
            TextButton(onClick = onAbout, colors = barButton) { ButtonIcon(KeyrookIcons.Info); Text(UiText.text("shell.about")) }
            if (canLock) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onLock, border = BorderStroke(1.dp, chrome.onBar.copy(alpha = 0.7f)),
                    colors = ButtonDefaults.outlinedButtonColors(backgroundColor = chrome.bar, contentColor = chrome.onBar)) {
                    ButtonIcon(KeyrookIcons.Lock)
                    Text(UiText.text("shell.lock", shortcutPrefix))
                }
            }
        }
    }
}

/** Appearance, session security and update preferences, grouped; every change applies and is saved at once. */
@Composable
internal fun SettingsDialog(preferences: AppSettings, onDismiss: () -> Unit, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(UiText.text("shell.security")) },
        text = {
            Column(Modifier.widthIn(min = 520.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(UiText.text("settings.appearance"))
                    SettingRow(UiText.text("shell.theme")) {
                        SegmentedControl(ThemeMode.entries.map { it to UiText.text("shell.theme.${it.name.lowercase()}") }, preferences.theme) { value ->
                            onChange { it.copy(theme = value) }
                        }
                    }
                    SettingRow(UiText.text("shell.contrast")) {
                        SegmentedControl(ContrastMode.entries.map { it to UiText.text("shell.contrast.${it.name.lowercase()}") }, preferences.contrast) { value ->
                            onChange { it.copy(contrast = value) }
                        }
                    }
                    SettingRow(UiText.text("shell.uiScale")) {
                        Choice(UiText.text("shell.uiScale"), preferences.uiScale.toString(), UI_SCALE_CHOICES.map { it.toString() to UiText.text("shell.uiScaleValue", it) }, nullable = false, showLabel = false) {
                            it?.toInt()?.let { value -> onChange { current -> current.copy(uiScale = value) } }
                        }
                    }
                    SettingRow(UiText.text("shell.language")) {
                        Choice(UiText.text("shell.language"), preferences.language.name, AppLanguage.entries.map { it.name to UiText.text("shell.language.${it.name.lowercase()}") }, nullable = false, showLabel = false) {
                            it?.let { value -> onChange { current -> current.copy(language = AppLanguage.valueOf(value)) } }
                        }
                    }
                }
                Divider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(UiText.text("settings.session"))
                    SettingRow(UiText.text("shell.lockMinutes")) {
                        Choice(UiText.text("shell.lockMinutes"), preferences.inactivityMinutes.toString(), LOCK_MINUTE_CHOICES.map { it.toString() to it.toString() }, nullable = false, showLabel = false) {
                            it?.toInt()?.let { value -> onChange { current -> current.copy(inactivityMinutes = value) } }
                        }
                    }
                    SettingRow(UiText.text("shell.windowLock")) {
                        Choice(UiText.text("shell.windowLock"), preferences.windowLock.name, WindowLockPolicy.entries.map { it.name to UiText.text("shell.windowLock.${it.name.lowercase()}") }, nullable = false, showLabel = false) {
                            it?.let { value -> onChange { current -> current.copy(windowLock = WindowLockPolicy.valueOf(value)) } }
                        }
                    }
                    SettingRow(UiText.text("shell.clipboardSeconds")) {
                        Choice(UiText.text("shell.clipboardSeconds"), preferences.clipboardSeconds.toString(), CLIPBOARD_SECOND_CHOICES.map { it.toString() to it.toString() }, nullable = false, showLabel = false) {
                            it?.toLong()?.let { value ->
                                runCatching { SecretClipboard.configure(value) }
                                onChange { current -> current.copy(clipboardSeconds = value) }
                            }
                        }
                    }
                    HintText(UiText.text("shell.settingsHint"))
                }
                Divider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle(UiText.text("settings.updates"))
                    UpdateCheckSetting(preferences.checkUpdatesOnStart) { value -> onChange { it.copy(checkUpdatesOnStart = value) } }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text(UiText.text("shell.close")) } },
    )
}

@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f))
        control()
    }
}
