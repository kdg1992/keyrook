// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.keyrook.core.model.OrganizationMetadata
import app.keyrook.core.model.Vault

/**
 * The customers, projects and templates section, opened from the toolbar and shown above the entry list until closed.
 * Customer and project notes are secrets: the edit dialog holds them as text only while it is open, like the entry
 * editor, and hands them to the controller as a character array that is erased afterwards.
 */
@Composable
internal fun OrganizationTools(vault: Vault, controller: VaultController, busy: Boolean, operation: (() -> Vault?) -> Unit,
                               onClose: () -> Unit) {
    var customer by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }
    var customerId by remember { mutableStateOf<String?>(null) }
    var editingCustomer by remember { mutableStateOf<String?>(null) }
    var editingProject by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editCustomer by remember { mutableStateOf<String?>(null) }
    var editMetadata by remember { mutableStateOf(CustomerMetadata()) }
    var editDescription by remember { mutableStateOf("") }
    var editNotes by remember { mutableStateOf("") }
    var confirmRemoval by remember { mutableStateOf(false) }
    var deletingTemplate by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth(), elevation = 2.dp) { Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(UiText.text("organization.title"), style = MaterialTheme.typography.h6, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text(UiText.text("shell.close")) }
        }
        Row {
            OutlinedTextField(customer, { customer = it }, enabled = !busy, label = { Text(UiText.text("organization.newCustomer")) })
            Button(enabled = !busy && customer.isNotBlank(), onClick = {
                val name = customer; operation { controller.addCustomer(name) }; customer = ""
            }) { Text(UiText.text("organization.addCustomer")) }
        }
        Row {
            OutlinedTextField(project, { project = it }, enabled = !busy, label = { Text(UiText.text("organization.newProject")) })
            Choice(UiText.text("common.customer"), customerId, vault.customers.map { it.id to it.name }, !busy) { customerId = it }
            Button(enabled = !busy && project.isNotBlank(), onClick = {
                val name = project; val selected = customerId
                operation { controller.addProject(name, selected) }; project = ""
            }) { Text(UiText.text("organization.addProject")) }
        }
        LazyColumn(Modifier.heightIn(max = 220.dp)) {
            items(vault.customers, key = { "customer:${it.id}" }) { item ->
                TextButton(enabled = !busy, onClick = {
                    editingCustomer = item.id; editingProject = null; editName = item.name; confirmRemoval = false
                    editMetadata = CustomerMetadata.of(item)
                    editNotes = runCatching { item.notes.useChars { String(it) } }.getOrDefault("")
                }) { Text(UiText.text("organization.editCustomer", item.name)) }
            }
            items(vault.projects, key = { "project:${it.id}" }) { item ->
                TextButton(enabled = !busy, onClick = {
                    editingProject = item.id; editingCustomer = null; editName = item.name
                    editCustomer = item.customerId; confirmRemoval = false
                    editDescription = item.description.orEmpty()
                    editNotes = runCatching { item.notes.useChars { String(it) } }.getOrDefault("")
                }) { Text(UiText.text("organization.editProject", item.name)) }
            }
            items(vault.templates, key = { "template:${it.id}" }) { item ->
                TextButton(enabled = !busy, onClick = { deletingTemplate = item.id }) {
                    Text(UiText.text("template.delete", item.name))
                }
            }
        }
        if (vault.templates.isEmpty()) Text(UiText.text("template.none"), style = MaterialTheme.typography.caption)
    } }
    val template = deletingTemplate?.let { id -> vault.templates.firstOrNull { it.id == id } }
    if (template != null) {
        val id = template.id
        AlertDialog(
            onDismissRequest = { if (!busy) deletingTemplate = null },
            title = { Text(UiText.text("template.deleteTitle")) },
            text = { Text(UiText.text("template.deleteBody", template.name)) },
            confirmButton = {
                Button(enabled = !busy, onClick = { operation { controller.deleteTemplate(id) }; deletingTemplate = null }) {
                    Text(UiText.text("template.deleteConfirm"))
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { deletingTemplate = null }) { Text(UiText.text("common.cancel")) } },
        )
    }
    val selectedCustomer = editingCustomer
    val selectedProject = editingProject
    if (selectedCustomer != null || selectedProject != null) {
        val inUse = if (selectedCustomer != null)
            vault.projects.any { it.customerId == selectedCustomer } || vault.entries.any { it.customerId == selectedCustomer }
        else vault.entries.any { it.projectId == selectedProject }
        fun dismiss() { editingCustomer = null; editingProject = null; confirmRemoval = false; editNotes = "" }
        val metadataValid = editMetadata.valid
        val descriptionValid = OrganizationMetadata.isValidDescription(OrganizationMetadata.normalize(editDescription))
        val notesValid = editNotes.length <= Vault.MAX_FIELD_CHARS
        val detailsValid = notesValid && if (selectedCustomer != null) metadataValid.all { it } else descriptionValid
        AlertDialog(
            onDismissRequest = { if (!busy) dismiss() },
            title = { Text(if (confirmRemoval) UiText.text("organization.removeTitle") else if (selectedCustomer != null) UiText.text("organization.customerTitle") else UiText.text("organization.projectTitle")) },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (confirmRemoval) {
                        Text(UiText.text("organization.removeBody", editName))
                    } else {
                        OutlinedTextField(editName, { editName = it }, enabled = !busy,
                            label = { Text(UiText.text("common.name")) }, singleLine = true, isError = editName.length > 4096)
                        if (selectedCustomer != null) {
                            val fields = listOf("organization.contactName", "organization.contactEmail", "organization.phone", "organization.website")
                            val values = listOf(editMetadata.contactName, editMetadata.contactEmail, editMetadata.phone, editMetadata.website)
                            fields.forEachIndexed { index, key ->
                                OutlinedTextField(values[index], { value ->
                                    editMetadata = when (index) {
                                        0 -> editMetadata.copy(contactName = value)
                                        1 -> editMetadata.copy(contactEmail = value)
                                        2 -> editMetadata.copy(phone = value)
                                        else -> editMetadata.copy(website = value)
                                    }
                                }, enabled = !busy, label = { Text(UiText.text(key)) }, singleLine = true, isError = !metadataValid[index])
                            }
                            if (!metadataValid.all { it }) Text(UiText.text("organization.metadataInvalid"), color = MaterialTheme.colors.error)
                        }
                        if (selectedProject != null) {
                            Choice(UiText.text("common.customer"), editCustomer, vault.customers.map { it.id to it.name }, !busy) { editCustomer = it }
                            Text(UiText.text("organization.moveHint"))
                            OutlinedTextField(editDescription, { editDescription = it }, enabled = !busy,
                                label = { Text(UiText.text("organization.description")) }, isError = !descriptionValid)
                            if (!descriptionValid) Text(UiText.text("organization.descriptionInvalid", OrganizationMetadata.MAX_DESCRIPTION_CHARS),
                                color = MaterialTheme.colors.error)
                        }
                        OutlinedTextField(editNotes, { editNotes = it }, enabled = !busy,
                            label = { Text(UiText.text("organization.notes")) }, isError = !notesValid)
                        Text(UiText.text("organization.notesHint"), style = MaterialTheme.typography.caption)
                        if (inUse) Text(UiText.text("organization.inUse"))
                        else TextButton(enabled = !busy, onClick = { confirmRemoval = true }) { Text(UiText.text("organization.remove")) }
                    }
                }
            },
            confirmButton = {
                Button(enabled = !busy && if (confirmRemoval) !inUse else editName.isNotBlank() && editName.length <= 4096 && detailsValid,
                    onClick = {
                        val name = editName; val customer = editCustomer; val remove = confirmRemoval
                        val metadata = editMetadata; val description = editDescription; val notes = editNotes
                        operation {
                            val chars = notes.toCharArray()
                            try {
                                when {
                                    selectedCustomer != null && remove -> controller.removeCustomer(selectedCustomer, true)
                                    selectedCustomer != null -> controller.updateCustomer(selectedCustomer, name, metadata, chars)
                                    remove -> controller.removeProject(requireNotNull(selectedProject), true)
                                    else -> controller.updateProject(requireNotNull(selectedProject), name, customer, description, chars)
                                }
                            } finally { chars.fill('\u0000') }
                        }
                        dismiss()
                    }) { Text(if (confirmRemoval) UiText.text("organization.removeConfirm") else UiText.text("common.save")) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { if (confirmRemoval) confirmRemoval = false else dismiss() }) { Text(UiText.text("common.cancel")) } },
        )
    }
}
