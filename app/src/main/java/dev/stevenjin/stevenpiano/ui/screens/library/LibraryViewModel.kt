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
import dev.stevenjin.stevenpiano.channels.ChannelSummary
import dev.stevenjin.stevenpiano.data.Genres
import dev.stevenjin.stevenpiano.data.LibraryRepository
import dev.stevenjin.stevenpiano.data.LibraryScope
import dev.stevenjin.stevenpiano.data.PlaylistOrder
import dev.stevenjin.stevenpiano.data.PlaylistSort
import dev.stevenjin.stevenpiano.data.TextKeys
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.db.ComposerGroup
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.PlaylistSummary
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The chips, Synthesia-style. */
enum class Category(private val word: String) {
    All("Pieces"),
    Playlists("Playlists"),
    Composers("Composers"),
    Favorites("Favorites"),
    Recent("Recent"),
    ;

    /**
     * The chip's word under the genre [scope] (v1.14 — M37): every piece is "Pieces" (never a second "All" beside the
     * switch's), and under Modern the composers are "Artists".
     */
    fun label(scope: LibraryScope): String = if (this == Composers && scope == LibraryScope.Modern) ARTISTS else word

    private companion object {
        const val ARTISTS = "Artists"
    }
}

/** A second-level list: one playlist's pieces, one composer's, or every channel (the Playlists' See all). */
sealed interface Group {
    val name: String

    data class Playlist(val id: Long, override val name: String) : Group

    data class Composer(val key: String, override val name: String) : Group

    data object Channels : Group {
        override val name: String get() = "Channels"
    }
}

/** What the list shows under the chips. */
sealed interface Listing {
    val isEmpty: Boolean

    /** Pieces; inside a playlist, [playlist] is the whole playlist's name, size and length, whatever the search shows. */
    data class Pieces(val pieces: List<PieceEntity>, val playlist: PlaylistSummary? = null) : Listing {
        override val isEmpty: Boolean get() = pieces.isEmpty()
    }

    /** The playlists' grid in the [sort] chosen, under the channels' row ([channels]; none while a search narrows the list). */
    data class Playlists(
        val playlists: List<PlaylistSummary>,
        val channels: List<ChannelSummary> = emptyList(),
        val sort: PlaylistSort = PlaylistSort.NEWEST,
    ) : Listing {
        override val isEmpty: Boolean get() = playlists.isEmpty()
    }

    /** Every channel, as cards in a grid. */
    data class Channels(val channels: List<ChannelSummary>) : Listing {
        override val isEmpty: Boolean get() = channels.isEmpty()
    }

    /**
     * The composers' (or artists') grid; [genreByKey] is each name's genre, the one most of all its pieces have, for
     * its tile's Move (v1.14 — M37: none for the blank name, a made-here one, or a tie).
     */
    data class Composers(val composers: List<ComposerGroup>, val genreByKey: Map<String, Int> = emptyMap()) : Listing {
        override val isEmpty: Boolean get() = composers.isEmpty()
    }
}

data class LibraryState(
    val category: Category = Category.All,
    val group: Group? = null,
    val listing: Listing = Listing.Pieces(emptyList()),
    /** Every piece's count, whatever the genre shown. */
    val pieceCount: Int = 0,
    val loaded: Boolean = false,
    /** The library could not be read (a damaged database, a row too large to read): the tab says so instead of crashing. */
    val unreadable: Boolean = false,
    /** The genre the [listing] is of (v1.14 — M37). */
    val scope: LibraryScope = LibraryScope.All,
) {
    /** Nothing imported yet. */
    val empty: Boolean get() = loaded && !unreadable && pieceCount == 0
}

