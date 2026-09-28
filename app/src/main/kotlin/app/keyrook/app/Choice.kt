// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A labelled drop-down: the label and the current value on one outlined button, the options in a menu. */
@Composable
internal fun Choice(label: String, value: String?, options: List<Pair<String, String>>, enabled: Boolean = true,
                    nullable: Boolean = true, modifier: Modifier = Modifier, showLabel: Boolean = true, changed: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val shown = options.find { it.first == value }?.second ?: UiText.text("choice.none")
    val spoken = UiText.text("choice.label", label, shown)
    val colors = MaterialTheme.colors
    Box {
        OutlinedButton(enabled = enabled, onClick = { expanded = true }, modifier = modifier.semantics { contentDescription = spoken },
            contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            border = BorderStroke(1.dp, if (value != null && nullable) colors.primary else LocalChrome.current.outline.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface)) {
            if (showLabel) Text("$label: ", style = MaterialTheme.typography.body2, color = colors.onSurface.copy(alpha = HINT_TEXT_ALPHA))
            Text(shown, style = MaterialTheme.typography.body2, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
            Spacer(Modifier.width(4.dp))
            Icon(KeyrookIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(16.dp).align(Alignment.CenterVertically))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            if (nullable) DropdownMenuItem(onClick = { changed(null); expanded = false }) { Text(UiText.text("choice.none")) }
            options.forEach { (id, title) ->
                DropdownMenuItem(onClick = { changed(id); expanded = false }) {
                    Text(title, fontWeight = if (id == value) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (id == value) colors.primary else colors.onSurface)
                }
            }
        }
    }
}
