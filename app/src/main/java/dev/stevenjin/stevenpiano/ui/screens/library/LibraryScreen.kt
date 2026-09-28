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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
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
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.data.art.ArtSize
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.db.PlaylistSummary
import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import dev.stevenjin.stevenpiano.data.imports.ImportSource
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.schedule.ScheduleDraft
import dev.stevenjin.stevenpiano.service.ArtworkService
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.KioskGate
import dev.stevenjin.stevenpiano.ui.KioskGateSheet
import dev.stevenjin.stevenpiano.ui.KioskLockCopy
import dev.stevenjin.stevenpiano.ui.LocalAppFrame
import dev.stevenjin.stevenpiano.ui.LocalFloatingPadding
import dev.stevenjin.stevenpiano.ui.PlaybackStarter
import dev.stevenjin.stevenpiano.ui.LockGlyph
import dev.stevenjin.stevenpiano.ui.Sentences
import dev.stevenjin.stevenpiano.ui.rememberChannelName
import dev.stevenjin.stevenpiano.ui.rememberKioskGate
import dev.stevenjin.stevenpiano.ui.components.ComposerArt
import dev.stevenjin.stevenpiano.ui.components.DragHandle
import dev.stevenjin.stevenpiano.ui.components.FloatingPlayClearance
import dev.stevenjin.stevenpiano.ui.components.FloatingPlayRequest
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.Hairline
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.CrashBanner
import dev.stevenjin.stevenpiano.ui.components.OutlinedBanner
import dev.stevenjin.stevenpiano.ui.components.PlaylistCover
import dev.stevenjin.stevenpiano.ui.components.ScreenHeader
import dev.stevenjin.stevenpiano.ui.components.moved
import dev.stevenjin.stevenpiano.ui.components.readingPadding
import dev.stevenjin.stevenpiano.ui.components.readingWidth
import dev.stevenjin.stevenpiano.ui.components.rememberArtworkRow
import dev.stevenjin.stevenpiano.ui.components.rememberDragReorderState
import dev.stevenjin.stevenpiano.ui.components.reorderable
import dev.stevenjin.stevenpiano.ui.components.reorderedBy
import dev.stevenjin.stevenpiano.ui.screens.nowplaying.NowPlayingPanel
import dev.stevenjin.stevenpiano.ui.screens.piece.PieceDetailSheet
import dev.stevenjin.stevenpiano.ui.screens.schedule.ScheduleDraftSaver
import dev.stevenjin.stevenpiano.ui.screens.schedule.ScheduleEditorSheet
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * The Library tab: search, the category chips, then text rows, or grids of tiles for Playlists
 * and Composers. Tapping a piece plays it (with the list it was in as the queue) and [onPlaying]
 * shows Now playing. A playlist opens as a page (its cover, Play and Shuffle) whose rows reorder
 * by their drag handles while no search narrows them. Row and tile menus act through [playback]
 * and the view model; "About this piece" opens the piece sheet and "Change photo" the photo
 * picker. A composer opens with their portrait and blurb. [onImport] brings files in; artwork
 * fetched in the background shows its progress under the import bar. On the launch after a crash,
 * an outlined banner offers to share diagnostics; while guests' requests wait for approval, another
 * offers the oldest with Approve and Dismiss ([RequestsBanner]). On wide screens the content stays a 720 dp
 * column in the middle; the list still scrolls from anywhere across the screen, and under the tab
 * bar's glass, its last row able to rise above it ([LocalFloatingPadding]). An open playlist's Play
 * floats as a glass circle at the bottom end of its column ([FloatingPlayRequest]). On wide frames
 * (AppFrame.twoPane) the Library is two panes: the list in 55 % of the width and the now-playing
 * panel ([NowPlayingPanel]) beside it; playing a piece then stays on the Library, and the Now
 * playing tab remains for the full score. In kiosk mode the library's changes are locked (DESIGN.md
 * › v1.6.1 — M20): adding music (the + and its sheet), deleting, renaming, adding to and taking out of
 * playlists, reordering them, a playlist's photo, a channel's volume and scheduling a channel wait for
 * the kiosk PIN ([KioskGate]); playing, queueing, favourites and browsing never do. A channel's
 * Schedule opens the schedule editor with the channel chosen (DESIGN.md › v1.6.2 — M19).
 */
