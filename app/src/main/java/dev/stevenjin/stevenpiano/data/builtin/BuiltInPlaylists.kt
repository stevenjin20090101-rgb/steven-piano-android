// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.builtin

import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.PlaylistPieceEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One piece a built-in list asks for: a composer (the library's `composerKey`, one of
 * [composers]: several for a piece known by its arranger too, "" for pieces whose composer the
 * library does not know), a [title] pattern found in the folded title (`PieceEntity.titleKey`,
 * which is `TextKeys.fold(title)`), and, when given, the INDEX.csv [collection] exactly.
 */
class Matcher(val composers: Set<String>, val title: Regex, val collection: String? = null) {
    fun accepts(piece: PieceEntity): Boolean =
        piece.composerKey in composers && (collection == null || piece.collection == collection) && title.containsMatchIn(piece.titleKey)
}

/**
 * A built-in playlist (DESIGN.md › v1.5 — M17): Popular, Recognisable, Epic on piano. [key] finds
 * its row in the library (`builtInKey`), never its [name].
 */
class BuiltInList(val key: String, val name: String, val matchers: List<Matcher>) {
    /**
     * The pieces of [pieces] this list holds, in its order: each matcher's hits in turn, at most
     * [MAX_HITS] of them (the first by folded title, then by id), a piece only at its first place.
     * Pure: what the library holds decides, and the same library always gives the same list.
     */
    fun matches(pieces: List<PieceEntity>): List<Long> {
        val byComposer = pieces.groupBy { it.composerKey }
        val found = LinkedHashSet<Long>()
        for (matcher in matchers) {
            matcher.composers.asSequence()
                .flatMap { byComposer[it].orEmpty().asSequence() }
                .filter(matcher::accepts)
                .sortedWith(BY_TITLE)
                .take(MAX_HITS)
                .forEach { found += it.id }
        }
        return found.toList()
    }

    companion object {
        /** A matcher adds at most this many pieces, so four recordings of one étude never swamp a list. */
        const val MAX_HITS = 4

        private val BY_TITLE = compareBy<PieceEntity>({ it.titleKey }, { it.id })
    }
}

/** What refreshing the built-in lists needs from the library (LibraryRepository; a fake in tests). */
interface BuiltInStore {
    /** Every piece in the library, read once. */
    suspend fun allPieces(): List<PieceEntity>

    /** The id of the built-in playlist [key], or null while there is none. */
    suspend fun builtInId(key: String): Long?

    /** The built-in playlist [key], made if needed and named [name] (or beside it, see [builtInName]). Returns its id. */
    suspend fun ensureBuiltIn(key: String, name: String): Long

    /** The playlist [id] holds exactly [orderedIds], in that order (one transaction). */
    suspend fun setPlaylistPieces(id: Long, orderedIds: List<Long>)
}

/**
 * The built-in playlists, refreshed from the library: when the app starts, after an import that
 * brought pieces in, and two seconds after pieces or playlists stop being renamed (AppGraph). A
 * list is made the first time the library holds something for it; one that later matches nothing
 * is kept, empty (the Library does not show an empty built-in). Refreshes never overlap.
 */
class BuiltInPlaylists(val lists: List<BuiltInList>) {
    private val running = Mutex()

    suspend fun refresh(store: BuiltInStore) = running.withLock {
        val pieces = store.allPieces()
        for (list in lists) {
            val ids = list.matches(pieces)
            if (ids.isEmpty() && store.builtInId(list.key) == null) continue
            store.setPlaylistPieces(store.ensureBuiltIn(list.key, list.name), ids)
        }
    }

    /** The lists' keys in the catalogue's order. */
    val keys: List<String> get() = lists.map { it.key }

    companion object {
        /**
         * The name a built-in list [name] takes: its own, or when another playlist has it (the
         * person made a "Popular" of their own), "Popular · built in", then "Popular · built in 2"…
         * [taken] says whether a name belongs to another playlist.
         */
        suspend fun builtInName(name: String, taken: suspend (String) -> Boolean): String {
            if (!taken(name)) return name
            var n = 1
            while (true) {
                val candidate = if (n == 1) "$name$BESIDE" else "$name$BESIDE $n"
                if (!taken(candidate)) return candidate
                n++
            }
        }

        /** What a built-in list's name gains when the person has a playlist of that name. */
        const val BESIDE = " · built in"

        /**
         * The links that make playlist [playlistId] hold [ordered] (distinct ids, in order) when it
         * holds [current] (piece to position) now: which go, which come (at their positions) and
         * which move. Pure; the library writes the result in one transaction.
         */
        fun linkChanges(playlistId: Long, current: Map<Long, Int>, ordered: List<Long>, now: Long): LinkChanges {
            val wanted = ordered.distinct()
            val keep = wanted.toHashSet()
            val removed = current.keys.filter { it !in keep }
            val added = ArrayList<PlaylistPieceEntity>()
            val moved = ArrayList<Pair<Long, Int>>()
            wanted.forEachIndexed { position, pieceId ->
                when (current[pieceId]) {
                    null -> added += PlaylistPieceEntity(playlistId, pieceId, now, position)
                    position -> Unit
                    else -> moved += pieceId to position
                }
            }
            return LinkChanges(removed, added, moved)
        }
    }
}

/** What [BuiltInPlaylists.linkChanges] found: links to remove, to add, and pieces to move to a new position. */
data class LinkChanges(val removed: List<Long>, val added: List<PlaylistPieceEntity>, val moved: List<Pair<Long, Int>>) {
    val none: Boolean get() = removed.isEmpty() && added.isEmpty() && moved.isEmpty()
}
