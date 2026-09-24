// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.Customer
import app.keyrook.core.model.Vault
import app.keyrook.core.report.MAX_REMINDER_DAYS
import app.keyrook.core.report.ReportText
import app.keyrook.core.report.customerOverviewText
import app.keyrook.core.report.customerOverviews
import app.keyrook.core.report.expiryCsv
import app.keyrook.core.report.expiryIcs
import app.keyrook.core.report.expiryItems
import app.keyrook.core.report.handoverSheetHtml
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** Report labels from the selected language's message catalog. */
internal val reportText = ReportText { UiText.text(it) }

/**
 * The data menu's report group. Reports are built by core from a snapshot that is erased afterwards; they contain
 * metadata and fields not marked hidden only, never passwords, keys, passphrases, TOTP secrets or notes.
 */
internal fun reportActions(controller: VaultController, dialogs: Dialogs, operation: (() -> Vault?) -> Unit): List<DataAction> = listOf(
    DataAction("report.overview") { operation {
        val text = controller.session.snapshot().use { customerOverviewText(customerOverviews(it), reportText) }
        dialogs.report(UiText.text("report.overview"), text)
        controller.session.snapshot()
    } },
    DataAction("report.handover") { operation { exportHandoverSheet(controller, dialogs); controller.session.snapshot() } },
    DataAction("report.expiry") { operation { exportExpiryDates(controller, dialogs); controller.session.snapshot() } },
)

internal enum class ExpiryFormat(private val labelKey: String, val fileType: DialogFile) {
    ICS("report.format.ics", DialogFile.ICS), CSV("transfer.format.csv", DialogFile.CSV);
    val label: String get() = UiText.text(labelKey)
}

/** Asks for a customer and a new file, then writes that customer's handover sheet with owner-only permissions. */
private fun exportHandoverSheet(controller: VaultController, dialogs: Dialogs) {
    val customers = controller.session.snapshot().use { vault ->
        vault.customers.sortedWith(compareBy<Customer> { it.name.lowercase(Locale.ROOT) }.thenBy { it.name })
    }
    if (customers.isEmpty()) {
        dialogs.inform(UiText.text("report.noCustomers"))
        return
    }
    val customer = dialogs.choose(UiText.text("report.chooseCustomer"), customers, Customer::name) ?: return
    val target = onEdt { chooseNewFile(DialogFile.HTML, "handover.html") } ?: return
    val html = controller.session.snapshot().use {
        handoverSheetHtml(it, customer.id, reportText, UiText.locale.language, LocalDate.now())
    }
    writePrivateNew(target, html.toByteArray(Charsets.UTF_8))
    dialogs.inform(UiText.text("report.handoverDone"))
}

/** Writes the expiry dates of all active entries as a calendar or a table to a new owner-only file. */
private fun exportExpiryDates(controller: VaultController, dialogs: Dialogs) {
    val items = controller.session.snapshot().use { expiryItems(it) }
    if (items.isEmpty()) {
        dialogs.inform(UiText.text("report.expiryEmpty"))
        return
    }
    val format = dialogs.choose(UiText.text("report.expiryFormat"), ExpiryFormat.entries, ExpiryFormat::label) ?: return
    val reminder = if (format == ExpiryFormat.ICS) (askReminderDays(dialogs) ?: return) else 0
    val target = onEdt { chooseNewFile(format.fileType, "keyrook-expiry.${format.fileType.extension}") } ?: return
    val content = when (format) {
        ExpiryFormat.ICS -> expiryIcs(items, reportText, Instant.now(), reminder.takeIf { it > 0 })
        ExpiryFormat.CSV -> expiryCsv(items, reportText)
    }
    writePrivateNew(target, content.toByteArray(Charsets.UTF_8))
    dialogs.inform(UiText.text("report.expiryDone", items.size))
}

/** Days before the expiry date for a calendar reminder; 0 means none and null means canceled. */
private fun askReminderDays(dialogs: Dialogs): Int? {
    var days = "30"
    while (true) {
        days = dialogs.ask(FieldsRequest(UiText.text("report.reminderTitle"), emptyList(),
            listOf(InputField(UiText.text("report.reminderDays"), days)), emptyList()))?.firstOrNull() ?: return null
        days.trim().toIntOrNull()?.takeIf { it in 0..MAX_REMINDER_DAYS }?.let { return it }
        dialogs.inform(UiText.text("report.reminderInvalid", MAX_REMINDER_DAYS))
    }
}
