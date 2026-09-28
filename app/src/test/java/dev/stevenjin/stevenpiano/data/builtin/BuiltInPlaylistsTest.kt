// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.builtin

import dev.stevenjin.stevenpiano.data.builtin.LibraryFixture.piece
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.PlaylistPieceEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in playlists (DESIGN.md › v1.5 — M17): the catalogue as it ships, the matchers'
 * rules (folded titles, the cap of four by title, the order, one place per piece, collections,
 * several or unknown composers), the refresh against a library that changes, the names a
 * built-in list takes beside the person's own, and the lists tuned against Steven's library
 * (`library_titles.csv`) and the Epic zip's INDEX: Popular and Recognisable find at least 15
 * pieces each there, Epic at least 30, every piece of the zip is found in a library made from it,
 * and no matcher ever adds more than four.
 */
class BuiltInPlaylistsTest {
    private val catalogue = BuiltInCatalogue.parse(LibraryFixture.asset(BuiltInCatalogue.ASSET).readText())

    private fun list(key: String) = catalogue.first { it.key == key }

    @Test
    fun `the catalogue holds Popular, Recognisable and Epic on piano, in that order`() {
        assertEquals(listOf("popular", "recognisable", "epic"), catalogue.map { it.key })
        assertEquals(listOf("Popular", "Recognisable", "Epic on piano"), catalogue.map { it.name })
        assertEquals(listOf(28, 25, 45), catalogue.map { it.matchers.size })
    }

    @Test
    fun `every pattern is written for folded titles`() {
        for (matcher in catalogue.flatMap { it.matchers }) {
            val pattern = matcher.title.pattern
            // Folded titles have no accents and no capitals; the em dash of piano-midi.de's titles stays.
            assertTrue(pattern, pattern.all { it.code < 128 || it == '—' })
            assertTrue(pattern, pattern.indices.none { pattern[it].isUpperCase() && (it == 0 || pattern[it - 1] != '\\') })
            assertTrue(pattern, RegexOption.IGNORE_CASE in matcher.title.options)
            assertTrue(matcher.composers.all { it == it.lowercase() && it.trim() == it })
        }
    }

    @Test
    fun `titles and composers are matched folded, as the library keys them`() {
        val list = BuiltInList("t", "Test", listOf(Matcher(setOf("chopin"), Regex("etude op\\. 10 no\\. 12", RegexOption.IGNORE_CASE))))
        val pieces = listOf(
            piece(1, "Étude Op. 10 No. 12", "Frédéric Chopin"),
            piece(2, "ETÜDE OP. 10 NO. 12", "chopin"),
            piece(3, "Étude Op. 10 No. 12", "Franz Liszt"),
            piece(4, "Étude Op. 10 No. 1", "Chopin, F"),
        )
        assertEquals(listOf(1L, 2L), list.matches(pieces))
    }

    @Test
    fun `a matcher adds at most four pieces, the first by folded title`() {
        val list = BuiltInList("t", "Test", listOf(Matcher(setOf("beethoven"), Regex("elise"))))
        val pieces = listOf(
            piece(1, "Für Elise (arr.)", "Beethoven"),
            piece(2, "Für Elise", "Beethoven"),
            piece(3, "Fur Elise [3]", "Beethoven"),
            piece(4, "Für Elise", "Beethoven"),
            piece(5, "Bagatelle 'Für Elise'", "Beethoven"),
            piece(6, "Für Elise [2]", "Beethoven"),
        )
        assertEquals(listOf(5L, 2L, 4L, 1L), list.matches(pieces))
        assertEquals(BuiltInList.MAX_HITS, list.matches(pieces).size)
    }

    @Test
    fun `a list keeps its matchers' order and holds each piece once`() {
        val list = BuiltInList(
            "t",
            "Test",
            listOf(
                Matcher(setOf("debussy"), Regex("clair de lune")),
                Matcher(setOf("chopin"), Regex("nocturne")),
                Matcher(setOf("debussy"), Regex("bergamasque")),
            ),
        )
        val pieces = listOf(
            piece(1, "Nocturne Op. 9 No. 2", "Chopin"),
            piece(2, "Suite bergamasque — Menuet", "Debussy"),
            piece(3, "Suite bergamasque — Clair de Lune", "Debussy"),
        )
        assertEquals(listOf(3L, 1L, 2L), list.matches(pieces))
        assertEquals(emptyList<Long>(), list.matches(emptyList()))
    }

    @Test
    fun `a collection narrows a matcher, and composers may be several or unknown`() {
        val pieces = listOf(
            piece(1, "Nocturne Op. 9 No. 2", "Chopin", collection = "piano-midi.de"),
            piece(2, "Nocturne Op. 9 No. 2", "Chopin", collection = "maestro"),
            piece(3, "The Entertainer", "", collection = "mutopia"),
            piece(4, "The Entertainer", "Scott Joplin"),
            piece(5, "Ave Maria", "Franz Schubert Franz Liszt"),
            piece(6, "Ave Maria", "Liszt"),
        )
        assertEquals(listOf(1L), BuiltInList("t", "T", listOf(Matcher(setOf("chopin"), Regex("nocturne"), "piano-midi.de"))).matches(pieces))
        assertEquals(listOf(3L, 4L), BuiltInList("t", "T", listOf(Matcher(setOf("joplin", ""), Regex("entertainer")))).matches(pieces))
        assertEquals(listOf(5L, 6L), BuiltInList("t", "T", listOf(Matcher(setOf("schubert", "liszt"), Regex("ave maria")))).matches(pieces))
    }

