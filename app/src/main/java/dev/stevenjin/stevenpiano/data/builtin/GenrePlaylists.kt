// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.builtin

import dev.stevenjin.stevenpiano.data.Genres
import dev.stevenjin.stevenpiano.data.PlaylistNames
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.PlaylistRef
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the genre playlists need of the library beyond the built-in lists' (LibraryRepository; a fake in tests). */
interface GenreStore : BuiltInStore {
    /** Every playlist's id, name and key, read once. */
    suspend fun playlistRefs(): List<PlaylistRef>

    /** Built-in playlist [id] goes (a genre no piece has any more); its pieces stay in the library. */
    suspend fun removeBuiltIn(id: Long)
}

/** One genre playlist: its built-in [key], its [name] as Apple names the genre, and its pieces in order. */
data class GenreList(val key: String, val name: String, val pieceIds: List<Long>)

/**
 * The genre playlists (v1.37 — M73: the library sorted like Apple Music). Every Modern piece's genre as Apple's catalogue
 * names it (`PieceEntity.appleGenre`, learnt by the album-cover lookup) gives it a built-in playlist of that name ("Pop",
 * "Hip-Hop/Rap", "Electronic", "Soundtrack"…), its pieces by title; a piece the catalogue matched nothing for goes in
 * "Other". A Classical piece, one made here, and one not looked up yet are in none. Two of Steven's own playlists take
 * their genres in ([absorber]): Apple's K-Pop goes to his "K-pop", and Dance, House, Electronic and Electronica to his
 * "House & Dance" (names compared as [PlaylistNames.fold] does); their names and their own songs stay, and a song joins
 * them when its genre arrives (`LibraryRepository.setAppleGenre`), never again after the person takes it out. Without such
 * a playlist the genre has a list of its own. Refreshed with the built-in lists and a few seconds after genres arrive; a
 * list whose genre no piece has any more goes. Refreshes never overlap.
 */
object GenrePlaylists {
    /** A genre list's built-in key: this and the genre's folded name ("genre:hiphoprap"). */
    const val KEY_PREFIX = "genre:"

    /** The list of pieces the catalogue matched nothing for. */
    const val OTHER_KEY = "genre-other"
    const val OTHER_NAME = "Other"

    /** Apple's genres (folded) that one of Steven's playlists takes in, and that playlist's folded name. */
    private val ABSORBED: Map<String, String> = mapOf(
        "kpop" to PlaylistNames.KPOP,
        "dance" to PlaylistNames.HOUSE_AND_DANCE,
        "house" to PlaylistNames.HOUSE_AND_DANCE,
        "electronic" to PlaylistNames.HOUSE_AND_DANCE,
        "electronica" to PlaylistNames.HOUSE_AND_DANCE,
    )

    private val running = Mutex()

    /** Whether built-in [key] is a genre list's. */
    fun isGenreKey(key: String?): Boolean = key != null && (key == OTHER_KEY || key.startsWith(KEY_PREFIX))

    /**
     * The person's playlist (among [playlists], never a built-in one) that takes Apple's genre [appleGenre] in: "K-pop" for
     * K-Pop, "House & Dance" for Dance, House, Electronic and Electronica, as names compare; the oldest of two alike. Null
     * for any other genre, or when there is no such playlist.
     */
    fun absorber(appleGenre: String, playlists: List<PlaylistRef>): Long? {
        val target = ABSORBED[PlaylistNames.fold(appleGenre)] ?: return null
        return playlists.filter { !it.builtIn && PlaylistNames.fold(it.name) == target }.minOfOrNull { it.id }
    }

    /**
     * The genre lists [pieces] make, given [playlists] (which say what is absorbed): one a genre, each piece once, by
     * title (then id); the largest first and "Other" last. Pure.
     */
    fun plan(pieces: List<PieceEntity>, playlists: List<PlaylistRef>): List<GenreList> {
        val members = LinkedHashMap<String, MutableList<Long>>()
        val spellings = HashMap<String, MutableMap<String, Int>>()
        for (piece in pieces.sortedWith(compareBy({ it.titleKey }, { it.id }))) {
            if (piece.genre != Genres.MODERN) continue
            val genre = piece.appleGenre?.trim() ?: continue   // not looked up yet
            val folded = PlaylistNames.fold(genre)
            val key = when {
                folded.isEmpty() -> OTHER_KEY
                absorber(genre, playlists) != null -> continue   // in Steven's own playlist
                else -> KEY_PREFIX + folded
            }
            members.getOrPut(key) { ArrayList() } += piece.id
            if (key != OTHER_KEY) spellings.getOrPut(key) { HashMap() }.let { it[genre] = (it[genre] ?: 0) + 1 }
        }
        return members.map { (key, ids) -> GenreList(key, if (key == OTHER_KEY) OTHER_NAME else spelling(spellings.getValue(key)), ids) }
            .sortedWith(compareBy({ it.key == OTHER_KEY }, { -it.pieceIds.size }, { it.name.lowercase() }))
    }

    /** The genre lists as the library is now: those [plan] gives set, those it no longer gives gone. Returns them. */
    suspend fun refresh(store: GenreStore): List<GenreList> = running.withLock {
        val playlists = store.playlistRefs()
        val lists = plan(store.allPieces(), playlists)
        val wanted = lists.mapTo(HashSet()) { it.key }
        playlists.filter { it.builtIn && isGenreKey(it.builtInKey) && it.builtInKey !in wanted }.forEach { store.removeBuiltIn(it.id) }
        for (list in lists) store.setPlaylistPieces(store.ensureBuiltIn(list.key, list.name), list.pieceIds)
        lists
    }

    /** The spelling most pieces give ("Hip-Hop/Rap"), the first in order on a tie. */
    private fun spelling(counts: Map<String, Int>): String =
        counts.entries.sortedWith(compareBy({ -it.value }, { it.key })).first().key
}
