// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

/**
 * The entries of one vault most recently opened in the editor, copied from or whose link was opened, newest first.
 * Holds entry IDs only, never values, and lives in memory only: it is never persisted, locking replaces it with an
 * empty instance, and a use in another vault starts a new list, so entries of one vault never appear for another.
 */
internal data class RecentEntries(val vaultId: String? = null, val ids: List<String> = emptyList()) {
    /** Moves [entryId] of the vault [vaultId] to the front, without duplicates, keeping at most [CAPACITY]. */
    fun used(vaultId: String, entryId: String): RecentEntries {
        val previous = if (vaultId == this.vaultId) ids else emptyList()
        return RecentEntries(vaultId, (listOf(entryId) + previous.filterNot { it == entryId }).take(CAPACITY))
    }

    /** The recent entry IDs of [vaultId], newest first; empty for any other vault. */
    fun of(vaultId: String?): List<String> = if (vaultId != null && vaultId == this.vaultId) ids else emptyList()

    companion object {
        const val CAPACITY = 10
    }
}
