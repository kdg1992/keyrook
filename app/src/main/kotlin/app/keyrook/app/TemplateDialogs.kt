// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.keyrook.core.model.Entry
import app.keyrook.core.model.EntryTemplate
import app.keyrook.core.model.TemplateType
import app.keyrook.core.model.Vault
import java.util.Locale

/** The editor's entry type for a template's type; both enumerations use the same names. */
internal fun TemplateType.entryType(): EntryType = EntryType.valueOf(name)

/** Templates sorted by name for choosing, case-insensitively and then by ID for a stable order. */
internal fun sortedTemplates(vault: Vault): List<EntryTemplate> =
    vault.templates.sortedWith(compareBy<EntryTemplate> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name }.thenBy { it.id })

/** A template name as typed: trimmed, non-blank and at most 4,096 characters, or null. */
internal fun templateName(text: String): String? = text.trim().takeIf { it.isNotEmpty() && it.length <= 4096 }

/** Lists the vault's templates; choosing one starts a new entry with its layout. */
@Composable
internal fun TemplateChooserDialog(vault: Vault, busy: Boolean, onDismiss: () -> Unit, onChoose: (EntryTemplate) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(UiText.text("template.chooseTitle")) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(UiText.text("template.chooseHint"))
                sortedTemplates(vault).forEach { template ->
                    TextButton(enabled = !busy, onClick = { onChoose(template) }) {
                        Text(UiText.text("template.option", template.name, template.type.entryType().label))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(UiText.text("common.cancel")) } },
    )
}

/**
 * Asks for the name of a new template made from [entry]'s layout. The dialog states that no value, notes or secret
 * becomes part of the template.
 */
@Composable
internal fun SaveTemplateDialog(entry: Entry, busy: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember(entry.id) { mutableStateOf(entry.title.take(4096)) }
    val valid = templateName(name) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(UiText.text("template.saveTitle")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { if (it.length <= 4096 + 16) name = it }, enabled = !busy,
                    label = { Text(UiText.text("common.name")) }, singleLine = true, isError = !valid)
                Text(UiText.text("template.saveHint"))
            }
        },
        confirmButton = {
            Button(enabled = !busy && valid, onClick = { templateName(name)?.let(onSave) }) { Text(UiText.text("common.save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(UiText.text("common.cancel")) } },
    )
}
