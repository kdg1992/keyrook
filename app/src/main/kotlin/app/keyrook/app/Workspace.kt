// SPDX-FileCopyrightText: 2026 Kim Daniel Geisthardt
// SPDX-License-Identifier: GPL-3.0-or-later
package app.keyrook.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.keyrook.core.model.*
import app.keyrook.core.security.HealthIssue
import javax.swing.SwingUtilities

/** The unlocked vault: toolbar, organizer and the entry list, next to the entry details when the window is wide. */
@Composable
internal fun Workspace(state: AppState, current: Vault, settings: SettingsStore, shortcuts: ShortcutActions, mac: Boolean,
                       warningsByEntry: Map<String, Set<HealthIssue>>) {
    val controller = state.controller
    val dialogs = state.dialogs
    val live = state.live
    val busy by state::busy
    var message by state::message
    var editing by state::editing
    var creating by state::creating
    var listView by state::listView
    var selection by state::selection
    var reveal by state::reveal
    var organizer by state::organizer
    fun operation(action: () -> Vault?) = state.operation(action)
    AppToolbar(dataActions(controller, settings, dialogs, ::operation, settingsFailed = {
        SwingUtilities.invokeLater { if (live.get()) message = UiText.text("settings.saveFailed") }
    }), busy, organizer, onOrganizer = { organizer = !organizer })
    if (organizer) OrganizationTools(current, controller, busy, ::operation, onClose = { organizer = false })
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val entryList: @Composable (Boolean) -> Unit = { compact ->
            VaultList(current, controller, busy, shortcuts, mac, listView, { listView = it }, selection,
                { selection = it }, warningsByEntry, compact, state.recent.of(current.id), onUsed = state::used,
                onCreate = { creating = true },
                onEdit = { state.used(it.id); editing = it },
                onDuplicate = { entry -> operation { controller.duplicate(entry.id) } },
                onTrash = { id -> operation { controller.trash(id, false) } },
                onRestore = { id -> operation { controller.trash(id, true) } },
                onPurge = { id -> operation { controller.purge(setOf(id)) } },
                onEmptyTrash = { operation { controller.emptyTrash() } },
                onBulkTrash = { ids, restore -> operation { controller.trashAll(ids, restore) } },
                onBulkTag = { ids, tag, add -> operation { controller.tagAll(ids, tag, add) } },
                onFavorite = { ids, favorite -> operation { controller.setFavorite(ids, favorite) } })
        }
        if (workspaceLayout(maxWidth.value) == WorkspaceLayout.LIST_DETAIL) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(0.45f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    entryList(true)
                }
                Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colors.onSurface.copy(alpha = 0.12f)))
                val selected = current.entries.firstOrNull { it.id == selection.selectedId }
                EntryDetailPane(current, selected, selected?.let { warningsByEntry[it.id] }.orEmpty(), reveal, busy,
                    onReveal = { reveal = it }, onEdit = { state.used(it.id); editing = it },
                    onUsed = { state.used(it.id) },
                    onFavorite = { entry -> operation { controller.setFavorite(setOf(entry.id), !entry.pinned) } },
                    modifier = Modifier.weight(0.55f).fillMaxHeight())
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { entryList(false) }
    }
}

/**
 * Search, filters and entry cards. [view] and [selectionState] are owned by the caller so they survive the editor.
 * [compact] arranges the controls for the narrower list pane next to the entry details.
 */
