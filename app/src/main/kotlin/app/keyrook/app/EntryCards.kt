// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keyrook.core.model.Entry
import app.keyrook.core.model.EntryData
import app.keyrook.core.model.Field
import app.keyrook.core.model.FieldKind
import app.keyrook.core.model.ReservedTags
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
    val colors = MaterialTheme.colors
    when (state) {
        ExpiryState.VALID -> Badge(UiText.text("list.expires", formatted),
            colors.onSurface.copy(alpha = 0.08f).compositeOver(colors.surface), colors.onSurface)
        ExpiryState.EXPIRING_SOON, ExpiryState.EXPIRED -> {
            val expired = state == ExpiryState.EXPIRED
            Badge(UiText.text(if (expired) "list.expired" else "list.expiringSoon", formatted),
                if (expired) colors.error else colors.secondary, if (expired) colors.onError else colors.onSecondary)
        }
    }
}

/** A small rounded label, used for expiry dates and tags. */
@Composable
internal fun Badge(text: String, background: Color, content: Color, border: Color? = null) {
    Surface(color = background, contentColor = content, shape = RoundedCornerShape(50), border = border?.let { BorderStroke(1.dp, it) }) {
        Text(text, style = MaterialTheme.typography.caption, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun TagBadge(tag: String) {
    val colors = MaterialTheme.colors
    Badge("#$tag", colors.surface, colors.primary, border = colors.primary.copy(alpha = 0.5f))
}

/**
 * A list row: type, title and metadata, the quick copy/open actions, and the remaining actions in a menu. Selection is
 * shown by border and tint and exposed to accessibility services. [markers] are password warnings by reason only.
 * [compact] rows, used next to the detail view, keep only the menu at the side. With [onMark], a checkbox shows and
 * toggles whether the entry is [marked] for a bulk action. With [onFavorite], a star shows and toggles the favorite
 * mark; reserved tags are never listed as tags. With [onSaveTemplate], a menu item saves the entry's layout as a template.
 */
@Composable
internal fun EntryCardView(entry: Entry, info: EntryCardInfo, isSelected: Boolean, listFocused: Boolean, trash: Boolean,
                           busy: Boolean, onClick: () -> Unit, onFocusInside: () -> Unit, onQuick: (QuickField) -> Unit,
                           onEdit: () -> Unit, onDuplicate: () -> Unit, onRestore: () -> Unit, onPurge: () -> Unit,
                           onTrash: () -> Unit, markers: List<HealthIssue> = emptyList(), compact: Boolean = false,
                           marked: Boolean = false, onMark: (() -> Unit)? = null, onFavorite: (() -> Unit)? = null,
                           onSaveTemplate: (() -> Unit)? = null) {
    val colors = MaterialTheme.colors
    val latestClick by rememberUpdatedState(onClick)
    val type = entry.data.type()
    Surface(
        Modifier.fillMaxWidth()
            .semantics { selected = isSelected }
            .onFocusChanged { if (it.hasFocus) onFocusInside() }
            .pointerInput(entry.id) { detectTapGestures { latestClick() } },
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) colors.primary.copy(alpha = 0.08f).compositeOver(colors.surface) else colors.surface,
        border = if (isSelected) BorderStroke(if (listFocused) 3.dp else 2.dp, colors.primary)
            else BorderStroke(1.dp, LocalChrome.current.outline),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onMark != null) Checkbox(marked, onCheckedChange = { onMark() }, colors = brandCheckboxColors(),
                modifier = Modifier.describedAs(UiText.text("a11y.markEntry", entry.title)))
            TypeAvatar(type)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.subtitle1,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (onFavorite != null) FavoriteToggle(entry.title, entry.pinned, busy, onFavorite)
                }
                HintText(listOfNotNull(type.label,
                    info.customer?.let { UiText.text("list.customerValue", it) },
                    info.project?.let { UiText.text("list.projectValue", it) }).joinToString(" · "))
                val details = listOfNotNull(info.username, info.address)
                if (details.isNotEmpty()) Text(details.joinToString(" · ") { "${it.label}: ${it.value}" },
                    style = MaterialTheme.typography.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val tags = ReservedTags.visible(entry.tags)
                val expiry = info.expiresOn != null && info.expiry != null
                if (expiry || markers.isNotEmpty() || tags.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (info.expiresOn != null && info.expiry != null) ExpiryBadge(info.expiresOn, info.expiry)
                        markers.forEach { WarningChip(healthIssueText(it), severe = severeIssue(it)) }
                        tags.forEach { TagBadge(it) }
                    }
                }
                if (!trash) FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    QuickField.entries.forEach { kind ->
                        val field = entry.data.quickField(kind)
                        val present = remember(field) { field != null && EntryQuickActions.available(field, kind) }
                        if (field != null && present) {
                            val label = entry.data.quickLabel(field)
                            TextButton(enabled = !busy, onClick = { onQuick(kind) }, contentPadding = CompactButtonPadding,
                                modifier = Modifier.heightIn(min = 30.dp)) {
                                Text(when (kind) {
                                    QuickField.URL -> UiText.text("list.openField", label)
                                    QuickField.TOTP -> UiText.text("list.copyTotp")
                                    else -> UiText.text("list.copyField", label)
                                }, style = MaterialTheme.typography.body2, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!compact) {
                    if (trash) TextButton(enabled = !busy, onClick = onRestore) { Text(UiText.text("shell.restore")) }
                    else TextButton(enabled = !busy, onClick = onEdit) { Text(UiText.text("shell.edit")) }
                }
                EntryMenu(entry.title, trash, busy, compact, onEdit, onDuplicate, onRestore, onPurge, onTrash, onSaveTemplate)
            }
        }
    }
}

/**
 * A star that shows whether the entry is a favorite and toggles it; its accessible name states the action for the entry
 * [title], and its state says whether the entry currently is a favorite.
 */
@Composable
internal fun FavoriteToggle(title: String, favorite: Boolean, busy: Boolean, onToggle: () -> Unit) {
    val label = UiText.text("a11y.fieldOption", title, UiText.text(if (favorite) "favorite.remove" else "favorite.add"))
    val state = UiText.text(if (favorite) "a11y.favorite" else "a11y.notFavorite")
    val colors = MaterialTheme.colors
    TextButton(enabled = !busy, onClick = onToggle, contentPadding = PaddingValues(horizontal = 6.dp),
        modifier = Modifier.defaultMinSize(minWidth = 32.dp, minHeight = 32.dp).semantics { contentDescription = label; stateDescription = state }) {
        Text(if (favorite) "★" else "☆", style = MaterialTheme.typography.h6,
            color = if (favorite) colors.secondary else colors.onSurface.copy(alpha = HINT_TEXT_ALPHA))
    }
}

/** The actions of one row that are not shown on it; [compact] rows also list the main action here. */
@Composable
private fun EntryMenu(title: String, trash: Boolean, busy: Boolean, compact: Boolean, onEdit: () -> Unit, onDuplicate: () -> Unit,
                      onRestore: () -> Unit, onPurge: () -> Unit, onTrash: () -> Unit, onSaveTemplate: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(enabled = !busy, onClick = { open = true }, modifier = Modifier.describedAs(UiText.text("list.moreActions", title))) {
            Icon(KeyrookIcons.More, contentDescription = null)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            @Composable
            fun item(label: String, danger: Boolean = false, action: () -> Unit) {
                DropdownMenuItem(enabled = !busy, onClick = { open = false; action() }) {
                    Text(label, color = if (danger) MaterialTheme.colors.error else Color.Unspecified)
                }
            }
            if (trash) {
                if (compact) item(UiText.text("shell.restore"), action = onRestore)
                item(UiText.text("list.purge"), danger = true, action = onPurge)
            } else {
                if (compact) item(UiText.text("shell.edit"), action = onEdit)
                item(UiText.text("shell.duplicate"), action = onDuplicate)
                if (onSaveTemplate != null) item(UiText.text("template.save"), action = onSaveTemplate)
                Divider()
                item(UiText.text("list.moveToTrash"), danger = true, action = onTrash)
            }
        }
    }
}
