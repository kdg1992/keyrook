// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.Vault
import app.keyrook.core.transfer.CsvMapping
import app.keyrook.core.transfer.InvalidImportException
import app.keyrook.core.transfer.VaultTransfer
import java.awt.GridLayout
import java.awt.Dimension
import javax.swing.*

/** Takes ownership of the input buffer, including cancellation and expired-session paths. */
internal fun importMappedCsv(
    bytes: ByteArray,
    guard: () -> Unit = capturedOperationGuard(),
    selectMapping: (List<String>) -> CsvMapping?,
): Vault? {
    try {
        guard()
        val transfer = VaultTransfer()
        val columns = try { transfer.csvColumns(bytes) } catch (_: InvalidImportException) {
            throw IllegalArgumentException("CSV-Kopf ungültig: maximal 100 eindeutige, nicht leere Spaltennamen mit jeweils höchstens 512 Zeichen und gültigem UTF-8 verwenden.")
        }
        val mapping = if (columns == listOf("keyrook-json")) null else selectMapping(columns) ?: return null
        guard()
        val imported = transfer.importCsv(bytes, mapping)
        try { guard(); return imported } catch (failure: Exception) { imported.close(); throw failure }
    } finally { bytes.fill(0) }
}

internal fun suggestedCsvMapping(columns: List<String>): CsvMapping {
    require(columns.isNotEmpty())
    fun find(vararg names: String) = names.firstNotNullOfOrNull { name ->
        columns.firstOrNull { it.equals(name, ignoreCase = true) }
    }
    return CsvMapping(find("Title", "Titel", "Name") ?: columns.first(),
        find("URL", "Website", "Webseite"), find("UserName", "Username", "Benutzername", "Login"),
        find("Password", "Passwort"), find("Notes", "Notizen", "Comment"))
}

/** Called on the EDT through the session-guarded modal dispatcher. No data-row preview is shown. */
internal fun askCsvMapping(columns: List<String>): CsvMapping? {
    check(SwingUtilities.isEventDispatchThread())
    val suggested = suggestedCsvMapping(columns)
    data class Column(val index: Int?, val label: String) { override fun toString() = label }
    fun selector(selected: String?, optional: Boolean): JComboBox<Column> {
        val choices = (if (optional) listOf(Column(null, "Nicht zuordnen")) else emptyList()) +
            columns.mapIndexed { index, name ->
                Column(index, "${index + 1}: ${name.map { if (it.isISOControl()) ' ' else it }.joinToString("")}")
            }
        return JComboBox(choices.toTypedArray()).apply {
            // CSV headers are untrusted text, never Swing HTML (including external image URLs).
            renderer = DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
            selectedItem = choices.firstOrNull { it.index?.let(columns::get) == selected } ?: choices.first()
            maximumRowCount = 12
            preferredSize = Dimension(360, preferredSize.height)
        }
    }
    val title = selector(suggested.title, false)
    val url = selector(suggested.url, true)
    val username = selector(suggested.username, true)
    val password = selector(suggested.password, true)
    val notes = selector(suggested.notes, true)
    val fields = JPanel(GridLayout(0, 2, 8, 8)).apply {
        listOf("Titel (Pflichtfeld)" to title, "URL" to url, "Benutzername" to username,
            "Passwort" to password, "Notizen" to notes).forEach { (name, selector) ->
            add(JLabel(name).apply { labelFor = selector }); add(selector)
        }
    }
    val panel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(JLabel("Die erste CSV-Zeile enthält die Spaltennamen."))
        add(JLabel("Nicht zugeordnete Spalten werden nicht importiert."))
        add(fields)
    }
    return try {
        if (JOptionPane.showConfirmDialog(null, panel, "CSV-Feldzuordnung",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) null
        else {
            fun JComboBox<Column>.column() = (selectedItem as Column).index?.let(columns::get)
            CsvMapping(title.column()!!, url.column(), username.column(), password.column(), notes.column())
        }
    } finally { listOf(title, url, username, password, notes).forEach { it.removeAllItems() } }
}
