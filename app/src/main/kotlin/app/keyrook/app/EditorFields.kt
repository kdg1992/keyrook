// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/** Field-level message below an input; Material 2 text fields have no supporting-text slot. */
@Composable
internal fun FieldError(error: InputError?, modifier: Modifier = Modifier) {
    if (error != null) Text(error.message(), modifier = modifier.padding(start = 16.dp),
        color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
}

/** Free text editing; only valid ports reach the model, invalid or empty text stays visible with an error. */
@Composable
internal fun PortField(text: String, enabled: Boolean, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val error = portError(text)
    Column(modifier) {
        OutlinedTextField(text, onChange, label = { Text(UiText.text("editor.port")) }, enabled = enabled,
            isError = error != null, singleLine = true)
        FieldError(error)
    }
}

@Composable
internal fun ExpiryField(text: String, enabled: Boolean, onChange: (String) -> Unit) {
    val parsed = ExpiryDates.parse(text)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, onChange, label = { Text(UiText.text("editor.expiry")) }, enabled = enabled,
                isError = parsed == ExpiryInput.Invalid, singleLine = true)
            TextButton(enabled = enabled, onClick = { onChange(ExpiryDates.plusOneYear(text, LocalDate.now())) }) {
                Text(UiText.text("editor.expiryPlusYear"))
            }
            TextButton(enabled = enabled && text.isNotEmpty(), onClick = { onChange("") }) { Text(UiText.text("editor.expiryClear")) }
        }
        when (parsed) {
            ExpiryInput.Invalid -> FieldError(InputError(InputProblem.INVALID_DATE))
            is ExpiryInput.Valid -> Text(UiText.text("editor.expiryParsed", parsed.normalized), Modifier.padding(start = 16.dp),
                style = MaterialTheme.typography.caption)
            ExpiryInput.Empty -> Unit
        }
    }
}
