// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.keyrook.core.model.Field
import app.keyrook.core.otp.Totp
import java.time.Instant

private const val CODE_MASK = "••••••"

/** A shown code: its digits, the end of its period, the seconds left and the period length. */
internal data class TotpDisplay(val code: String, val validUntil: Instant, val remaining: Long, val period: Long) {
    override fun toString(): String = "TotpDisplay(code=[redacted], validUntil=$validUntil, remaining=$remaining, period=$period)"
}

/**
 * The next state of a shown code at [now]: the current code is kept while its period lasts, otherwise a new one is
 * computed from [field]. Returns null when the stored value cannot produce a code (invalid or already erased).
 */
internal fun nextTotpDisplay(field: Field, previous: TotpDisplay?, now: Instant): TotpDisplay? {
    if (previous != null && now.isBefore(previous.validUntil)) {
        val left = java.time.Duration.between(now, previous.validUntil)
        return previous.copy(remaining = left.seconds + if (left.nano > 0) 1 else 0)
    }
    return runCatching {
        Totp.code(field.value, now).use { code ->
            TotpDisplay(code.code.useChars(::String), code.validUntil, code.remainingSeconds(now), code.periodSeconds)
        }
    }.getOrNull()
}

/** Accepted formats below the editor's TOTP field. */
@Composable
internal fun TotpFormatHint() {
    Text(UiText.text("editor.totpHint"), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.caption)
}

/**
 * The current one-time code of a web login in the detail view. The code stays masked until [shown]; only then does a
 * ticker run, which refreshes the countdown every second and the code at each period boundary. Masking (toggle,
 * selection change, lock, window deactivation) removes the ticker from the composition, which stops it and drops the
 * displayed code. Copy computes a fresh code and uses the owned, expiring clipboard, whether or not it is shown.
 */
@Composable
internal fun TotpCodeRow(field: Field, shown: Boolean, busy: Boolean, onToggle: () -> Unit, onNotice: (String) -> Unit) {
    val valid = remember(field) { runCatching { Totp.isValid(field.value) }.getOrDefault(false) }
    Column {
        Text(UiText.text("totp.code"), style = MaterialTheme.typography.caption)
        if (!valid) Text(UiText.text("totp.invalid"), color = MaterialTheme.colors.error)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (shown) TotpLiveCode(field, Modifier.weight(1f))
            else Text(CODE_MASK, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
            TextButton(onClick = onToggle) { Text(UiText.text(if (shown) "detail.hide" else "detail.show")) }
            TextButton(enabled = !busy, onClick = {
                onNotice(runCatching { EntryQuickActions.copyTotp(field) }
                    .fold({ UiText.text("list.totpCopied", it) }, { UiText.text("list.copyFailed") }))
            }) { Text(UiText.text("common.copy")) }
        }
    }
}

@Composable
private fun TotpLiveCode(field: Field, modifier: Modifier) {
    var display by remember(field) { mutableStateOf(nextTotpDisplay(field, null, Instant.now())) }
    LaunchedEffect(field) {
        while (true) {
            val now = Instant.now()
            display = nextTotpDisplay(field, display, now)
            // Wake just after the next full second so the countdown and the period boundary stay aligned.
            kotlinx.coroutines.delay(1_000L - now.nano / 1_000_000 + 5)
        }
    }
    val current = display
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (current == null) {
            Text(UiText.text("totp.failed"), color = MaterialTheme.colors.error)
        } else {
            Text(current.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.h6)
            Text(UiText.text("totp.remaining", current.remaining), style = MaterialTheme.typography.caption)
            LinearProgressIndicator(progress = (current.remaining.toFloat() / current.period).coerceIn(0f, 1f),
                modifier = Modifier.width(120.dp))
        }
    }
}
