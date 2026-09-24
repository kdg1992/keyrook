// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.Customer
import app.keyrook.core.model.Vault
import app.keyrook.core.report.ReportText
import app.keyrook.core.report.customerOverviewText
import app.keyrook.core.report.customerOverviews
import app.keyrook.core.report.handoverSheetHtml
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
)

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