@Composable
private fun VaultList(vault: Vault, controller: VaultController, busy: Boolean, shortcuts: ShortcutActions, mac: Boolean,
                      view: ListView, onView: (ListView) -> Unit, selectionState: EntrySelection,
                      onSelection: (EntrySelection) -> Unit, issues: Map<String, Set<HealthIssue>>, compact: Boolean,
                      recent: List<String>, onUsed: (String) -> Unit, onCreate: () -> Unit, onEdit: (Entry) -> Unit,
                      onDuplicate: (Entry) -> Unit, onTrash: (String) -> Unit, onRestore: (String) -> Unit,
                      onPurge: (String) -> Unit, onEmptyTrash: () -> Unit,
                      onBulkTrash: (Set<String>, Boolean) -> Unit, onBulkTag: (Set<String>, String, Boolean) -> Unit,
                      onFavorite: (Set<String>, Boolean) -> Unit) {
    val search = view.search
    val includeHidden = view.includeHidden
    val activeFilters = view.filters.normalized(vault)
    val trash = activeFilters.trash
    SideEffect { if (view.filters != activeFilters) onView(view.copy(filters = activeFilters)) }
    fun applyFilters(next: EntryListFilters) = onView(view.copy(filters = next))
    val matches = searchResults(vault, controller, search, includeHidden)
    val searchFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    var listFocused by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<ListConfirmation?>(null) }
    var notice by remember { mutableStateOf("") }
    // Open bulk tag dialog: true adds a tag to the marked entries, false removes one.
    var bulkTag by remember { mutableStateOf<Boolean?>(null) }
    val trashCount = vault.entries.count { it.deletedAt != null }
    val latestNew by rememberUpdatedState({ if (!busy && !trash && confirmation == null) onCreate() })
    DisposableEffect(shortcuts) {
        shortcuts.newEntry = { latestNew() }
        shortcuts.search = { searchFocus.requestFocus() }
        onDispose { shortcuts.newEntry = null; shortcuts.search = null }
    }
    // The list takes the focus when shown and again after its dialogs close, so arrow keys work immediately.
    val listIdle = confirmation == null && !help && bulkTag == null
    LaunchedEffect(listIdle) { if (listIdle) runCatching { listFocus.requestFocus() } }
    val today by produceState(java.time.LocalDate.now()) {
        while (true) { kotlinx.coroutines.delay(60_000); value = java.time.LocalDate.now() }
    }
    val entries = activeFilters.select(vault, matches.orEmpty(), today, recent)
    val selection = selectionState.update(view.query(activeFilters), matches?.let { entries.map { it.id } })
    SideEffect { if (selectionState != selection) onSelection(selection) }
    val selectedEntry = entries.firstOrNull { it.id == selection.selectedId }
    fun quickAction(entry: Entry, kind: QuickField) {
        if (entry.data.quickField(kind) != null) onUsed(entry.id)
        if (kind == QuickField.TOTP) { notice = EntryQuickActions.copyTotpNotice(entry.data); return }
        val field = entry.data.quickField(kind)?.takeIf { EntryQuickActions.available(it) }
        notice = when {
            field == null -> UiText.text("list.noQuickField")
            kind == QuickField.URL ->
                if (runCatching { EntryQuickActions.open(field) }.isSuccess) "" else UiText.text("list.openFailed")
            runCatching { EntryQuickActions.copy(field) }.isSuccess -> UiText.text("list.copied", entry.data.quickLabel(field))
            else -> UiText.text("list.copyFailed")
        }
    }
    fun shortcutContext(focus: ShortcutFocus) = ShortcutContext(unlocked = true, busy = busy, editing = false,
        modal = confirmation != null || help || bulkTag != null, focus = focus, selection = selectedEntry != null, trash = trash)
    fun handleListKey(event: KeyEvent): Boolean {
        val action = keyboardShortcut(event, mac, shortcutContext(ShortcutFocus.LIST)) ?: return false
        when (action) {
            ShortcutAction.SELECT_PREVIOUS -> onSelection(selection.previous())
            ShortcutAction.SELECT_NEXT -> onSelection(selection.next())
            ShortcutAction.SELECT_FIRST -> onSelection(selection.first())
            ShortcutAction.SELECT_LAST -> onSelection(selection.last())
            ShortcutAction.MARK_ALL -> onSelection(selection.markAll())
            // Delete acts on the marked entries when there are any, otherwise on the selected one.
            ShortcutAction.TRASH_ENTRY -> {
                val entry = selectedEntry
                confirmation = when {
                    selection.marked.isNotEmpty() -> ListConfirmation.TrashMarked(selection.markedIds.toSet())
                    entry != null -> ListConfirmation.Trash(entry.id, entry.title)
                    else -> return false
                }
            }
            else -> {
                val entry = selectedEntry ?: return false
                when (action) {
                    ShortcutAction.COPY_PASSWORD -> quickAction(entry, QuickField.SECRET)
                    ShortcutAction.COPY_USERNAME -> quickAction(entry, QuickField.USERNAME)
                    ShortcutAction.COPY_TOTP -> quickAction(entry, QuickField.TOTP)
                    ShortcutAction.OPEN_URL -> quickAction(entry, QuickField.URL)
                    ShortcutAction.EDIT_ENTRY -> onEdit(entry)
                    ShortcutAction.TOGGLE_MARK -> onSelection(selection.toggleMark(entry.id))
                    else -> return false
                }
            }
        }
        return true
    }
    val searchField: @Composable (Modifier) -> Unit = { modifier ->
        OutlinedTextField(search, { if (it.length <= 256) onView(view.copy(search = it)) }, label = { Text(UiText.text("shell.search")) },
            modifier = modifier.focusRequester(searchFocus).onPreviewKeyEvent { event ->
                if (keyboardShortcut(event, mac, shortcutContext(ShortcutFocus.SEARCH)) != ShortcutAction.FOCUS_LIST) false
                else { runCatching { listFocus.requestFocus() }.isSuccess }
            }, singleLine = true)
    }
    val listButtons: @Composable () -> Unit = {
        Button(onClick = onCreate, enabled = !busy && !trash) { Text(UiText.text("shell.newEntry")) }
        TextButton(onClick = { applyFilters(activeFilters.copy(trash = !trash)) }, enabled = !busy) { Text(if (trash) UiText.text("shell.active") else UiText.text("shell.trash")) }
        if (trash) TextButton(enabled = !busy && trashCount > 0, onClick = { confirmation = ListConfirmation.EmptyTrash(trashCount) }) {
            Text(UiText.text("list.emptyTrash"))
        }
        TextButton(onClick = { help = true }) { Text(UiText.text("shortcuts.title")) }
    }
    if (compact) {
        searchField(Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) { listButtons() }
    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        searchField(Modifier.weight(1f))
        listButtons()
    }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        LabeledCheckbox(includeHidden, UiText.text("shell.hiddenSearch")) { onView(view.copy(includeHidden = it)) }
        LabeledCheckbox(activeFilters.favorites, UiText.text("filters.favorites")) { applyFilters(activeFilters.copy(favorites = it)) }
        LabeledCheckbox(activeFilters.recent, UiText.text("filters.recent")) { applyFilters(activeFilters.copy(recent = it)) }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(UiText.text("shell.type"), activeFilters.type?.name, EntryType.entries.map { it.name to it.label }) {
            applyFilters(activeFilters.copy(type = it?.let(EntryType::valueOf)))
        }
        Choice(UiText.text("shell.customer"), activeFilters.customerId, vault.customers.map { it.id to it.name }) {
            applyFilters(activeFilters.copy(customerId = it).normalized(vault))
        }
        Choice(UiText.text("shell.project"), activeFilters.projectId, activeFilters.projects(vault).map { it.id to it.name }) {
            applyFilters(activeFilters.copy(projectId = it))
        }
        Choice(UiText.text("shell.tag"), activeFilters.tag,
            vault.entries.flatMap { ReservedTags.visible(it.tags) }.distinct().sorted().map { it to it }) {
            applyFilters(activeFilters.copy(tag = it))
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice(UiText.text("shell.expiry"), activeFilters.expiry.name, ExpiryFilter.entries.map { it.name to it.label }, nullable = false) {
            applyFilters(activeFilters.copy(expiry = ExpiryFilter.valueOf(requireNotNull(it))))
        }
        Choice(UiText.text("shell.sort"), activeFilters.sort.name, EntrySort.entries.map { it.name to it.label }, nullable = false) {
            applyFilters(activeFilters.copy(sort = EntrySort.valueOf(requireNotNull(it))))
        }
        TextButton(onClick = { onView(ListView()) }) { Text(UiText.text("shell.reset")) }
    }
    if (entries.isNotEmpty()) BulkBar(selection, entries, trash, busy, onSelection,
        onTrash = { confirmation = ListConfirmation.TrashMarked(selection.markedIds.toSet()) },
        onRestore = { onBulkTrash(selection.markedIds.toSet(), true) }, onTag = { bulkTag = it },
        onFavorite = { onFavorite(selection.markedIds.toSet(), it) })
    if (notice.isNotEmpty()) Text(notice)
    if (matches == null) Text(UiText.text("shell.searching"))
    else if (entries.isEmpty()) Text(UiText.text("shell.noEntries"))
    val listState = rememberLazyListState()
    val selectedIndex = entries.indexOfFirst { it.id == selection.selectedId }
    LaunchedEffect(selectedIndex, selection.selectedId) {
        if (selectedIndex < 0) return@LaunchedEffect
        val layout = listState.layoutInfo
        val shown = layout.visibleItemsInfo.firstOrNull { it.index == selectedIndex }
        if (shown == null || shown.offset < layout.viewportStartOffset || shown.offset + shown.size > layout.viewportEndOffset) {
            listState.scrollToItem(selectedIndex)
        }
    }
    LazyColumn(Modifier.focusRequester(listFocus).onFocusChanged { listFocused = it.hasFocus }
        .onKeyEvent { handleListKey(it) }.focusable(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entries, key = { it.id }) { entry ->
            val info = remember(entry.id, entry.modifiedAt, vault.id, vault.revision, today, UiText.locale) {
                entryCardInfo(entry, vault, today)
            }
            EntryCardView(entry, info, isSelected = entry.id == selection.selectedId, listFocused = listFocused,
                trash = trash, busy = busy,
                onClick = { onSelection(selection.select(entry.id)); runCatching { listFocus.requestFocus() } },
                onFocusInside = { onSelection(selection.select(entry.id)) },
                onQuick = { kind -> quickAction(entry, kind) },
                onEdit = { onEdit(entry) }, onDuplicate = { onDuplicate(entry) }, onRestore = { onRestore(entry.id) },
                onPurge = { confirmation = ListConfirmation.Purge(entry.id, entry.title) },
                onTrash = { confirmation = ListConfirmation.Trash(entry.id, entry.title) },
                markers = passwordMarkers(issues[entry.id].orEmpty()), compact = compact,
                marked = entry.id in selection.marked, onMark = { onSelection(selection.toggleMark(entry.id)) },
                onFavorite = if (trash) null else ({ onFavorite(setOf(entry.id), !entry.pinned) }))
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text(UiText.text("shortcuts.title")) },
        text = { ShortcutHelpTable(mac) },
        confirmButton = { TextButton(onClick = { help = false }) { Text(UiText.text("shell.close")) } })
    fun dismiss() { confirmation = null }
    when (val pending = confirmation) {
        is ListConfirmation.Trash -> ConfirmationDialog(UiText.text("list.trashTitle"), UiText.text("list.trashBody", pending.title),
            UiText.text("list.trashConfirm"), busy, irreversible = false,
            onConfirm = { dismiss(); onTrash(pending.id) }, onDismiss = ::dismiss)
        is ListConfirmation.TrashMarked -> ConfirmationDialog(UiText.text("list.trashTitle"),
            UiText.text("bulk.trashBody", pending.ids.size), UiText.text("list.trashConfirm"), busy, irreversible = false,
            onConfirm = { dismiss(); onBulkTrash(pending.ids, false) }, onDismiss = ::dismiss)
        is ListConfirmation.Purge -> ConfirmationDialog(UiText.text("list.purgeTitle"), UiText.text("list.purgeBody", pending.title),
            UiText.text("list.purgeConfirm"), busy, irreversible = true,
            onConfirm = { dismiss(); onPurge(pending.id) }, onDismiss = ::dismiss)
        is ListConfirmation.EmptyTrash -> ConfirmationDialog(UiText.text("list.emptyTrashTitle"),
            UiText.text("list.emptyTrashBody", pending.count), UiText.text("list.emptyTrashConfirm"), busy, irreversible = true,
            onConfirm = { dismiss(); onEmptyTrash() }, onDismiss = ::dismiss)
        null -> Unit
    }
    bulkTag?.let { add ->
        val ids = selection.markedIds.toSet()
        BulkTagDialog(add, vault.entries.filter { it.id in ids }, busy, onDismiss = { bulkTag = null }) { tag ->
            bulkTag = null
            onBulkTag(ids, tag, add)
        }
    }
}