@Composable
fun LibraryScreen(playback: PlaybackStarter, onPlaying: () -> Unit, onOpenPiano: () -> Unit, onImport: (ImportSource) -> Unit) {
    val graph = LocalContext.current.graph
    val vm = viewModel { LibraryViewModel(graph.library, graph.importProgress, graph.appScope, graph.artwork::forget, graph.channelPools.summaries) }
    val state by vm.state.collectAsStateWithLifecycle()
    val importProgress by vm.importProgress.collectAsStateWithLifecycle()
    val artworkProgress by graph.artwork.progress.collectAsStateWithLifecycle()
    val crashed by graph.crashNotice.collectAsStateWithLifecycle()
    val requests by graph.web.requests.pending.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var adding by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var about by rememberSaveable { mutableStateOf<Long?>(null) }
    var photoFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var volumeFor by rememberSaveable { mutableStateOf<String?>(null) }
    val gate = rememberKioskGate()
    var scheduling by rememberSaveable(stateSaver = ScheduleDraftSaver) { mutableStateOf<ScheduleDraft?>(null) }
    val pickers = rememberImportPickers(onImport)
    // The picker's grant ends with this screen: the photo is copied at once, in the app's scope.
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val playlistId = photoFor
        photoFor = null
        if (uri != null && playlistId != null) graph.appScope.launch { graph.artwork.setPlaylistPhoto(playlistId, uri) }
    }
    val changePhoto: (Long) -> Unit = { playlistId ->
        gate.run {
            photoFor = playlistId
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    // Every dialog changes the library (add to a playlist, rename, delete): in kiosk mode each waits for the PIN.
    val openDialog: (LibraryDialog) -> Unit = { next -> gate.run { dialog = next } }
    val addMusic: () -> Unit = { gate.run { adding = true } }
    val actions = remember(vm, playback, gate) {
        PieceActions(
            playNext = { playback.playNext(listOf(it.id)) },
            addToQueue = { playback.addToQueue(listOf(it.id)) },
            about = { about = it.id },
            addToPlaylist = { piece -> gate.run { dialog = LibraryDialog.AddToPlaylist(piece) } },
            setFavorite = vm::setFavorite,
            rename = { piece -> gate.run { dialog = LibraryDialog.Rename(piece) } },
            delete = { piece -> gate.run { dialog = LibraryDialog.Delete(piece) } },
        )
    }
    val frame = LocalAppFrame.current
    // On wide frames the piece shows in the panel beside the list: Now playing is not opened.
    val play = LibraryPlay(playback, onPlaying = { if (!frame.twoPane) onPlaying() })

    BackHandler(enabled = state.group != null, onBack = vm::closeGroup)
    val floating = LocalFloatingPadding.current
    val direction = LocalLayoutDirection.current
    // The list rises above the tab bar; with the keyboard up (which hides the bar) it ends at the keyboard.
    val listBottom = (floating.calculateBottomPadding() - WindowInsets.ime.asPaddingValues().calculateBottomPadding()).coerceAtLeast(0.dp)
    val sides = Modifier.padding(start = floating.calculateStartPadding(direction), end = floating.calculateEndPadding(direction))

    val library: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.imePadding()) {
            Column(Modifier.readingWidth()) {
                ScreenHeader("Library") {
                    // Settings locked in kiosk: a padlock beside the +, which then asks for the PIN.
                    if (gate.locked) LockGlyph(description = null)
                    GlyphButton(R.drawable.ic_add, if (gate.locked) "Add MIDI files, ${KioskLockCopy.LOCKED.lowercase()}" else "Add MIDI files", onClick = addMusic)
                }
                HairlineDivider()
                ImportBar(importProgress, vm.dismissedImport, vm::dismissImport)
                ArtworkBar(artworkProgress)
                if (crashed) CrashBanner(onAnswered = graph::answerCrashNotice, modifier = Modifier.padding(16.dp))
                RequestsBanner(requests, onApprove = graph.web::approve, onDismiss = graph.web::dismiss, modifier = Modifier.padding(16.dp))
            }
            when {
                !state.loaded -> Unit
                state.unreadable -> Column(Modifier.readingWidth()) {
                    CategoryChips(state.category, vm::selectCategory)
                    OutlinedBanner(UNREADABLE, Modifier.padding(16.dp))
                }
                state.empty -> EmptyLibrary(onAdd = addMusic)
                else -> {
                    var listBounds by remember { mutableStateOf<Rect?>(null) }
                    BoxWithConstraints(
                        Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { listBounds = it.boundsInRoot() },
                    ) {
                        val padding = readingPadding(maxWidth, bottom = listBottom)
                        // The reading column's bottom end, above the floating controls: where a playlist's Play floats.
                        val density = LocalDensity.current
                        val anchor = listBounds?.let { box ->
                            with(density) {
                                val side = padding.calculateStartPadding(direction).toPx()
                                Rect(box.left + side, box.top, box.right - side, box.bottom - listBottom.toPx())
                            }
                        }
                        // A schedule changes when the piano plays: in kiosk mode it waits for the PIN, as a
                        // channel's volume does.
                        val schedule: (String) -> Unit = { key ->
                            gate.run {
                                scheduling = ScheduleDraft.fresh(LocalTime.now(), ScheduleKind.CHANNEL, key, graph.settings.value.channelVolume(key))
                            }
                        }
                        LibraryItems(state, vm, listState, padding, anchor, actions, play, changePhoto, { key -> gate.run { volumeFor = key } }, schedule, gate, openDialog)
                    }
                }
            }
        }
    }
    if (frame.twoPane) {
        // The list (55 %, its 720 dp reading width inside it) and the now-playing panel (45 %), a hairline between.
        Row(
            Modifier
                .fillMaxSize()
                .then(sides),
        ) {
            library(Modifier.weight(LIST_SHARE).fillMaxHeight())
            VerticalDivider(thickness = Hairline, color = LocalHairline.current)
            NowPlayingPanel(
                playback,
                onOpenPiano,
                Modifier
                    .weight(1f - LIST_SHARE)
                    .fillMaxHeight()
                    .padding(bottom = floating.calculateBottomPadding()),
            )
        }
    } else {
        library(Modifier.fillMaxSize().then(sides))
    }
    val context = LocalContext.current
    if (adding) AddSheet(pickers, onFetchArtwork = { ArtworkService.start(context, force = true) }) { adding = false }
    dialog?.let { LibraryDialogs(it, vm) { dialog = null } }
    about?.let { PieceDetailSheet(it) { about = null } }
    volumeFor?.let { key ->
        val name = rememberChannelName(key) ?: key
        ChannelVolumeSheet(key, name) { volumeFor = null }
    }
    scheduling?.let { draft -> ScheduleEditorSheet(draft) { scheduling = null } }
    KioskGateSheet(gate)
}

