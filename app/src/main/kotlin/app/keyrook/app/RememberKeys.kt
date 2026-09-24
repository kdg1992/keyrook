// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import java.time.LocalDate

/**
 * A `remember` key that compares [value] by identity. Vault snapshots are keyed this way: comparing them by content
 * would visit every entry, and an equal but newer snapshot must still replace results that refer to a closed one.
 */
internal class SameInstance(private val value: Any?) {
    override fun equals(other: Any?): Boolean = other is SameInstance && other.value === value
    override fun hashCode(): Int = System.identityHashCode(value)
}

/** Today's local date, checked once a minute so date-based badges and filters change at midnight. */
@Composable
internal fun rememberToday(): State<LocalDate> = produceState(LocalDate.now()) {
    while (true) { kotlinx.coroutines.delay(60_000); value = LocalDate.now() }
}
