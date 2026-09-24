// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

/** How the unlocked vault is arranged: list and entry details side by side, or the list alone. */
internal enum class WorkspaceLayout { LIST_DETAIL, SINGLE_COLUMN }

/** Content width, in dp, from which the list and the entry details are shown side by side. */
internal const val LIST_DETAIL_MIN_WIDTH_DP = 900f

/** Unknown or unbounded widths fall back to the single column, which works at every size. */
internal fun workspaceLayout(widthDp: Float): WorkspaceLayout =
    if (widthDp.isFinite() && widthDp >= LIST_DETAIL_MIN_WIDTH_DP) WorkspaceLayout.LIST_DETAIL else WorkspaceLayout.SINGLE_COLUMN
