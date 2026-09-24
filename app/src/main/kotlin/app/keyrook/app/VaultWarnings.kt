// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.security.EntryHealth
import app.keyrook.core.security.HealthIssue

/** Numbers of entries per [HealthIssue] and of entries with any issue; each entry counts once per issue. */
internal data class WarningCounts(val expired: Int = 0, val expiringSoon: Int = 0, val weak: Int = 0, val reused: Int = 0,
                                  val entries: Int = 0, val old: Int = 0, val duplicates: Int = 0) {
    fun count(issue: HealthIssue): Int = when (issue) {
        HealthIssue.EXPIRED -> expired
        HealthIssue.EXPIRING_SOON -> expiringSoon
        HealthIssue.SHORT_OR_REPETITIVE_PASSWORD -> weak
        HealthIssue.REUSED_PASSWORD -> reused
        HealthIssue.OLD_PASSWORD -> old
        HealthIssue.DUPLICATE_ENTRY -> duplicates
    }
}

/**
 * Aggregates core [app.keyrook.core.security.VaultHealth] findings, which contain entry IDs and reasons only.
 * Findings without issues are ignored and repeated entry IDs are merged.
 */
internal fun warningCounts(findings: List<EntryHealth>): WarningCounts {
    val byEntry = warningIssues(findings)
    fun count(issue: HealthIssue) = byEntry.values.count { issue in it }
    return WarningCounts(count(HealthIssue.EXPIRED), count(HealthIssue.EXPIRING_SOON),
        count(HealthIssue.SHORT_OR_REPETITIVE_PASSWORD), count(HealthIssue.REUSED_PASSWORD), byEntry.size,
        count(HealthIssue.OLD_PASSWORD), count(HealthIssue.DUPLICATE_ENTRY))
}

/** Issues per entry ID in finding order; entries without issues are absent. */
internal fun warningIssues(findings: List<EntryHealth>): Map<String, Set<HealthIssue>> {
    val result = linkedMapOf<String, MutableSet<HealthIssue>>()
    findings.forEach { finding ->
        if (finding.issues.isNotEmpty()) result.getOrPut(finding.entryId) { linkedSetOf() }.addAll(finding.issues)
    }
    return result
}

/** Password markers of a list card, in display order. Expiry already has its own badge with the date. */
internal fun passwordMarkers(issues: Set<HealthIssue>): List<HealthIssue> =
    listOf(HealthIssue.SHORT_OR_REPETITIVE_PASSWORD, HealthIssue.REUSED_PASSWORD, HealthIssue.OLD_PASSWORD).filter { it in issues }
