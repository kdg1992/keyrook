// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

/** Identifies the displayed version of an entry. Saving the entry changes [modifiedAt] and so masks it again. */
internal data class RevealKey(val vaultId: String, val entryId: String, val modifiedAt: String)

/**
 * Which masked values of the detail view are shown in plain text. Holds value positions only, never values.
 * Everything starts masked. A different entry, vault or saved version masks everything again ([follow]); locking,
 * leaving or minimizing the window and opening the editor mask everything as well ([cleared]).
 */
internal data class RevealState(val key: RevealKey? = null, val revealed: Set<Int> = emptySet()) {
    fun follow(key: RevealKey?): RevealState = if (key == this.key) this else RevealState(key)

    /** Shows or masks one position of the entry identified by [key]; other entries' positions are masked first. */
    fun toggle(key: RevealKey, slot: Int): RevealState {
        val current = follow(key)
        return current.copy(revealed = if (slot in current.revealed) current.revealed - slot else current.revealed + slot)
    }

    fun shows(key: RevealKey?, slot: Int): Boolean = key != null && key == this.key && slot in revealed

    fun cleared(): RevealState = if (revealed.isEmpty()) this else RevealState(key)

    companion object {
        /** Position of the entry notes; fields use their index in [app.keyrook.core.model.EntryData.fields]. */
        const val NOTES = -1

        /** Position of the current TOTP code computed from a web login's secret. */
        const val TOTP_CODE = -2
    }
}
