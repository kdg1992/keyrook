// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Option lists longer than this get a search field at the top of the menu. */
internal const val CHOICE_SEARCH_THRESHOLD = 8

/** Options whose title contains every word of [query], ignoring case; a blank query keeps all. */
internal fun filterChoices(options: List<Pair<String, String>>, query: String): List<Pair<String, String>> {
    val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return options.filter { (_, title) -> words.all { title.contains(it, ignoreCase = true) } }
}

/**
 * A labelled drop-down: the label and the current value on one outlined button, the options in a menu. Long lists
 * ([CHOICE_SEARCH_THRESHOLD]) start with a search field that has the focus, and Enter picks the only remaining option.
 */
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
            var query by remember { mutableStateOf("") }
            val searchable = options.size > CHOICE_SEARCH_THRESHOLD
            val shownOptions = if (searchable) filterChoices(options, query) else options
            if (searchable) {
                val focus = rememberInitialFocus()
                OutlinedTextField(query, { if (it.length <= 64) query = it }, singleLine = true,
                    label = { Text(UiText.text("choice.search", label)) },
                    modifier = Modifier.padding(horizontal = 8.dp).widthIn(min = 220.dp).focusRequester(focus).submitOnEnter {
                        shownOptions.singleOrNull()?.let { (id, _) -> changed(id); expanded = false }
                    })
            }
            if (nullable && query.isBlank()) DropdownMenuItem(onClick = { changed(null); expanded = false }) { Text(UiText.text("choice.none")) }
            if (searchable && shownOptions.isEmpty()) DropdownMenuItem(onClick = {}, enabled = false) { Text(UiText.text("choice.noMatch")) }
            shownOptions.forEach { (id, title) ->
                DropdownMenuItem(onClick = { changed(id); expanded = false }) {
                    Text(title, fontWeight = if (id == value) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (id == value) colors.primary else colors.onSurface)
                }
            }
        }
    }
}
