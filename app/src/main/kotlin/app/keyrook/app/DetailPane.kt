// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.keyrook.core.crypto.Secret
import app.keyrook.core.model.*
import app.keyrook.core.security.HealthIssue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val MASK = "••••••••"

private fun Secret.present(): Boolean = runCatching { useChars { it.isNotEmpty() } }.getOrDefault(false)

/** Creates an immutable copy; called only for visible fields and values the user explicitly revealed. */
private fun Secret.plainText(): String = runCatching { useChars { String(it) } }.getOrDefault("")

private fun formatInstant(value: String): String = runCatching {
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(UiText.locale)
        .withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

/**
 * Read-only details of the selected entry. Masked fields and the notes stay masked until "show" is toggled for that
 * value; the shown positions live in [reveal] and are masked again when another entry or saved version is shown and
 * when this view leaves the screen (editor, narrow window, lock). Web logins with a TOTP secret also show the current
 * one-time code under the same rules ([RevealState.TOTP_CODE]). Copy and open use the list's clipboard and browser
 * paths. Trashed entries show their metadata only.
 */
@Composable
internal fun EntryDetailPane(vault: Vault, entry: Entry?, issues: Set<HealthIssue>, reveal: RevealState, busy: Boolean,
                             onReveal: (RevealState) -> Unit, onEdit: (Entry) -> Unit, onFavorite: (Entry) -> Unit,
                             onUsed: (Entry) -> Unit, modifier: Modifier = Modifier) {
    val latestReveal by rememberUpdatedState(onReveal)
    DisposableEffect(Unit) { onDispose { latestReveal(RevealState()) } }
    val key = entry?.let { RevealKey(vault.id, it.id, it.modifiedAt) }
    val current = reveal.follow(key)
    SideEffect { if (current != reveal) onReveal(current) }
    if (entry == null || key == null) {
        Box(modifier.padding(16.dp)) { Text(UiText.text("detail.none")) }
        return
    }
    val today by produceState(LocalDate.now()) {
        while (true) { kotlinx.coroutines.delay(60_000); value = LocalDate.now() }
    }
    val info = remember(entry.id, entry.modifiedAt, vault.id, vault.revision, today, UiText.locale) {
        entryCardInfo(entry, vault, today)
    }
    var notice by remember(entry.id) { mutableStateOf("") }
    val actions = entry.deletedAt == null
    fun copy(label: String, secret: Secret) {
        val copied = runCatching { secret.useChars { SecretClipboard.copy(String(it)) } }.isSuccess
        notice = if (copied) UiText.text("list.copied", label) else UiText.text("list.copyFailed")
        if (copied) onUsed(entry)
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (actions) FavoriteToggle(entry.title, entry.pinned, busy) { onFavorite(entry) }
            Text(entry.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.h5)
        }
        Text(listOfNotNull(entry.data.type().label,
            info.customer?.let { UiText.text("list.customerValue", it) },
            info.project?.let { UiText.text("list.projectValue", it) }).joinToString(" · "))
        if (!actions) Text(UiText.text("detail.inTrash"), color = MaterialTheme.colors.error)
        if (info.expiresOn != null && info.expiry != null) ExpiryBadge(info.expiresOn, info.expiry)
        val markers = passwordMarkers(issues)
        if (markers.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            markers.forEach { WarningChip(healthIssueText(it), severe = severeIssue(it)) }
        }
        val tags = ReservedTags.visible(entry.tags)
        if (tags.isNotEmpty()) Text(UiText.text("detail.tags", tags.joinToString(", ")))
        Text(UiText.text("detail.dates", formatInstant(entry.createdAt), formatInstant(entry.modifiedAt)),
            style = MaterialTheme.typography.caption)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && actions, onClick = { onEdit(entry) }) { Text(UiText.text("shell.edit")) }
        }
        if (notice.isNotEmpty()) Text(notice)
        Divider()
        ConnectionDetails(vault, entry.data, busy || !actions)
        val labels = entry.data.labels()
        entry.data.fields().forEachIndexed { index, field ->
            val label = labels.getOrElse(index) { "" }
            val open: (() -> Unit)? = if (field.kind != FieldKind.URL) null else ({
                val opened = runCatching { EntryQuickActions.open(field) }.isSuccess
                notice = if (opened) "" else UiText.text("list.openFailed")
                if (opened) onUsed(entry)
            })
            DetailValue(label = label, value = field.value, hidden = field.hidden, shown = current.shows(key, index),
                actions = actions, busy = busy, onToggle = { onReveal(current.toggle(key, index)) },
                onCopy = { copy(label, field.value) }, onOpen = open)
        }
        val totp = (entry.data as? EntryData.Web)?.totp
        if (actions && totp != null && totp.value.present()) TotpCodeRow(totp, shown = current.shows(key, RevealState.TOTP_CODE),
            busy = busy, onToggle = { onReveal(current.toggle(key, RevealState.TOTP_CODE)) },
            onNotice = { notice = it; onUsed(entry) })
        val notesLabel = UiText.text("editor.notes")
        DetailValue(label = notesLabel, value = entry.notes, hidden = true, shown = current.shows(key, RevealState.NOTES),
            actions = actions, busy = busy, onToggle = { onReveal(current.toggle(key, RevealState.NOTES)) },
            onCopy = { copy(notesLabel, entry.notes) }, onOpen = null)
        if (entry.history.isNotEmpty()) Text(UiText.text("detail.history", entry.history.size),
            style = MaterialTheme.typography.caption)
    }
}

