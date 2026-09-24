// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.Row
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
import app.keyrook.core.model.Vault

/** The customers and projects section, opened from the toolbar and shown above the entry list until closed. */
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
    var confirmRemoval by remember { mutableStateOf(false) }
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
                }) { Text(UiText.text("organization.editCustomer", item.name)) }
            }
            items(vault.projects, key = { "project:${it.id}" }) { item ->
                TextButton(enabled = !busy, onClick = {
                    editingProject = item.id; editingCustomer = null; editName = item.name
                    editCustomer = item.customerId; confirmRemoval = false
                }) { Text(UiText.text("organization.editProject", item.name)) }
            }
        }
    } }
    val selectedCustomer = editingCustomer
    val selectedProject = editingProject
    if (selectedCustomer != null || selectedProject != null) {
        val inUse = if (selectedCustomer != null)
            vault.projects.any { it.customerId == selectedCustomer } || vault.entries.any { it.customerId == selectedCustomer }
        else vault.entries.any { it.projectId == selectedProject }
        fun dismiss() { editingCustomer = null; editingProject = null; confirmRemoval = false }
        AlertDialog(
            onDismissRequest = { if (!busy) dismiss() },
            title = { Text(if (confirmRemoval) UiText.text("organization.removeTitle") else if (selectedCustomer != null) UiText.text("organization.customerTitle") else UiText.text("organization.projectTitle")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (confirmRemoval) {
                        Text(UiText.text("organization.removeBody", editName))
                    } else {
                        OutlinedTextField(editName, { editName = it }, enabled = !busy,
                            label = { Text(UiText.text("common.name")) }, singleLine = true, isError = editName.length > 4096)
                        if (selectedProject != null) {
                            Choice(UiText.text("common.customer"), editCustomer, vault.customers.map { it.id to it.name }, !busy) { editCustomer = it }
                            Text(UiText.text("organization.moveHint"))
                        }
                        if (inUse) Text(UiText.text("organization.inUse"))
                        else TextButton(enabled = !busy, onClick = { confirmRemoval = true }) { Text(UiText.text("organization.remove")) }
                    }
                }
            },
            confirmButton = {
                Button(enabled = !busy && if (confirmRemoval) !inUse else editName.isNotBlank() && editName.length <= 4096,
                    onClick = {
                        val name = editName; val customer = editCustomer; val remove = confirmRemoval
                        operation {
                            when {
                                selectedCustomer != null && remove -> controller.removeCustomer(selectedCustomer, true)
                                selectedCustomer != null -> controller.renameCustomer(selectedCustomer, name)
                                remove -> controller.removeProject(requireNotNull(selectedProject), true)
                                else -> controller.updateProject(requireNotNull(selectedProject), name, customer)
                            }
                        }
                        dismiss()
                    }) { Text(if (confirmRemoval) UiText.text("organization.removeConfirm") else UiText.text("common.save")) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { if (confirmRemoval) confirmRemoval = false else dismiss() }) { Text(UiText.text("common.cancel")) } },
        )
    }
}
