// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.Box
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable
internal fun Choice(label: String, value: String?, options: List<Pair<String, String>>, enabled: Boolean = true,
                    nullable: Boolean = true, modifier: Modifier = Modifier, changed: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(enabled = enabled, onClick = { expanded = true }, modifier = modifier) {
            Text(UiText.text("choice.label", label, options.find { it.first == value }?.second ?: UiText.text("choice.none")))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (nullable) DropdownMenuItem(onClick = { changed(null); expanded = false }) { Text(UiText.text("choice.none")) }
            options.forEach { (id, title) -> DropdownMenuItem(onClick = { changed(id); expanded = false }) { Text(title) } }
        }
    }
}
