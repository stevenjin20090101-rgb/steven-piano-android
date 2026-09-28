// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.stevenjin.stevenpiano.data.LibraryRepository
import dev.stevenjin.stevenpiano.data.TextKeys
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.db.ComposerGroup
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.PlaylistSummary
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The chips, Synthesia-style. */
enum class Category(val label: String) {
    All("All"),
    Playlists("Playlists"),
    Composers("Composers"),
    Favorites("Favorites"),
    Recent("Recent"),
}

/** A second-level list: one playlist's pieces, or one composer's. */
sealed interface Group {
    val name: String

    data class Playlist(val id: Long, override val name: String) : Group

    data class Composer(val key: String, override val name: String) : Group
}

/** What the list shows under the chips. */
sealed interface Listing {
    val isEmpty: Boolean

    /** Pieces; inside a playlist, [playlist] is the whole playlist's name, size and length, whatever the search shows. */
    data class Pieces(val pieces: List<PieceEntity>, val playlist: PlaylistSummary? = null) : Listing {
        override val isEmpty: Boolean get() = pieces.isEmpty()
    }

    data class Playlists(val playlists: List<PlaylistSummary>) : Listing {
        override val isEmpty: Boolean get() = playlists.isEmpty()
    }

    data class Composers(val composers: List<ComposerGroup>) : Listing {
        override val isEmpty: Boolean get() = composers.isEmpty()
    }
}

data class LibraryState(
    val category: Category = Category.All,
    val group: Group? = null,
    val listing: Listing = Listing.Pieces(emptyList()),
    val pieceCount: Int = 0,
    val loaded: Boolean = false,
    /** The library could not be read (a damaged database, a row too large to read): the tab says so instead of crashing. */
    val unreadable: Boolean = false,
) {
    /** Nothing imported yet. */
    val empty: Boolean get() = loaded && !unreadable && pieceCount == 0
}

/** What the Library shows: a category, or a group inside it. */
internal data class Selection(val category: Category, val group: Group?)

