// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

/**
 * Vault tools above the entry list: the data menu (backups, transfer, account) and the customers and projects
 * section. Menu items start the same operations as before; the menu closes before an action starts.
 */
@Composable
internal fun AppToolbar(actions: List<List<DataAction>>, busy: Boolean, organizer: Boolean, onOrganizer: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            OutlinedButton(enabled = !busy, onClick = { menu = true }) { Text(UiText.text("toolbar.data")) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                actions.forEachIndexed { index, group ->
                    if (index > 0) Divider()
                    group.forEach { action ->
                        DropdownMenuItem(enabled = !busy, onClick = { menu = false; action.run() }) { Text(action.label) }
                    }
                }
            }
        }
        OutlinedButton(enabled = !busy || organizer, onClick = onOrganizer) {
            Text(UiText.text(if (organizer) "toolbar.organizationHide" else "organization.title"))
        }
    }
}
