// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keyrook.core.model.Entry
import app.keyrook.core.model.EntryData
import app.keyrook.core.model.Field
import app.keyrook.core.model.FieldKind
import app.keyrook.core.model.Vault
import app.keyrook.core.security.HealthIssue
import app.keyrook.core.security.VaultHealth
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal enum class ExpiryState { VALID, EXPIRING_SOON, EXPIRED }
internal data class CardValue(val label: String, val value: String)

/** Metadata shown on a list card. Contains only visible field values, never hidden ones. */
internal data class EntryCardInfo(val customer: String?, val project: String?, val username: CardValue?,
                                  val address: CardValue?, val expiresOn: LocalDate?, val expiry: ExpiryState?)

private const val MAX_CARD_CHARS = 256

/** Same window as core [VaultHealth]: past dates are expired, today through the warning window expire soon. */
internal fun expiryState(expiresOn: LocalDate, today: LocalDate): ExpiryState = when {
    expiresOn.isBefore(today) -> ExpiryState.EXPIRED
    !expiresOn.isAfter(today.plusDays(VaultHealth.EXPIRY_WARNING_DAYS)) -> ExpiryState.EXPIRING_SOON
    else -> ExpiryState.VALID
}

/** The field naming where an entry lives: URL, host, mail server or domain. */
internal fun EntryData.addressField(): Field? = when (this) {
    is EntryData.Web -> url
    is EntryData.Panel -> url
    is EntryData.Transfer -> host
    is EntryData.Server -> host
    is EntryData.Email -> listOfNotNull(imap, pop3, smtp).firstOrNull()?.host
    is EntryData.Domain -> name
    is EntryData.Custom -> values.values.firstOrNull { it.kind == FieldKind.URL && !it.hidden }
    is EntryData.Ssh -> null
}

internal fun entryCardInfo(entry: Entry, vault: Vault, today: LocalDate): EntryCardInfo {
    val project = entry.projectId?.let { id -> vault.projects.firstOrNull { it.id == id } }
    val customerId = entry.customerId ?: project?.customerId
    val customer = customerId?.let { id -> vault.customers.firstOrNull { it.id == id }?.name }
    val expires = entry.expiresOn?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return EntryCardInfo(customer, project?.name, entry.data.cardValue(entry.data.quickField(QuickField.USERNAME)),
        entry.data.cardValue(entry.data.addressField()), expires, expires?.let { expiryState(it, today) })
}

/** Hidden fields are skipped whatever they contain; control characters cannot break the card layout. */
private fun EntryData.cardValue(field: Field?): CardValue? {
    if (field == null || field.hidden) return null
    val text = runCatching {
        field.value.useChars { chars -> String(chars, 0, minOf(chars.size, MAX_CARD_CHARS)) }
    }.getOrNull() ?: return null
    val clean = text.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
    return if (clean.isEmpty()) null else CardValue(quickLabel(field), clean)
}

@Composable
internal fun ExpiryBadge(date: LocalDate, state: ExpiryState) {
    val formatted = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(UiText.locale))
    when (state) {
        ExpiryState.VALID -> Text(UiText.text("list.expires", formatted), style = MaterialTheme.typography.body2)
        ExpiryState.EXPIRING_SOON, ExpiryState.EXPIRED -> {
            val expired = state == ExpiryState.EXPIRED
            val colors = MaterialTheme.colors
            Surface(color = if (expired) colors.error else colors.secondary,
                contentColor = if (expired) colors.onError else colors.onSecondary, shape = RoundedCornerShape(4.dp)) {
                Text(UiText.text(if (expired) "list.expired" else "list.expiringSoon", formatted),
                    style = MaterialTheme.typography.body2, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

/**
 * A list row. Selection is shown by border, tint and elevation and exposed to accessibility services. [markers] are
 * password warnings by reason only. [compact] cards, used next to the detail view, put their entry actions below the
 * text instead of beside it.
 */
@Composable
internal fun EntryCardView(entry: Entry, info: EntryCardInfo, isSelected: Boolean, listFocused: Boolean, trash: Boolean,
                           busy: Boolean, onClick: () -> Unit, onFocusInside: () -> Unit, onQuick: (QuickField) -> Unit,
                           onEdit: () -> Unit, onDuplicate: () -> Unit, onRestore: () -> Unit, onPurge: () -> Unit,
                           onTrash: () -> Unit, markers: List<HealthIssue> = emptyList(), compact: Boolean = false) {
    val colors = MaterialTheme.colors
    val latestClick by rememberUpdatedState(onClick)
    Card(
        Modifier.fillMaxWidth()
            .semantics { selected = isSelected }
            .onFocusChanged { if (it.hasFocus) onFocusInside() }
            .pointerInput(entry.id) { detectTapGestures { latestClick() } },
        backgroundColor = if (isSelected) colors.primary.copy(alpha = 0.08f).compositeOver(colors.surface) else colors.surface,
        border = if (isSelected) BorderStroke(if (listFocused) 3.dp else 2.dp, colors.primary) else null,
        elevation = if (isSelected) 4.dp else 2.dp,
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.title, style = MaterialTheme.typography.h6)
                Text(listOfNotNull(entry.data.type().label,
                    info.customer?.let { UiText.text("list.customerValue", it) },
                    info.project?.let { UiText.text("list.projectValue", it) }).joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = listOfNotNull(info.username, info.address)
                if (details.isNotEmpty()) Text(details.joinToString(" · ") { "${it.label}: ${it.value}" },
                    style = MaterialTheme.typography.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (info.expiresOn != null && info.expiry != null) ExpiryBadge(info.expiresOn, info.expiry)
                if (markers.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    markers.forEach { WarningChip(healthIssueText(it), severe = false) }
                }
                if (entry.tags.isNotEmpty()) Text(entry.tags.joinToString(", "), style = MaterialTheme.typography.body2)
                if (!trash) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    QuickField.entries.forEach { kind ->
                        val field = entry.data.quickField(kind)
                        val present = remember(field) { field != null && EntryQuickActions.available(field, kind) }
                        if (field != null && present) {
                            val label = entry.data.quickLabel(field)
                            TextButton(enabled = !busy, onClick = { onQuick(kind) }) {
                                Text(when (kind) {
                                    QuickField.URL -> UiText.text("list.openField", label)
                                    QuickField.TOTP -> UiText.text("list.copyTotp")
                                    else -> UiText.text("list.copyField", label)
                                })
                            }
                        }
                    }
                }
                if (compact) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    EntryCardActions(trash, busy, onEdit, onDuplicate, onRestore, onPurge, onTrash)
                }
            }
            if (!compact) EntryCardActions(trash, busy, onEdit, onDuplicate, onRestore, onPurge, onTrash)
        }
    }
}

@Composable
private fun EntryCardActions(trash: Boolean, busy: Boolean, onEdit: () -> Unit, onDuplicate: () -> Unit,
                             onRestore: () -> Unit, onPurge: () -> Unit, onTrash: () -> Unit) {
    if (!trash) TextButton(enabled = !busy, onClick = onEdit) { Text(UiText.text("shell.edit")) }
    if (!trash) TextButton(enabled = !busy, onClick = onDuplicate) { Text(UiText.text("shell.duplicate")) }
    if (trash) {
        TextButton(enabled = !busy, onClick = onRestore) { Text(UiText.text("shell.restore")) }
        TextButton(enabled = !busy, onClick = onPurge) { Text(UiText.text("list.purge"), color = MaterialTheme.colors.error) }
    } else TextButton(enabled = !busy, onClick = onTrash) { Text(UiText.text("list.moveToTrash")) }
}