/**
 * The Library's state from the [selections] (with the search text) and the piece [count]: each
 * selection's [listing]. A listing or count that fails to read becomes the unreadable state, logged
 * through [log], instead of an exception that would crash the app; choosing another category or
 * search reads again. Pure over its flows, so it is unit-tested.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun libraryStates(
    selections: Flow<Pair<Selection, String>>,
    count: Flow<Int>,
    log: (Throwable) -> Unit,
    listing: (Selection, String) -> Flow<Listing>,
): Flow<LibraryState> =
    selections
        .flatMapLatest { (sel, q) ->
            listing(sel, q)
                .map { LibraryState(sel.category, sel.group, it, loaded = true) }
                .catch { e ->
                    if (e is CancellationException) throw e
                    log(e)
                    emit(LibraryState(sel.category, sel.group, loaded = true, unreadable = true))
                }
        }
        .combine(count) { state, pieces -> state.copy(pieceCount = pieces) }
        .catch { e ->
            if (e is CancellationException) throw e
            log(e)
            emit(LibraryState(loaded = true, unreadable = true))
        }

/**
 * The Library tab: the chosen category or group, filtered by the search field (title and
 * composer, case and accents ignored), and the menus' edits. Edits run in [writes], the app's
 * scope, so leaving the tab never cuts one short. A deleted piece or playlist takes its artwork
 * with it ([forgetArtwork]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val library: LibraryRepository,
    val importProgress: StateFlow<ImportProgress>,
    private val writes: CoroutineScope,
    private val forgetArtwork: (String) -> Unit = {},
) : ViewModel() {
    private val selection = MutableStateFlow(Selection(Category.All, null))

    /** The search field's text. Compose state, so typing never waits on the database. */
    var query by mutableStateOf("")
        private set

    /** The finished import whose summary was dismissed. */
    var dismissedImport by mutableStateOf<ImportProgress?>(null)
        private set

    val state: StateFlow<LibraryState> =
        libraryStates(
            combine(selection, snapshotFlow { query.trim() }.distinctUntilChanged()) { sel, q -> sel to q },
            library.count(),
            log = { Log.w(TAG, "The library couldn't be read", it) },
            listing = ::listing,
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryState())

    /** Every playlist of the person's (never a built-in one: the app sets those), for the dialogs; none when the library can't be read. */
    val playlists: Flow<List<PlaylistSummary>> get() = library.playlists().map { all -> all.filterNot { it.builtIn } }.orNone()

    fun membershipOf(pieceId: Long): Flow<List<Long>> = library.playlistIdsOf(pieceId).orNone()

    fun search(text: String) {
        query = text
    }

    fun selectCategory(category: Category) {
        selection.value = Selection(category, null)
    }

    fun openGroup(group: Group) {
        query = ""
        selection.update { it.copy(group = group) }
    }

    fun closeGroup() {
        selection.update { it.copy(group = null) }
    }

    fun dismissImport(progress: ImportProgress) {
        dismissedImport = progress
    }

    fun setFavorite(piece: PieceEntity, favorite: Boolean) = write { library.setFavorite(piece.id, favorite) }

    fun rename(piece: PieceEntity, title: String, composer: String) = write { library.rename(piece.id, title, composer) }

    fun delete(piece: PieceEntity) {
        write { library.delete(piece.id) }
        forgetArtwork(ArtworkEntity.forPiece(piece.id))
    }

    fun setMembership(playlistId: Long, pieceId: Long, member: Boolean) = write {
        if (member) library.addToPlaylist(playlistId, pieceId) else library.removeFromPlaylist(playlistId, pieceId)
    }

    /** Makes the playlist (or finds the one with that name) and puts the piece at its end. */
    fun addToNewPlaylist(name: String, pieceId: Long) = write {
        library.addToPlaylist(library.createPlaylist(name), pieceId)
    }

    fun renamePlaylist(id: Long, name: String) = write { library.renamePlaylist(id, name) }

    /** Deletes the playlist (never its pieces); its page closes if it is open. */
    fun deletePlaylist(id: Long) {
        if ((selection.value.group as? Group.Playlist)?.id == id) closeGroup()
        write { library.deletePlaylist(id) }
        forgetArtwork(ArtworkEntity.forPlaylist(id))
    }

    fun removeFromPlaylist(playlistId: Long, pieceId: Long) = write { library.removeFromPlaylist(playlistId, pieceId) }

    /** Move up ([delta] -1) or Move down (+1) from a row's menu. */
    fun movePiece(playlistId: Long, pieceId: Long, delta: Int) = write { library.movePiece(playlistId, pieceId, delta) }

    /** A drag in the playlist ended with its pieces in [orderedIds]' order. */
    fun reorderPlaylist(playlistId: Long, orderedIds: List<Long>) = write { library.reorderPlaylist(playlistId, orderedIds) }

    /** A composer's pieces, by title, for Play all and Shuffle on the composer's tile. Nothing plays if they can't be read. */
    fun composerPieces(composerKey: String, then: (List<Long>) -> Unit) {
        viewModelScope.launch {
            val ids = try {
                library.byComposer(composerKey).first().map { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Log.w(TAG, "A composer's pieces couldn't be read", e)
                return@launch
            }
            then(ids)
        }
    }

    private fun listing(sel: Selection, query: String): Flow<Listing> {
        val key = TextKeys.fold(query)
        return when (val group = sel.group) {
            is Group.Playlist -> combine(library.inPlaylist(group.id), library.playlists()) { pieces, all ->
                Listing.Pieces(pieces.matching(key), all.firstOrNull { it.id == group.id })
            }
            is Group.Composer -> library.byComposer(group.key).map { Listing.Pieces(it.matching(key)) }
            null -> when (sel.category) {
                Category.All -> (if (key.isEmpty()) library.all() else library.search(query)).map { Listing.Pieces(it) }
                Category.Favorites -> library.favorites().map { Listing.Pieces(it.matching(key)) }
                Category.Recent -> library.recent().map { Listing.Pieces(it.matching(key)) }
                Category.Playlists -> library.playlists().map { all -> Listing.Playlists(PlaylistShelf.shown(all).filter { key in TextKeys.fold(it.name) }) }
                Category.Composers -> library.composers().map { all -> Listing.Composers(all.filter { key in TextKeys.fold(it.name) }) }
            }
        }
    }

    private fun List<PieceEntity>.matching(key: String): List<PieceEntity> = if (key.isEmpty()) this else filter { key in it.searchText }

    /** A read that fails ends as an empty list, logged, rather than an exception in a dialog. */
    private fun <T> Flow<List<T>>.orNone(): Flow<List<T>> = catch { e ->
        if (e is CancellationException) throw e
        Log.w(TAG, "The library couldn't be read", e)
        emit(emptyList())
    }

    private fun write(block: suspend () -> Unit) {
        writes.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {   // e.g. a name another playlist already has
                Log.w(TAG, "Library edit failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "Library"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * The Playlists grid's order: the built-in playlists first, in the order they were made (the
 * catalogue's), then the rest by name as the library lists them. A built-in playlist with nothing
 * in it is not shown at all: the library holds none of its pieces yet.
 */
internal object PlaylistShelf {
    fun shown(all: List<PlaylistSummary>): List<PlaylistSummary> {
        val (builtIn, others) = all.partition { it.builtIn }
        return builtIn.filter { it.pieceCount > 0 }.sortedBy { it.id } + others
    }
}