/** Count of marked entries and the actions for them; shown above the list while it has entries. */
@Composable
private fun BulkBar(selection: EntrySelection, entries: List<Entry>, trash: Boolean, busy: Boolean,
                    onSelection: (EntrySelection) -> Unit, onTrash: () -> Unit, onRestore: () -> Unit,
                    onTag: (Boolean) -> Unit, onFavorite: (Boolean) -> Unit) {
    val count = selection.markedIds.size
    val anyTag = entries.any { it.id in selection.marked && ReservedTags.visible(it.tags).isNotEmpty() }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        TextButton(onClick = { onSelection(selection.markAll()) }) {
            Text(UiText.text(if (count == entries.size) "bulk.clear" else "bulk.selectAll"))
        }
        if (count > 0) {
            Text(UiText.text("bulk.count", count), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (trash) TextButton(enabled = !busy, onClick = onRestore) { Text(UiText.text("bulk.restore")) }
            else TextButton(enabled = !busy, onClick = onTrash) { Text(UiText.text("bulk.trash")) }
            TextButton(enabled = !busy, onClick = { onTag(true) }) { Text(UiText.text("bulk.addTag")) }
            TextButton(enabled = !busy && anyTag, onClick = { onTag(false) }) { Text(UiText.text("bulk.removeTag")) }
            if (!trash) {
                TextButton(enabled = !busy, onClick = { onFavorite(true) }) { Text(UiText.text("bulk.favorite")) }
                TextButton(enabled = !busy, onClick = { onFavorite(false) }) { Text(UiText.text("bulk.unfavorite")) }
            }
            if (count < entries.size) TextButton(onClick = { onSelection(selection.clearMarks()) }) { Text(UiText.text("bulk.clear")) }
        }
    }
}

/** Asks for the tag to add, or offers the tags of the marked [entries] to remove. */
@Composable
private fun BulkTagDialog(add: Boolean, entries: List<Entry>, busy: Boolean, onDismiss: () -> Unit,
                          onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<String?>(null) }
    val tags = entries.flatMap { ReservedTags.visible(it.tags) }.distinct().sorted()
    val tag = if (add) text.trim() else chosen.orEmpty()
    val error = bulkTagError(tag)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(UiText.text(if (add) "bulk.addTagTitle" else "bulk.removeTagTitle", entries.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (add) {
                    OutlinedTextField(text, { if (it.length <= MAX_TAG_CHARS + 16) text = it }, enabled = !busy,
                        label = { Text(UiText.text("shell.tag")) }, singleLine = true,
                        isError = text.isNotEmpty() && error != null)
                    if (text.isNotEmpty()) FieldError(error)
                } else Choice(UiText.text("shell.tag"), chosen, tags.map { it to it }, !busy) { chosen = it }
            }
        },
        confirmButton = {
            Button(enabled = !busy && error == null, onClick = { onConfirm(tag) }) {
                Text(UiText.text(if (add) "bulk.addTag" else "bulk.removeTag"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(UiText.text("common.cancel")) } },
    )
}
