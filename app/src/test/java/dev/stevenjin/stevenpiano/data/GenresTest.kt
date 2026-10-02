// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data

import dev.stevenjin.stevenpiano.data.Genres.CLASSICAL
import dev.stevenjin.stevenpiano.data.Genres.MODERN
import dev.stevenjin.stevenpiano.data.Genres.NONE
import dev.stevenjin.stevenpiano.data.builtin.LibraryFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classical and Modern (v1.14 — M37): the one rule on the upgrade's cases (SchemaV5Test runs the same rows through the
 * SQL), every composer a 1.10 library holds Classical by key, and the playlists' majority.
 */
class GenresTest {
    @Test
    fun `the rule - made here, the artist's genre, a classical composer, the pack, else Modern`() {
        val none = emptyMap<String, Int>()
        assertEquals(CLASSICAL, Genres.of("medtner", "maestro-classical-performances", none))
        assertEquals(CLASSICAL, Genres.of("debussy", null, none))
        assertEquals(MODERN, Genres.of("ed sheeran", null, none))
        assertEquals(MODERN, Genres.of("", null, none))
        assertEquals("Mutopia's pieces name no composer", CLASSICAL, Genres.of("", "mutopia-public-domain", none))
        assertEquals(NONE, Genres.of("made in studio", null, none))
        assertEquals(NONE, Genres.of("recorded live", "piano-midi.de", mapOf("recorded live" to CLASSICAL)))
        assertEquals(CLASSICAL, Genres.of("medtner", null, none))
        assertEquals(CLASSICAL, Genres.of("bach cpe", "piano-midi.de", none))
        assertEquals("a canonical surname and its initials", CLASSICAL, Genres.of("bach jc", null, none))
        assertEquals("a first name that is a pack composer's surname", MODERN, Genres.of("adam levine", null, none))
        assertEquals(MODERN, Genres.of("adam ant", null, none))
        assertEquals(MODERN, Genres.of("bach j s", null, none))
        assertEquals(CLASSICAL, Genres.of("anonymous", "piano-midi.de", none))
        assertEquals(MODERN, Genres.of("anonymous", null, none))
        assertEquals("the artist's other pieces teach it", CLASSICAL, Genres.of("anonymous", null, mapOf("anonymous" to CLASSICAL)))
        assertEquals(MODERN, Genres.of("stay", null, none))
        assertEquals("a classical composer the app doesn't know", MODERN, Genres.of("kapustin", null, none))
        assertEquals(CLASSICAL, Genres.of("traditional", null, none))
        assertEquals("Steven's move comes first", MODERN, Genres.of("debussy", null, mapOf("debussy" to MODERN)))
        assertEquals("never for the blank key", CLASSICAL, Genres.of("", "piano-midi.de", mapOf("" to MODERN)))
        assertNull(Genres.strong("ed sheeran", null))
        assertEquals(NONE, Genres.strong("made in studio", null))
        assertEquals(CLASSICAL, Genres.majority(3, 2))
        assertEquals(MODERN, Genres.majority(0, 1))
        assertNull(Genres.majority(2, 2))
        assertEquals(listOf("classical", "modern", null), listOf(CLASSICAL, MODERN, NONE).map(Genres::name))
        assertEquals(listOf(CLASSICAL, MODERN, null, null, null), listOf("classical", "modern", "Classical", "", null).map(Genres::named))
    }

    @Test
    fun `every composer of a 1_10 library is Classical by key, but the blank one and Anonymous`() {
        val keys = LibraryFixture.rows("composer_keys_1_10.csv").map { it.getValue("key") }.toSet()
        assertEquals(61, keys.size)
        for (key in keys - setOf("", "anonymous")) assertEquals(key, CLASSICAL, Genres.strong(key, null))
        assertNull(Genres.strong("", null))
        assertNull(Genres.strong("anonymous", null))
    }

    @Test
    fun `a playlist shows under the genre most of its pieces have, under both on a tie, and Recordings and Made in Studio always`() {
        fun shows(classical: Int, modern: Int, key: String? = null) =
            LibraryScope.entries.filter { Genres.playlistShows(classical, modern, key, it) }
        assertEquals(listOf(LibraryScope.All, LibraryScope.Classical), shows(40, 2))
        assertEquals(listOf(LibraryScope.All, LibraryScope.Modern), shows(1, 264))
        assertEquals(LibraryScope.entries, shows(3, 3))
        assertEquals("empty, or made here only", LibraryScope.entries, shows(0, 0))
        assertEquals(LibraryScope.entries, shows(0, 0, "recordings"))
        assertEquals(LibraryScope.entries, shows(5, 0, "studio"))
        assertTrue(Genres.playlistShows(17, 0, "popular", LibraryScope.Classical))
        assertFalse(Genres.playlistShows(17, 0, "popular", LibraryScope.Modern))
    }
}
