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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.LaunchedEffect
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
import dev.stevenjin.stevenpiano.data.db.PlaylistSummary
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.LocalAppFrame
import dev.stevenjin.stevenpiano.ui.PlaybackStarter
import dev.stevenjin.stevenpiano.ui.components.DragHandle
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.MonogramTile
import dev.stevenjin.stevenpiano.ui.components.ScreenHeader
import dev.stevenjin.stevenpiano.ui.components.moved
import dev.stevenjin.stevenpiano.ui.components.readingPadding
import dev.stevenjin.stevenpiano.ui.components.readingWidth
import dev.stevenjin.stevenpiano.ui.components.rememberDragReorderState
import dev.stevenjin.stevenpiano.ui.components.reorderable
import dev.stevenjin.stevenpiano.ui.components.reorderedBy
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

/**
 * The Library tab: search, the category chips, then text rows, or grids of tiles for Playlists
 * and Composers. Tapping a piece plays it (with the list it was in as the queue) and [onPlaying]
 * shows Now playing. A playlist opens as a page (its cover, Play and Shuffle) whose rows reorder
 * by their drag handles while no search narrows them. Row and tile menus act through [playback]
 * and the view model. [onImport] brings files in. On wide screens the content stays a 720 dp
 * column in the middle; the list still scrolls from anywhere across the screen.
 */
@Composable
fun LibraryScreen(playback: PlaybackStarter, onPlaying: () -> Unit, onImport: (ImportSource) -> Unit) {
    val graph = LocalContext.current.graph
    val vm = viewModel { LibraryViewModel(graph.library, graph.importProgress, graph.appScope) }
    val state by vm.state.collectAsStateWithLifecycle()
    val importProgress by vm.importProgress.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    val pickers = rememberImportPickers(onImport)
    val actions = remember(vm, playback) {
        PieceActions(
            playNext = { playback.playNext(listOf(it.id)) },
            addToQueue = { playback.addToQueue(listOf(it.id)) },
            addToPlaylist = { dialog = LibraryDialog.AddToPlaylist(it) },
            setFavorite = vm::setFavorite,
            rename = { dialog = LibraryDialog.Rename(it) },
            delete = { dialog = LibraryDialog.Delete(it) },
        )
    }
    val play = LibraryPlay(playback, onPlaying)

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
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                LibraryItems(state, vm, listState, readingPadding(maxWidth), actions, play) { dialog = it }
            }
        }
    }
    if (adding) AddSheet(pickers) { adding = false }
    dialog?.let { LibraryDialogs(it, vm) { dialog = null } }
}

/** Playing from the library: the playback service starts, and Now playing is shown. */
private class LibraryPlay(private val playback: PlaybackStarter, private val onPlaying: () -> Unit) {
    fun piece(pieceId: Long, queue: List<Long>) {
        playback.play(pieceId, queue)
        onPlaying()
    }

    fun all(pieceIds: List<Long>, shuffle: Boolean) {
        if (pieceIds.isEmpty()) return
        playback.playAll(pieceIds, shuffle)
        onPlaying()
    }
}

/** A piece row's key; only these are reorderable, so no other key starts with "p" and a digit. */
private fun pieceKey(id: Long): String = "p$id"

private fun isPieceKey(key: Any): Boolean = key is String && key.length > 1 && key[0] == 'p' && key[1].isDigit()

