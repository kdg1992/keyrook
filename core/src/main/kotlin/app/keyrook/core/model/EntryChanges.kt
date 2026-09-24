// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.core.model

import java.time.Instant

/**
 * Tags with a meaning of their own. They are stored in [Entry.tags] like any other tag, so the vault format, JSON/CSV
 * export and import are unchanged, but the desktop app hides them from tag lists and never creates them from typed
 * tag input. The `keyrook:` prefix keeps them apart from ordinary tags in practice.
 */
object ReservedTags {
    /** Marks an entry as a favorite. */
    const val FAVORITE = "keyrook:favorite"

    fun isReserved(tag: String): Boolean = tag == FAVORITE

    /** The tags shown to and edited by the user, in stored order. */
    fun visible(tags: List<String>): List<String> = tags.filterNot(::isReserved)
}

val Entry.favorite: Boolean get() = ReservedTags.FAVORITE in tags

/** A changed entry's new time: [now], but never before its last change, so a clock set back cannot reorder history. */
private fun changeTime(entry: Entry, now: Instant): String = maxOf(now, Instant.parse(entry.modifiedAt)).toString()

private fun Vault.requireEntries(ids: Set<String>): List<Entry> {
    require(ids.isNotEmpty())
    val selected = entries.filter { it.id in ids }
    require(selected.size == ids.size) { "Unknown entry" }
    return selected
}

/**
 * Moves the entries [ids] to the trash, or restores them from it with [restore]. Every entry must currently be on the
 * other side; otherwise nothing changes and the call fails. The result shares all objects with this vault, so the
 * caller keeps ownership; saving it applies the whole change as one revision.
 */
fun Vault.trashEntries(ids: Set<String>, restore: Boolean, now: Instant = Instant.now()): Vault {
    require(requireEntries(ids).all { (it.deletedAt != null) == restore }) { "Entry already moved" }
    return copy(entries = entries.map { entry ->
        if (entry.id !in ids) entry else {
            val stamp = changeTime(entry, now)
            entry.copy(deletedAt = if (restore) null else stamp, modifiedAt = stamp)
        }
    })
}

/**
 * Adds [tag] to, or with [add] false removes it from, the entries [ids]. Entries that already have (or lack) the tag
 * stay the same objects with their change time; when no entry changes, this same vault is returned so the caller
 * can skip saving. A tag must be trimmed, non-blank, at most 256 characters and free of commas, which separate tags
 * in the editor. Tag count limits are checked by [Vault.validate] on the result. The result shares all objects with
 * this vault, so the caller keeps ownership.
 */
fun Vault.tagEntries(ids: Set<String>, tag: String, add: Boolean, now: Instant = Instant.now()): Vault {
    require(tag.isNotBlank() && tag == tag.trim() && tag.length <= MAX_TAG_CHARS && ',' !in tag) { "Invalid tag" }
    requireEntries(ids)
    var changed = false
    val updated = entries.map { entry ->
        if (entry.id !in ids || (tag in entry.tags) == add) entry else {
            changed = true
            entry.copy(tags = if (add) entry.tags + tag else entry.tags.filterNot { it == tag },
                modifiedAt = changeTime(entry, now))
        }
    }
    return if (changed) copy(entries = updated) else this
}

private const val MAX_TAG_CHARS = 256
