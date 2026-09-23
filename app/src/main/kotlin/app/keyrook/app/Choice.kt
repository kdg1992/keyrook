// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.Box
import androidx.compose.material.*
import androidx.compose.runtime.*

@Composable
internal fun Choice(label: String, value: String?, options: List<Pair<String, String>>, enabled: Boolean = true,
                    nullable: Boolean = true, changed: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(enabled = enabled, onClick = { expanded = true }) {
            Text("$label: ${options.find { it.first == value }?.second ?: "–"}")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (nullable) DropdownMenuItem(onClick = { changed(null); expanded = false }) { Text("–") }
            options.forEach { (id, title) -> DropdownMenuItem(onClick = { changed(id); expanded = false }) { Text(title) } }
        }
    }
}
