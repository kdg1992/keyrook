// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.Vault
import app.keyrook.core.transfer.CsvMapping
import app.keyrook.core.transfer.InvalidImportException
import app.keyrook.core.transfer.VaultTransfer

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
            throw IllegalArgumentException(UiText.text("csv.invalid"))
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

/** Numbered, single-line display of an untrusted header; control characters cannot forge additional lines. */
internal fun csvColumnLabel(columns: List<String>, index: Int): String =
    "${index + 1}: ${columns[index].map { if (it.isISOControl()) ' ' else it }.joinToString("")}"

/** Runs on the vault worker and waits for the mapping dialog; null when it is canceled or the vault locks. */
internal fun askCsvMapping(dialogs: Dialogs, columns: List<String>): CsvMapping? =
    dialogs.ask(CsvMappingRequest(columns, suggestedCsvMapping(columns)))