/** What the Library shows: a category, or a group inside it, of one genre or all ([scope], v1.14 — M37). */
internal data class Selection(val category: Category, val group: Group?, val scope: LibraryScope = LibraryScope.All)

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
                .map { LibraryState(sel.category, sel.group, it, loaded = true, scope = sel.scope) }
                .catch { e ->
                    if (e is CancellationException) throw e
                    log(e)
                    emit(LibraryState(sel.category, sel.group, loaded = true, unreadable = true, scope = sel.scope))
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
 * with it ([forgetArtwork]). Everything shown is of the genre chosen (v1.14 — M37: All, Classical
 * or Modern), which starts as the one chosen last ([rememberedScope], read once) and is remembered
 * through [saveScope].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val library: LibraryRepository,
    val importProgress: StateFlow<ImportProgress>,
    private val writes: CoroutineScope,
    private val forgetArtwork: (String) -> Unit = {},
    private val channels: Flow<List<ChannelSummary>?> = flowOf(null),
    private val playlistSort: Flow<PlaylistSort> = flowOf(PlaylistSort.NEWEST),
    private val builtInOrder: () -> List<String> = { emptyList() },
    rememberedScope: Flow<LibraryScope> = flowOf(LibraryScope.All),
    private val saveScope: suspend (LibraryScope) -> Unit = {},
) : ViewModel() {
    /** Null until the genre remembered has been read: nothing is listed before, so the genre never changes as the tab opens. */
    private val selection = MutableStateFlow<Selection?>(null)

    init {
        viewModelScope.launch {
            val remembered = try {
                rememberedScope.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Log.w(TAG, "The Library's genre couldn't be read", e)
                LibraryScope.All
            }
            selection.update { it ?: Selection(Category.All, null, remembered) }
        }
    }

    /** The genre chosen, at once (the switch's thumb moves as it is touched; the list follows when it has been read). */
    val scope: StateFlow<LibraryScope?> = selection.map { it?.scope }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The search field's text. Compose state, so typing never waits on the database. */
    var query by mutableStateOf("")
        private set

    /** The finished import whose summary was dismissed. */
    var dismissedImport by mutableStateOf<ImportProgress?>(null)
        private set

    val state: StateFlow<LibraryState> =
        libraryStates(
            combine(selection.filterNotNull(), snapshotFlow { query.trim() }.distinctUntilChanged()) { sel, q -> sel to q },
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
        selection.update { Selection(category, null, it?.scope ?: LibraryScope.All) }
    }

    /**
     * The genre (v1.14 — M37): remembered across restarts; an open playlist, composer or the channels close, and the
     * chip and the search text stay. Choosing the one already chosen changes nothing.
     */
    fun selectScope(scope: LibraryScope) {
        if (selection.value?.scope == scope) return
        selection.update { (it ?: Selection(Category.All, null)).copy(group = null, scope = scope) }
        write { saveScope(scope) }
    }

    fun openGroup(group: Group) {
        query = ""
        selection.update { it?.copy(group = group) }
    }

    fun closeGroup() {
        selection.update { it?.copy(group = null) }
    }

    fun dismissImport(progress: ImportProgress) {
        dismissedImport = progress
    }

    fun setFavorite(piece: PieceEntity, favorite: Boolean) = write { library.setFavorite(piece.id, favorite) }

    fun rename(piece: PieceEntity, title: String, composer: String) = write { library.rename(piece.id, title, composer) }

    /** The piece moves to Classical or Modern (v1.14 — M37); a list of the other genre lets it go. */
    fun setGenre(piece: PieceEntity, genre: Int) = write { library.setGenre(piece.id, genre) }

    /** Every piece by [composerKey] moves to Classical or Modern, so the artist's later uploads follow. */
    fun setComposerGenre(composerKey: String, genre: Int) = write { library.setComposerGenre(composerKey, genre) }

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
        if ((selection.value?.group as? Group.Playlist)?.id == id) closeGroup()
        write { library.deletePlaylist(id) }
        forgetArtwork(ArtworkEntity.forPlaylist(id))
    }

    fun removeFromPlaylist(playlistId: Long, pieceId: Long) = write { library.removeFromPlaylist(playlistId, pieceId) }

    /** Move up ([delta] -1) or Move down (+1) from a row's menu. */
    fun movePiece(playlistId: Long, pieceId: Long, delta: Int) = write { library.movePiece(playlistId, pieceId, delta) }

    /** A drag in the playlist ended with its pieces in [orderedIds]' order. */
    fun reorderPlaylist(playlistId: Long, orderedIds: List<Long>) = write { library.reorderPlaylist(playlistId, orderedIds) }

    /**
     * A composer's pieces of the genre shown, by title, for Play all and Shuffle on the composer's tile. Nothing plays
     * if they can't be read.
     */
    fun composerPieces(composerKey: String, then: (List<Long>) -> Unit) {
        val scope = selection.value?.scope ?: LibraryScope.All
        viewModelScope.launch {
            val ids = try {
                library.byComposer(composerKey, scope).first().map { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Log.w(TAG, "A composer's pieces couldn't be read", e)
                return@launch
            }
            then(ids)
        }
    }

    /**
     * The listing for [sel] of its genre (v1.14 — M37): the pieces, favorites, recent ones and a search of that genre;
     * the composers with a piece of it, each page that genre's pieces; the playlists that show under it, each opening
     * whole; the channels listed under it.
     */
    private fun listing(sel: Selection, query: String): Flow<Listing> {
        val key = TextKeys.fold(query)
        val scope = sel.scope
        return when (val group = sel.group) {
            is Group.Playlist -> combine(library.inPlaylist(group.id), library.playlists()) { pieces, all ->
                Listing.Pieces(pieces.matching(key), all.firstOrNull { it.id == group.id })
            }
            is Group.Composer -> library.byComposer(group.key, scope).map { Listing.Pieces(it.matching(key)) }
            Group.Channels -> channels.map { all -> Listing.Channels(GenreListing.channels(all.orEmpty(), scope).filter { key in TextKeys.fold(it.name) }) }
            null -> when (sel.category) {
                Category.All -> (if (key.isEmpty()) library.all(scope) else library.search(query, scope)).map { Listing.Pieces(it) }
                Category.Favorites -> library.favorites(scope).map { Listing.Pieces(it.matching(key)) }
                Category.Recent -> library.recent(scope).map { Listing.Pieces(it.matching(key)) }
                // The channels' row stands above the playlists; a search narrows the playlists alone. Their order is the
                // person's choice (v1.10.1 — M28, D6); the built-in lists' order is read off the main thread.
                Category.Playlists -> combine(library.playlists(scope), channels, playlistSort) { all, cards, sort ->
                    Listing.Playlists(
                        PlaylistShelf.shown(all, sort, builtInOrder()).filter { key in TextKeys.fold(it.name) },
                        if (key.isEmpty()) GenreListing.channels(cards.orEmpty(), scope) else emptyList(),
                        sort,
                    )
                }.flowOn(Dispatchers.Default)
                // Each name's genre for its tile's Move, counted over all its pieces (whatever the genre shown).
                Category.Composers -> combine(library.composers(scope), library.all()) { all, pieces ->
                    Listing.Composers(all.filter { key in TextKeys.fold(it.name) }, GenreListing.byKey(pieces))
                }.flowOn(Dispatchers.Default)
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

/** What the genre chosen does to a listing (v1.14 — M37). Pure. */
internal object GenreListing {
    /** The channels listed under [scope]: every one under All, else those of its genre (so Everything only under All). */
    fun channels(all: List<ChannelSummary>, scope: LibraryScope): List<ChannelSummary> {
        val genre = scope.genre ?: return all
        return all.filter { it.genre == genre }
    }

    /** Each composer's or artist's genre, the one most of their [pieces] have; never the blank name or a made-here one, nor a tie. */
    fun byKey(pieces: List<PieceEntity>): Map<String, Int> {
        val counts = HashMap<String, IntArray>()
        for (piece in pieces) {
            val key = piece.composerKey
            if (key.isBlank() || Genres.madeHere(key)) continue
            val count = counts.getOrPut(key) { IntArray(2) }
            when (piece.genre) {
                Genres.CLASSICAL -> count[0]++
                Genres.MODERN -> count[1]++
            }
        }
        return buildMap { counts.forEach { (key, count) -> Genres.majority(count[0], count[1])?.let { put(key, it) } } }
    }
}

/**
 * The Playlists grid: every playlist in the order chosen ([PlaylistOrder.listing], v1.10.1 — M28: newest
 * first, the built-in ones after them in their fixed order [builtInOrder]; or, by name, the built-in ones
 * first in the order they were made, then the rest by name). A built-in playlist with nothing in it is not
 * shown at all: the library holds none of its pieces yet.
 */
internal object PlaylistShelf {
    fun shown(all: List<PlaylistSummary>, sort: PlaylistSort = PlaylistSort.NAME, builtInOrder: List<String> = emptyList()): List<PlaylistSummary> =
        PlaylistOrder.listing(all, sort, builtInOrder).filter { !it.builtIn || it.pieceCount > 0 }
}