/** One value: visible fields as text, masked ones as dots unless [shown]. Empty values offer no actions. */
@Composable
private fun DetailValue(label: String, value: Secret, hidden: Boolean, shown: Boolean, actions: Boolean, busy: Boolean,
                        onToggle: () -> Unit, onCopy: () -> Unit, onOpen: (() -> Unit)?) {
    val present = value.present()
    Column {
        Text(label, style = MaterialTheme.typography.caption)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val masked = present && hidden && !(shown && actions)
            val text = when {
                !present -> UiText.text("detail.empty")
                masked -> MASK
                else -> value.plainText()
            }
            Text(text, Modifier.weight(1f).then(if (masked) Modifier.maskedValueSemantics(label) else Modifier),
                fontFamily = if (hidden) FontFamily.Monospace else null)
            if (present && actions) {
                // Buttons name the value they act on; every row has the same visible "Show", "Copy" and "Open".
                if (hidden) TextButton(onClick = onToggle, modifier = Modifier.revealSemantics(label, shown)) {
                    Text(UiText.text(if (shown) "detail.hide" else "detail.show"))
                }
                TextButton(enabled = !busy, onClick = onCopy, modifier = Modifier.describedAs(UiText.text("a11y.copyValue", label))) {
                    Text(UiText.text("common.copy"))
                }
                if (onOpen != null) TextButton(enabled = !busy, onClick = onOpen,
                    modifier = Modifier.describedAs(UiText.text("a11y.openValue", label))) { Text(UiText.text("common.open")) }
            }
        }
    }
}

/** Ports, protocols and assignments, plus the SSH/SFTP command copy of server and transfer records. */
@Composable
private fun ConnectionDetails(vault: Vault, data: EntryData, disabled: Boolean) {
    fun titles(ids: List<String>) = ids.mapNotNull { id -> vault.entries.firstOrNull { it.id == id }?.title }
    fun encryption(value: MailEncryption) =
        if (value == MailEncryption.NONE) UiText.text("editor.encryptionNone") else value.name
    when (data) {
        is EntryData.Transfer -> {
            Text(UiText.text("detail.transfer", data.protocol.name, data.port))
            if (data.protocol == TransferProtocol.SFTP) CommandCopyButton("SFTP", disabled) {
                ConnectionCommands.sftp(data.host.value.plainText(), data.port, data.username.value.plainText())
            }
        }
        is EntryData.Server -> {
            Text(UiText.text("detail.port", data.port))
            CommandCopyButton("SSH", disabled) {
                ConnectionCommands.ssh(data.host.value.plainText(), data.port, data.username.value.plainText())
            }
        }
        is EntryData.Email -> listOfNotNull(data.imap?.let { "IMAP" to it }, data.pop3?.let { "POP3" to it },
            data.smtp?.let { "SMTP" to it }).forEach { (name, endpoint) ->
            Text(UiText.text("detail.endpoint", name, endpoint.port, encryption(endpoint.encryption)))
        }
        is EntryData.Ssh -> {
            Text(UiText.text("detail.keyType", data.keyType.name))
            val servers = titles(data.serverIds)
            if (servers.isNotEmpty()) Text(UiText.text("detail.servers", servers.joinToString(", ")))
        }
        is EntryData.Domain -> data.registrarLoginId?.let { id ->
            titles(listOf(id)).firstOrNull()?.let { Text(UiText.text("detail.registrarLogin", it)) }
        }
        else -> Unit
    }
}
