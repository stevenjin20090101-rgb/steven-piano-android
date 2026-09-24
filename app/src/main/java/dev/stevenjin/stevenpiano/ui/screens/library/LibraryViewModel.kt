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
import dev.stevenjin.stevenpiano.data.db.CollectionSummary
import dev.stevenjin.stevenpiano.data.db.ComposerGroup
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The chips, Synthesia-style. */
enum class Category(val label: String) {
    All("All"),
    Collections("Collections"),
    Composers("Composers"),
    Favorites("Favorites"),
    Recent("Recent"),
}

/** A second-level list: one collection's pieces, or one composer's. */
sealed interface Group {
    val name: String

    data class Collection(val id: Long, override val name: String) : Group

    data class Composer(val key: String, override val name: String) : Group
}

/** What the list shows under the chips. */
sealed interface Listing {
    val isEmpty: Boolean

    data class Pieces(val pieces: List<PieceEntity>) : Listing {
        override val isEmpty: Boolean get() = pieces.isEmpty()
    }

    data class Collections(val collections: List<CollectionSummary>) : Listing {
        override val isEmpty: Boolean get() = collections.isEmpty()
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
) {
    /** Nothing imported yet. */
    val empty: Boolean get() = loaded && pieceCount == 0
}

/**
 * The Library tab: the chosen category or group, filtered by the search field (title and
 * composer, case and accents ignored), and the long-press edits. Edits run in [writes], the
 * app's scope, so leaving the tab never cuts one short.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val library: LibraryRepository,
    val importProgress: StateFlow<ImportProgress>,
    private val writes: CoroutineScope,
) : ViewModel() {
    private data class Selection(val category: Category, val group: Group?)

    private val selection = MutableStateFlow(Selection(Category.All, null))

    /** The search field's text. Compose state, so typing never waits on the database. */
    var query by mutableStateOf("")
        private set

    /** The finished import whose summary was dismissed. */
    var dismissedImport by mutableStateOf<ImportProgress?>(null)
        private set

    val state: StateFlow<LibraryState> =
        combine(selection, snapshotFlow { query.trim() }.distinctUntilChanged()) { sel, q -> sel to q }
            .flatMapLatest { (sel, q) -> listing(sel, q).map { LibraryState(sel.category, sel.group, it, loaded = true) } }
            .combine(library.count()) { state, count -> state.copy(pieceCount = count) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryState())

    val collections: Flow<List<CollectionSummary>> get() = library.collections()

    fun membershipOf(pieceId: Long): Flow<List<Long>> = library.collectionIdsOf(pieceId)

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

    fun delete(piece: PieceEntity) = write { library.delete(piece.id) }

    fun setMembership(collectionId: Long, pieceId: Long, member: Boolean) = write {
        if (member) library.addToCollection(collectionId, pieceId) else library.removeFromCollection(collectionId, pieceId)
    }

    /** Makes the collection (or finds the one with that name) and puts the piece in it. */
    fun addToNewCollection(name: String, pieceId: Long) = write {
        library.addToCollection(library.createCollection(name), pieceId)
    }

    fun renameCollection(id: Long, name: String) = write { library.renameCollection(id, name) }

    fun deleteCollection(id: Long) = write { library.deleteCollection(id) }

    private fun listing(sel: Selection, query: String): Flow<Listing> {
        val key = TextKeys.fold(query)
        return when (val group = sel.group) {
            is Group.Collection -> library.inCollection(group.id).map { Listing.Pieces(it.matching(key)) }
            is Group.Composer -> library.byComposer(group.key).map { Listing.Pieces(it.matching(key)) }
            null -> when (sel.category) {
                Category.All -> (if (key.isEmpty()) library.all() else library.search(query)).map { Listing.Pieces(it) }
                Category.Favorites -> library.favorites().map { Listing.Pieces(it.matching(key)) }
                Category.Recent -> library.recent().map { Listing.Pieces(it.matching(key)) }
                Category.Collections -> library.collections().map { all -> Listing.Collections(all.filter { key in TextKeys.fold(it.name) }) }
                Category.Composers -> library.composers().map { all -> Listing.Composers(all.filter { key in TextKeys.fold(it.name) }) }
            }
        }
    }

    private fun List<PieceEntity>.matching(key: String): List<PieceEntity> = if (key.isEmpty()) this else filter { key in it.searchText }

    private fun write(block: suspend () -> Unit) {
        writes.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {   // e.g. a name another collection already has
                Log.w(TAG, "Library edit failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "Library"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