@Composable
private fun LibraryItems(
    state: LibraryState,
    vm: LibraryViewModel,
    listState: LazyListState,
    padding: PaddingValues,
    actions: PieceActions,
    play: LibraryPlay,
    onDialog: (LibraryDialog) -> Unit,
) {
    val columns = LocalAppFrame.current.tileColumns
    val listing = state.listing
    val group = state.group
    val playlistId = (group as? Group.Playlist)?.id
    val pieces = (listing as? Listing.Pieces)?.pieces.orEmpty()
    // Reordering needs the whole playlist on screen: not while a search narrows it.
    val reorderable = playlistId != null && vm.query.isBlank()
    // The order on screen while a drag is under way and until the playlist has caught up with it.
    var dragged by remember(playlistId) { mutableStateOf<List<Long>?>(null) }
    val shown = dragged?.let { order -> reorderedBy(pieces, order) { it.id } } ?: pieces
    val drag = rememberDragReorderState(
        listState,
        canMoveTo = ::isPieceKey,
        onMove = { from, to ->
            val order = moved(dragged ?: pieces.map { it.id }, (from as String).drop(1).toLong(), (to as String).drop(1).toLong())
            if (order != null) dragged = order
            order != null
        },
        onDrop = { if (playlistId != null) dragged?.let { vm.reorderPlaylist(playlistId, it) } },
    )
    LaunchedEffect(pieces, drag.draggingKey) {
        if (drag.draggingKey == null && dragged == pieces.map { it.id }) dragged = null
    }
    val rowActions = remember(actions, playlistId) {
        if (playlistId == null) {
            actions
        } else {
            actions.forPlaylist(
                remove = { vm.removeFromPlaylist(playlistId, it.id) },
                move = { piece, delta -> vm.movePiece(playlistId, piece.id, delta) },
            )
        }
    }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = padding) {
        item(key = "search") { SearchField(vm.query, vm::search) }
        item(key = "categories") { CategoryChips(state.category, vm::selectCategory) }
        when (group) {
            is Group.Playlist -> item(key = "header") {
                val summary = (listing as? Listing.Pieces)?.playlist
                    ?: PlaylistSummary(group.id, group.name, false, pieces.size, pieces.sumOf { it.durationMs })
                PlaylistHeader(
                    summary,
                    cover = { MonogramTile(summary.name, it) },
                    onBack = vm::closeGroup,
                    onPlay = { play.all(shown.map { it.id }, shuffle = false) },
                    onShuffle = { play.all(shown.map { it.id }, shuffle = true) },
                    onRename = { onDialog(LibraryDialog.RenamePlaylist(summary)) },
                    onDelete = { onDialog(LibraryDialog.DeletePlaylist(summary)) },
                )
            }
            is Group.Composer -> item(key = "group") { GroupHeader(group, pieces.size, vm::closeGroup) }
            null -> Unit
        }
        when (listing) {
            is Listing.Pieces -> itemsIndexed(shown, key = { _, piece -> pieceKey(piece.id) }) { index, piece ->
                val key = pieceKey(piece.id)
                val place = RowPlace(index, shown.size)
                PieceRow(
                    piece,
                    rowActions,
                    onPlay = { play.piece(piece.id, shown.map { it.id }) },
                    modifier = if (reorderable) Modifier.reorderable(drag, key, this) else Modifier,
                    place = if (reorderable) place else null,
                    trailing = if (reorderable) {
                        {
                            DragHandle(
                                drag,
                                key,
                                onMoveUp = if (place.canMoveUp) ({ vm.movePiece(playlistId, piece.id, -1) }) else null,
                                onMoveDown = if (place.canMoveDown) ({ vm.movePiece(playlistId, piece.id, 1) }) else null,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            is Listing.Playlists -> {
                item(key = "tiles-top") { Spacer(Modifier.height(8.dp)) }
                items(listing.playlists.chunked(columns), key = { row -> "tiles-pl-${row.first().id}" }) { row ->
                    TileRow(columns, row.size) {
                        row.forEach { playlist ->
                            PlaylistTile(
                                playlist,
                                onOpen = { vm.openGroup(Group.Playlist(playlist.id, playlist.name)) },
                                onDialog = onDialog,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            is Listing.Composers -> {
                item(key = "tiles-top") { Spacer(Modifier.height(8.dp)) }
                items(listing.composers.chunked(columns), key = { row -> "tiles-k-${row.first().composerKey}" }) { row ->
                    TileRow(columns, row.size) {
                        row.forEach { composer ->
                            ComposerTile(
                                composer,
                                onOpen = { vm.openGroup(Group.Composer(composer.composerKey, composerName(composer))) },
                                onPlayAll = { shuffle -> vm.composerPieces(composer.composerKey) { play.all(it, shuffle) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        if (listing.isEmpty) item(key = "none") { EmptyListing(state, vm.query.trim()) }
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

/** Inside a composer: back, the name, how many pieces. */
@Composable
private fun GroupHeader(group: Group.Composer, pieceCount: Int, onBack: () -> Unit) {
    Column {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(R.drawable.ic_back, "Back to composers", onClick = onBack)
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
        state.group is Group.Playlist -> "This playlist is empty." to "Long-press a piece to add it here."
        state.category == Category.Favorites -> "No favorites yet." to "Long-press a piece to make it a favorite."
        state.category == Category.Playlists -> "No playlists yet." to "Long-press a piece to add it to a playlist."
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
