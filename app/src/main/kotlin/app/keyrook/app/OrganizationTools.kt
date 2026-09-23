// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.Row
import androidx.compose.material.*
import androidx.compose.runtime.*
import app.keyrook.core.model.Vault

@Composable
internal fun OrganizationTools(vault: Vault, controller: VaultController, busy: Boolean, operation: (() -> Vault?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var customer by remember { mutableStateOf("") }
    var project by remember { mutableStateOf("") }
    var customerId by remember { mutableStateOf<String?>(null) }
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
    }
}
