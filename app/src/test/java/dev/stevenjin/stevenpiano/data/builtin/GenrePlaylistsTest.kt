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
import dev.stevenjin.stevenpiano.data.PlaylistOrder
import dev.stevenjin.stevenpiano.data.PlaylistSort
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.PlaylistRef
import dev.stevenjin.stevenpiano.data.db.PlaylistSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The genre playlists (v1.37 — M73): Apple's genres sorted as Apple Music does, two of them into Steven's own playlists. */
class GenrePlaylistsTest {
    private val kpop = PlaylistRef(3, "K-pop", builtIn = false, builtInKey = null)
    private val houseAndDance = PlaylistRef(4, "House & Dance", builtIn = false, builtInKey = null)
    private val steven = listOf(kpop, houseAndDance, PlaylistRef(5, "Road trip", false, null), PlaylistRef(6, "Popular", true, "popular"))

    private fun modern(id: Long, title: String, genre: String?): PieceEntity =
        LibraryFixture.piece(id, title, "Some Artist").copy(genre = Genres.MODERN, appleGenre = genre)

    private val library = listOf(
        modern(1, "Dynamite", "K-Pop"),
        modern(2, "Titanium", "Dance"),
        modern(3, "Levels", "Electronic"),
        modern(4, "Where Are U Now", "House"),
        modern(5, "Perfect", "Pop"),
        modern(6, "Shape of You", "Pop"),
        modern(7, "Lose Yourself", "Hip-Hop/Rap"),
        modern(8, "Time", "Soundtrack"),
        modern(9, "A fragment", ""),
        modern(10, "Not looked up yet", null),
        LibraryFixture.piece(11, "Clair de lune", "Debussy").copy(appleGenre = "Classical"),
        modern(12, "Bad", "pop"),
    )

    @Test
    fun `names compare without case, spaces, punctuation or and`() {
        for (name in listOf("K-pop", "KPOP", "k pop", "K–Pop")) assertEquals(name, PlaylistNames.KPOP, PlaylistNames.fold(name))
        for (name in listOf("House & Dance", "house and dance", "house/dance", "HOUSE  &  DANCE")) assertEquals(name, PlaylistNames.HOUSE_AND_DANCE, PlaylistNames.fold(name))
    }

    @Test
    fun `K-Pop goes to K-pop, and Dance, House, Electronic and Electronica to House & Dance`() {
        assertEquals(3L, GenrePlaylists.absorber("K-Pop", steven))
        for (genre in listOf("Dance", "House", "Electronic", "Electronica")) assertEquals(genre, 4L, GenrePlaylists.absorber(genre, steven))
        assertNull(GenrePlaylists.absorber("Pop", steven))
        assertNull("only the person's own playlist takes a genre in", GenrePlaylists.absorber("K-Pop", listOf(PlaylistRef(9, "K-Pop", true, "genre:kpop"))))

        val held = GenrePlaylists.plan(library, steven).flatMap { it.pieceIds }
        assertEquals("in Steven's own playlists, not in a genre list", emptySet<Long>(), held.intersect(setOf(1L, 2L, 3L, 4L)))
    }

    @Test
    fun `other genres get their own named playlists, no genre goes to Other, last, and classical pieces never`() {
        val lists = GenrePlaylists.plan(library, steven)
        assertEquals(
            listOf(
                GenreList("genre:pop", "Pop", listOf(12L, 5L, 6L)),   // by title; "pop" counted with "Pop", spelt as most are
                GenreList("genre:hiphoprap", "Hip-Hop/Rap", listOf(7L)),
                GenreList("genre:soundtrack", "Soundtrack", listOf(8L)),
                GenreList(GenrePlaylists.OTHER_KEY, "Other", listOf(9L)),
            ),
            lists,
        )
        // Without Steven's two playlists, their genres have lists of their own.
        val alone = GenrePlaylists.plan(library, emptyList()).map { it.name }
        assertEquals(listOf("Pop", "Dance", "Electronic", "Hip-Hop/Rap", "House", "K-Pop", "Soundtrack", "Other"), alone)
    }

    @Test
    fun `a refresh makes the lists, removes a genre no piece has any more, and the shelf puts them after Steven's own by size`() = runBlocking {
        val store = FakeStore(library, steven + PlaylistRef(30, "Country", true, "genre:country"))
        GenrePlaylists.refresh(store)
        assertEquals(listOf(30L), store.removed)
        assertEquals(listOf(12L, 5L, 6L), store.contents()["genre:pop"])
        assertEquals(listOf(9L), store.contents()[GenrePlaylists.OTHER_KEY])

        val shelf = listOf(   // as the library lists them: by name
            PlaylistSummary(7, "K-pop", true, 12, 0),
            PlaylistSummary(40, "Other", false, 30, 0, builtIn = true, builtInKey = GenrePlaylists.OTHER_KEY),
            PlaylistSummary(42, "Pop", false, 9, 0, builtIn = true, builtInKey = "genre:pop"),
            PlaylistSummary(6, "Popular", false, 17, 0, builtIn = true, builtInKey = "popular"),
            PlaylistSummary(5, "Road trip", false, 3, 0),
            PlaylistSummary(41, "Soundtrack", false, 2, 0, builtIn = true, builtInKey = "genre:soundtrack"),
        )
        assertEquals(listOf(7L, 5L, 42L, 41L, 40L, 6L), PlaylistOrder.listing(shelf, PlaylistSort.NEWEST, listOf("popular")).map { it.id })
        assertEquals(listOf(6L, 7L, 5L, 42L, 41L, 40L), PlaylistOrder.listing(shelf, PlaylistSort.NAME, listOf("popular")).map { it.id })
    }

    /** The library's side of a refresh, in memory. */
    private class FakeStore(val pieces: List<PieceEntity>, val refs: List<PlaylistRef>) : GenreStore {
        private val keys = LinkedHashMap<String, Long>()
        private val held = HashMap<Long, List<Long>>()
        val removed = ArrayList<Long>()

        override suspend fun allPieces(): List<PieceEntity> = pieces

        override suspend fun playlistRefs(): List<PlaylistRef> = refs

        override suspend fun removeBuiltIn(id: Long) {
            removed += id
        }

        override suspend fun builtInId(key: String): Long? = keys[key]

        override suspend fun ensureBuiltIn(key: String, name: String): Long = keys.getOrPut(key) { 100L + keys.size }

        override suspend fun setPlaylistPieces(id: Long, orderedIds: List<Long>) {
            held[id] = orderedIds
        }

        fun contents(): Map<String, List<Long>> = keys.mapValues { (_, id) -> held[id].orEmpty() }
    }
}
