// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.ScreenHeader
import dev.stevenjin.stevenpiano.ui.components.readingPadding
import dev.stevenjin.stevenpiano.ui.components.readingWidth
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

/**
 * The Library tab: search, the category chips, then text-only rows. Tapping a piece plays it
 * (with the list it was in as the queue) and switches to Now playing. [onImport] brings files in.
 * On wide screens the content stays a 720 dp column in the middle; the list still scrolls from
 * anywhere across the screen.
 */
@Composable
fun LibraryScreen(onPlay: (pieceId: Long, queue: List<Long>) -> Unit, onImport: (ImportSource) -> Unit) {
    val graph = LocalContext.current.graph
    val vm = viewModel { LibraryViewModel(graph.library, graph.importProgress, graph.appScope) }
    val state by vm.state.collectAsStateWithLifecycle()
    val importProgress by vm.importProgress.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    val pickers = rememberImportPickers(onImport)

    BackHandler(enabled = state.group != null, onBack = vm::closeGroup)

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Column(Modifier.readingWidth()) {
            ScreenHeader("Library") {
                GlyphButton(R.drawable.ic_add, "Add MIDI files") { adding = true }
            }
            HairlineDivider()
            ImportBar(importProgress, vm.dismissedImport, vm::dismissImport)
        }
        when {
            !state.loaded -> Unit
            state.empty -> EmptyLibrary(onAdd = { adding = true })
            else -> LibraryList(state, vm, listState, onPlay) { dialog = it }
        }
    }
    if (adding) AddSheet(pickers) { adding = false }
    dialog?.let { LibraryDialogs(it, vm) { dialog = null } }
}

@Composable
private fun LibraryList(
    state: LibraryState,
    vm: LibraryViewModel,
    listState: LazyListState,
    onPlay: (Long, List<Long>) -> Unit,
    onDialog: (LibraryDialog) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        LibraryItems(state, vm, listState, readingPadding(maxWidth), onPlay, onDialog)
    }
}

@Composable
private fun LibraryItems(
    state: LibraryState,
    vm: LibraryViewModel,
    listState: LazyListState,
    padding: PaddingValues,
    onPlay: (Long, List<Long>) -> Unit,
    onDialog: (LibraryDialog) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
        item(key = "search") { SearchField(vm.query, vm::search) }
        item(key = "categories") { CategoryChips(state.category, vm::selectCategory) }
        state.group?.let { group ->
            item(key = "group") { GroupHeader(group, (state.listing as? Listing.Pieces)?.pieces?.size ?: 0, vm::closeGroup) }
        }
        when (val listing = state.listing) {
            is Listing.Pieces -> items(listing.pieces, key = { "p${it.id}" }) { piece ->
                PieceRow(
                    piece,
                    onPlay = { onPlay(piece.id, listing.pieces.map { it.id }) },
                    onFavorite = { vm.setFavorite(piece, it) },
                    onDialog = onDialog,
                )
            }
            is Listing.Collections -> items(listing.collections, key = { "c${it.id}" }) { collection ->
                CollectionRow(collection, onOpen = { vm.openGroup(Group.Collection(collection.id, collection.name)) }, onDialog = onDialog)
            }
            is Listing.Composers -> items(listing.composers, key = { "k${it.composerKey}" }) { composer ->
                ComposerRow(composer) { vm.openGroup(Group.Composer(composer.composerKey, composerName(composer))) }
            }
        }
        if (state.listing.isEmpty) item(key = "none") { EmptyListing(state, vm.query.trim()) }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text("Search titles and composers") },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = if (query.isEmpty()) null else {
            { GlyphButton(R.drawable.ic_close, "Clear search") { onQuery("") } }
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = MaterialTheme.shapes.small,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(selected: Category, onSelect: (Category) -> Unit) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .semantics { selectableGroup() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Category.entries.forEach { category ->
            val isSelected = category == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(category) },
                label = { Text(category.label) },
                leadingIcon = if (isSelected) {
                    { Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
            )
        }
    }
}

/** Inside a collection or a composer: back, the name, how many pieces. */
@Composable
private fun GroupHeader(group: Group, pieceCount: Int, onBack: () -> Unit) {
    Column {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(R.drawable.ic_back, if (group is Group.Collection) "Back to collections" else "Back to composers", onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Column {
                Text(
                    group.name,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Eyebrow(Format.count(pieceCount, "piece", "pieces"))
            }
        }
        HairlineDivider()
    }
}

@Composable
private fun EmptyLibrary(onAdd: () -> Unit) {
    EmptyMessage("No pieces yet.", "Add a MIDI file to begin.") {
        OutlinedButton(onClick = onAdd, border = BorderStroke(Hairline, LocalTertiary.current)) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add MIDI files")
        }
    }
}

@Composable
private fun EmptyListing(state: LibraryState, query: String) {
    val (title, body) = when {
        query.isNotEmpty() -> "Nothing matches “$query”." to "Try part of a title or a composer's name."
        state.group is Group.Collection -> "This collection is empty." to "Long-press a piece to add it here."
        state.category == Category.Favorites -> "No favorites yet." to "Long-press a piece to make it a favorite."
        state.category == Category.Collections -> "No collections yet." to "Long-press a piece to add it to a collection."
        else -> "Nothing here yet." to "Add a MIDI file to begin."
    }
    EmptyMessage(title, body)
}

@Composable
private fun EmptyMessage(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}