/** The list's share of a wide frame; the now-playing panel has the rest. */
private const val LIST_SHARE = 0.55f

/** Playing from the library: the playback service starts, and Now playing is shown ([onPlaying]; not on wide frames, whose panel shows the piece). */
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

    /** A channel's card: endless play from its pool (nothing when the pool is too small). */
    fun channel(key: String) {
        if (playback.playChannel(key)) onPlaying()
    }
}

/** What the tab says when the library can't be read, in place of a crash. */
private const val UNREADABLE = "The library couldn't be read."

/** A piece row's key; only these are reorderable, so no other key starts with "p" and a digit. */
private fun pieceKey(id: Long): String = "p$id"

private fun isPieceKey(key: Any): Boolean = key is String && key.length > 1 && key[0] == 'p' && key[1].isDigit()

@Composable
private fun LibraryItems(
    state: LibraryState,
    vm: LibraryViewModel,
    listState: LazyListState,
    padding: PaddingValues,
    playAnchor: Rect?,
    actions: PieceActions,
    play: LibraryPlay,
    onChangePhoto: (Long) -> Unit,
    onSetVolume: (String) -> Unit,
    onSchedule: (String) -> Unit,
    gate: KioskGate,
    onDialog: (LibraryDialog) -> Unit,
) {
    val columns = LocalAppFrame.current.tileColumns
    val graph = LocalContext.current.graph
    // Which channel plays, for its card, and whether the piano is there to hear it (the dot's colour).
    val playingChannel = graph.player.state.collectAsStateWithLifecycle().value.channel
    val connected = graph.pianoLink.state.collectAsStateWithLifecycle().value is LinkState.Connected
    val listing = state.listing
    val group = state.group
    val playlistId = (group as? Group.Playlist)?.id
    val pieces = (listing as? Listing.Pieces)?.pieces.orEmpty()
    // A built-in playlist's order and pieces are the app's: no handles, no Move or Remove in its rows.
    val builtIn = (listing as? Listing.Pieces)?.playlist?.builtIn == true
    // Reordering needs the whole playlist on screen: not while a search narrows it, nor while kiosk mode
    // locks the library's changes (a drag can't wait for a PIN: the handles are simply not there).
    val reorderable = playlistId != null && !builtIn && vm.query.isBlank() && !gate.locked
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
    val rowActions = remember(actions, playlistId, builtIn, gate) {
        if (playlistId == null || builtIn) {
            actions
        } else {
            actions.forPlaylist(
                remove = { piece -> gate.run { vm.removeFromPlaylist(playlistId, piece.id) } },
                move = { piece, delta -> gate.run { vm.movePiece(playlistId, piece.id, delta) } },
            )
        }
    }

    // An open playlist's Play floats at the list's bottom end; its last row can rise clear of it.
    val floatingPlay = playlistId != null && shown.isNotEmpty()
    FloatingPlayRequest(floatingPlay, playAnchor) { play.all(shown.map { it.id }, shuffle = false) }
    val direction = LocalLayoutDirection.current
    val listPadding = if (!floatingPlay) {
        padding
    } else {
        PaddingValues(
            start = padding.calculateStartPadding(direction),
            end = padding.calculateEndPadding(direction),
            bottom = padding.calculateBottomPadding() + FloatingPlayClearance,
        )
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = listPadding) {
        item(key = "search") { SearchField(vm.query, vm::search) }
        item(key = "categories") { CategoryChips(state.category, vm::selectCategory) }
        when (group) {
            is Group.Playlist -> item(key = "header") {
                val summary = (listing as? Listing.Pieces)?.playlist
                    ?: PlaylistSummary(group.id, group.name, false, pieces.size, pieces.sumOf { it.durationMs })
                PlaylistHeader(
                    summary,
                    cover = { PlaylistCover(summary.id, summary.name, ArtSize.Tile, it) },
                    onBack = vm::closeGroup,
                    onShuffle = { play.all(shown.map { it.id }, shuffle = true) },
                    onRename = { onDialog(LibraryDialog.RenamePlaylist(summary)) },
                    onChangePhoto = { onChangePhoto(summary.id) },
                    onDelete = { onDialog(LibraryDialog.DeletePlaylist(summary)) },
                )
            }
            Group.Channels -> item(key = "header") {
                ChannelsHeader((listing as? Listing.Channels)?.channels?.size ?: 0, onBack = vm::closeGroup)
            }
            is Group.Composer -> item(key = "group") {
                val artwork = rememberArtworkRow(ArtworkEntity.forComposer(group.key))
                ComposerHeader(
                    portrait = { ComposerArt(group.key, group.name, ArtSize.Tile, it) },
                    name = group.name,
                    meta = Format.count(pieces.size, "piece", "pieces"),
                    blurb = artwork?.description?.let(Sentences::firstTwo),
                    sourceUrl = artwork?.sourceUrl,
                    onBack = vm::closeGroup,
                )
            }
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
                if (listing.channels.isNotEmpty()) {
                    item(key = "channels") {
                        ChannelRow(
                            listing.channels,
                            playing = playingChannel,
                            connected = connected,
                            onPlay = play::channel,
                            onSetVolume = onSetVolume,
                            onSchedule = onSchedule,
                            onSeeAll = { vm.openGroup(Group.Channels) },
                        )
                    }
                }
                item(key = "tiles-top") { Spacer(Modifier.height(8.dp)) }
                items(listing.playlists.chunked(columns), key = { row -> "tiles-pl-${row.first().id}" }) { row ->
                    TileRow(columns, row.size) {
                        row.forEach { playlist ->
                            PlaylistTile(
                                playlist,
                                onOpen = { vm.openGroup(Group.Playlist(playlist.id, playlist.name)) },
                                onChangePhoto = { onChangePhoto(playlist.id) },
                                onDialog = onDialog,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            is Listing.Channels -> channelsGrid(listing.channels, columns, playingChannel, connected, play::channel, onSetVolume, onSchedule)
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
        (state.listing as? Listing.Pieces)?.playlist?.builtIn == true -> "This playlist is empty." to "It fills itself from the library's pieces."
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