    @Test
    fun `a refresh makes a list once the library holds something for it, then follows the library`() = runBlocking {
        val lists = BuiltInPlaylists(
            listOf(
                BuiltInList("popular", "Popular", listOf(Matcher(setOf("beethoven"), Regex("elise")))),
                BuiltInList("epic", "Epic on piano", listOf(Matcher(setOf("liszt"), Regex("campanella")))),
            ),
        )
        val store = FakeStore(listOf(piece(1, "Für Elise", "Beethoven"), piece(2, "Clair de lune", "Debussy")))
        lists.refresh(store)
        assertEquals(mapOf("popular" to listOf(1L)), store.contents())   // nothing for Epic yet: no empty list is made

        store.pieces = store.pieces + piece(3, "La Campanella", "Liszt") + piece(4, "Für Elise (arr.)", "Beethoven")
        lists.refresh(store)
        assertEquals(mapOf("popular" to listOf(1L, 4L), "epic" to listOf(3L)), store.contents())

        store.pieces = store.pieces.filter { it.id != 3L && it.id != 1L }   // deleted, renamed away
        lists.refresh(store)
        assertEquals(mapOf("popular" to listOf(4L), "epic" to emptyList()), store.contents())   // kept, empty: the Library hides it
        assertEquals(2, store.made)
    }

    @Test
    fun `a built-in list takes its own name, or stands beside the person's`() = runBlocking {
        assertEquals("Popular", BuiltInPlaylists.builtInName("Popular") { false })
        assertEquals("Popular · built in", BuiltInPlaylists.builtInName("Popular") { it == "Popular" })
        assertEquals("Popular · built in 2", BuiltInPlaylists.builtInName("Popular") { it == "Popular" || it == "Popular · built in" })
    }

    @Test
    fun `setting a playlist's pieces removes, adds and moves only what differs`() {
        val changes = BuiltInPlaylists.linkChanges(7, mapOf(1L to 0, 2L to 1, 3L to 2), listOf(3L, 1L, 4L, 3L), now = 99)
        assertEquals(listOf(2L), changes.removed)
        assertEquals(listOf(PlaylistPieceEntity(7, 4, 99, 2)), changes.added)
        assertEquals(listOf(3L to 0, 1L to 1), changes.moved)
        assertTrue(BuiltInPlaylists.linkChanges(7, mapOf(1L to 0, 2L to 1), listOf(1L, 2L), now = 0).none)
    }

    @Test
    fun `in Steven's library Popular and Recognisable find at least 15 pieces each, Epic at least 30`() {
        val library = LibraryFixture.corpus
        assertEquals(1_727, library.size)
        val found = catalogue.associate { it.key to it.matches(library) }
        println("Built-in lists in the corpus fixture: " + found.entries.joinToString { (key, ids) -> "$key ${ids.size}" })
        for (list in catalogue) {
            val silent = list.matchers.count { m -> library.none(m::accepts) }
            println("  ${list.key}: ${list.matchers.size} matchers, $silent find nothing in this library")
        }
        assertTrue("Popular ${found["popular"]?.size}", found.getValue("popular").size >= 15)
        assertTrue("Recognisable ${found["recognisable"]?.size}", found.getValue("recognisable").size >= 15)
        assertTrue("Epic ${found["epic"]?.size}", found.getValue("epic").size >= 30)
        found.values.forEach { ids -> assertEquals(ids.size, ids.toSet().size) }
    }

    @Test
    fun `no matcher adds more than four pieces`() {
        for (library in listOf(LibraryFixture.corpus, LibraryFixture.epicZip, LibraryFixture.corpus + LibraryFixture.epicZip)) {
            for (list in catalogue) {
                for (matcher in list.matchers) {
                    val added = BuiltInList(list.key, list.name, listOf(matcher)).matches(library)
                    assertTrue("${list.key} /${matcher.title.pattern}/ adds ${added.size}", added.size <= BuiltInList.MAX_HITS)
                }
            }
        }
    }

    @Test
    fun `every piece of the Epic zip is found in a library made from it, in the zip's order`() {
        val zip = LibraryFixture.epicZip
        assertEquals(45, zip.size)
        assertEquals(zip.map { it.id }, list("epic").matches(zip))
    }

    @Test
    fun `the Popular and Recognisable matchers find nothing but their own pieces in the zip`() {
        val zip = LibraryFixture.epicZip
        val titles = zip.associate { it.id to it.title }
        assertEquals(
            listOf("Für Elise", "Moonlight Sonata, 1st movement", "Clair de lune", "Prelude Op. 28 No. 15 \"Raindrop\"", "Liebestraum No. 3", "Fantaisie-Impromptu, Op. 66", "Pathétique Sonata, 2nd movement"),
            list("popular").matches(zip).map(titles::getValue),
        )
        assertEquals(13, list("recognisable").matches(zip).size)
    }

    /** The library's side of a refresh, in memory. */
    private class FakeStore(var pieces: List<PieceEntity>) : BuiltInStore {
        private val keys = LinkedHashMap<String, Long>()
        private val held = HashMap<Long, List<Long>>()
        var made = 0

        override suspend fun allPieces(): List<PieceEntity> = pieces

        override suspend fun builtInId(key: String): Long? = keys[key]

        override suspend fun ensureBuiltIn(key: String, name: String): Long = keys.getOrPut(key) { made++; 100L + made }

        override suspend fun setPlaylistPieces(id: Long, orderedIds: List<Long>) {
            held[id] = orderedIds
        }

        fun contents(): Map<String, List<Long>> = keys.mapValues { (_, id) -> held[id].orEmpty() }
    }
}
