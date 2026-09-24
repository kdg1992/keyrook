// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.model.Vault
import app.keyrook.core.report.ReportText
import app.keyrook.core.report.customerOverviewText
import app.keyrook.core.report.customerOverviews

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
)
