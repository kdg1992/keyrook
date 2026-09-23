// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.keyrook.core.model.Vault

@Composable
internal fun OrganizationTools(vault: Vault, controller: VaultController, busy: Boolean, operation: (() -> Vault?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var customer by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }
    var customerId by remember { mutableStateOf<String?>(null) }
    var editingCustomer by remember { mutableStateOf<String?>(null) }
    var editingProject by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf("") }
    var editCustomer by remember { mutableStateOf<String?>(null) }
    var confirmRemoval by remember { mutableStateOf(false) }
    TextButton(enabled = !busy, onClick = { expanded = !expanded }) { Text("Kunden und Projekte") }
    if (expanded) {
        Row {
            OutlinedTextField(customer, { customer = it }, enabled = !busy, label = { Text("Neuer Kunde") })
            Button(enabled = !busy && customer.isNotBlank(), onClick = {
                val name = customer; operation { controller.addCustomer(name) }; customer = ""
            }) { Text("Kunde anlegen") }
        }
        Row {
            OutlinedTextField(project, { project = it }, enabled = !busy, label = { Text("Neues Projekt") })
            Choice("Kunde", customerId, vault.customers.map { it.id to it.name }, !busy) { customerId = it }
            Button(enabled = !busy && project.isNotBlank(), onClick = {
                val name = project; val selected = customerId
                operation { controller.addProject(name, selected) }; project = ""
            }) { Text("Projekt anlegen") }
        }
        LazyColumn(Modifier.heightIn(max = 220.dp)) {
            items(vault.customers, key = { "customer:${it.id}" }) { item ->
                TextButton(enabled = !busy, onClick = {
                    editingCustomer = item.id; editingProject = null; editName = item.name; confirmRemoval = false
                }) { Text("Kunde bearbeiten: ${item.name}") }
            }
            items(vault.projects, key = { "project:${it.id}" }) { item ->
                TextButton(enabled = !busy, onClick = {
                    editingProject = item.id; editingCustomer = null; editName = item.name
                    editCustomer = item.customerId; confirmRemoval = false
                }) { Text("Projekt bearbeiten: ${item.name}") }
            }
        }
    }
    val selectedCustomer = editingCustomer
    val selectedProject = editingProject
    if (selectedCustomer != null || selectedProject != null) {
        val inUse = if (selectedCustomer != null)
            vault.projects.any { it.customerId == selectedCustomer } || vault.entries.any { it.customerId == selectedCustomer }
        else vault.entries.any { it.projectId == selectedProject }
        fun dismiss() { editingCustomer = null; editingProject = null; confirmRemoval = false }
        AlertDialog(
            onDismissRequest = { if (!busy) dismiss() },
            title = { Text(if (confirmRemoval) "Endgültig entfernen?" else if (selectedCustomer != null) "Kunde bearbeiten" else "Projekt bearbeiten") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (confirmRemoval) {
                        Text("„$editName“ wird entfernt. Dieser Vorgang kann nicht rückgängig gemacht werden.")
                    } else {
                        OutlinedTextField(editName, { editName = it }, enabled = !busy,
                            label = { Text("Name") }, singleLine = true, isError = editName.length > 4096)
                        if (selectedProject != null) {
                            Choice("Kunde", editCustomer, vault.customers.map { it.id to it.name }, !busy) { editCustomer = it }
                            Text("Beim Wechsel zu einem Kunden werden alle Projekteinträge diesem Kunden zugeordnet, auch im Papierkorb. Ohne Projektkunden behalten die Einträge ihre bisherige Kundenzuordnung.")
                        }
                        if (inUse) Text("Entfernen erst möglich, wenn keine Einträge (auch im Papierkorb) oder Projekte mehr zugeordnet sind.")
                        else TextButton(enabled = !busy, onClick = { confirmRemoval = true }) { Text("Entfernen …") }
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
                    }) { Text(if (confirmRemoval) "Endgültig entfernen" else "Speichern") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { if (confirmRemoval) confirmRemoval = false else dismiss() }) { Text("Abbrechen") } },
        )
    }
}
