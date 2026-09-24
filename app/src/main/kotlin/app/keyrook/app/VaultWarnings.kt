// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import app.keyrook.core.security.EntryHealth
import app.keyrook.core.security.HealthIssue

/** Numbers of entries per [HealthIssue] and of entries with any issue; each entry counts once per issue. */
internal data class WarningCounts(val expired: Int = 0, val expiringSoon: Int = 0, val weak: Int = 0, val reused: Int = 0,
                                  val entries: Int = 0, val old: Int = 0, val duplicates: Int = 0, val breached: Int = 0) {
    fun count(issue: HealthIssue): Int = when (issue) {
        HealthIssue.EXPIRED -> expired
        HealthIssue.EXPIRING_SOON -> expiringSoon
        HealthIssue.SHORT_OR_REPETITIVE_PASSWORD -> weak
        HealthIssue.REUSED_PASSWORD -> reused
        HealthIssue.OLD_PASSWORD -> old
        HealthIssue.DUPLICATE_ENTRY -> duplicates
        HealthIssue.BREACHED_PASSWORD -> breached
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
        count(HealthIssue.OLD_PASSWORD), count(HealthIssue.DUPLICATE_ENTRY), count(HealthIssue.BREACHED_PASSWORD))
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
    listOf(HealthIssue.BREACHED_PASSWORD, HealthIssue.SHORT_OR_REPETITIVE_PASSWORD, HealthIssue.REUSED_PASSWORD,
        HealthIssue.OLD_PASSWORD).filter { it in issues }

/** Reasons shown in the error color: an expired entry and a password found in known breaches. */
internal fun severeIssue(issue: HealthIssue): Boolean = issue == HealthIssue.EXPIRED || issue == HealthIssue.BREACHED_PASSWORD

/**
 * Adds [HealthIssue.BREACHED_PASSWORD] for the entry IDs in [breached], the still valid results of a breach check the
 * user started, to the local [findings]. While the local check runs (null) the result stays null.
 */
internal fun withBreaches(findings: List<EntryHealth>?, breached: Map<String, Long>): List<EntryHealth>? {
    if (findings == null || breached.isEmpty()) return findings
    val listed = findings.mapTo(HashSet()) { it.entryId }
    return findings.map { if (it.entryId in breached) it.copy(issues = it.issues + HealthIssue.BREACHED_PASSWORD) else it } +
        breached.keys.filter { it !in listed }.map { EntryHealth(it, setOf(HealthIssue.BREACHED_PASSWORD)) }
}
