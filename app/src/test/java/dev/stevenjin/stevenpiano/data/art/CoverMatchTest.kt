// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.net.CatalogTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of Apple's results is a piece's album cover (v1.15 — M40): only one whose title and artist clearly fit. */
class CoverMatchTest {
    private fun song(track: String, artist: String, album: String? = null, art: Boolean = true) = CatalogTrack(
        trackName = track,
        artistName = artist,
        collectionName = album,
        artworkUrl100 = if (art) "https://is1-ssl.mzstatic.com/image/thumb/Music/v4/${track.length}/${artist.length}/source/100x100bb.jpg" else null,
        trackViewUrl = "https://music.apple.com/us/album/x/1?i=2",
        kind = "song",
    )

    @Test
    fun `a pop song is found by its title and its artist`() {
        val capaldi = song("Someone You Loved", "Lewis Capaldi", "Divinely Uninspired to a Hellish Extent")
        val results = listOf(song("Someone You Loved (Piano Version)", "Piano Dreamers", "Piano Covers"), capaldi)
        assertSame(capaldi, CoverMatch.pick("Someone You Loved", "Lewis Capaldi", classical = false, results))
        assertEquals("someone you loved lewis capaldi", CoverMatch.term("Someone You Loved", "Lewis Capaldi"))
    }

    @Test
    fun `a soundtrack piece is found under its composer, who is the artist`() {
        val hisaishi = song("Merry-Go-Round of Life (From \"Howl's Moving Castle\")", "Joe Hisaishi", "Howl's Moving Castle (Original Soundtrack)")
        val results = listOf(song("Merry Go Round of Life", "Grissini Project", "Ghibli on Piano"), hisaishi)
        assertSame(hisaishi, CoverMatch.pick("Merry-Go-Round of Life", "Joe Hisaishi", classical = false, results))
    }

    @Test
    fun `a classical title with a catalogue number is found by 70 % of its words, its composer named by the album`() {
        val title = "Prelude and Fugue in C Major, BWV 846"
        val minor = song("Prelude & Fugue No. 2 in C Minor, BWV 847: I. Prelude", "Glenn Gould", "Bach: The Well-Tempered Clavier, Book I")
        val major = song("Prelude & Fugue No. 1 in C Major, BWV 846: I. Prelude", "Glenn Gould", "Bach: The Well-Tempered Clavier, Book I")
        assertSame(major, CoverMatch.pick(title, "Bach", classical = true, listOf(minor, major)))
        // Five of its six longer words ("and" is missing): 83 %. The C minor one holds three: 50 %.
        assertTrue(CoverMatch.trackFits(CoverMatch.core(title), CoverMatch.core(major.trackName)))
        assertFalse(CoverMatch.trackFits(CoverMatch.core(title), CoverMatch.core(minor.trackName)))
        // An artist who is no composer is never named by the album alone.
        assertNull(CoverMatch.pick(title, "Bach", classical = false, listOf(major)))
    }

    @Test
    fun `the wrong artist is refused, however well the title fits`() {
        val others = listOf(song("Clair de Lune", "Flight Facilities", "Down to Earth"), song("Clair de lune", "Taylor Swift"))
        assertNull(CoverMatch.pick("Clair de lune", "Debussy", classical = true, others))
        val tributes = listOf(song("Yesterday", "Boyz II Men", "II"), song("Yesterday", "Leona Lewis", "Songs of The Beatles"))
        assertNull(CoverMatch.pick("Yesterday", "The Beatles", classical = false, tributes))
        assertNull("no artist, nothing to match", CoverMatch.pick("Yesterday", "", classical = false, tributes))
    }

    @Test
    fun `a result without artwork, or with artwork elsewhere, is skipped for the next`() {
        val bare = song("Clair de lune", "Claude Debussy", art = false)
        val elsewhere = song("Clair de lune", "Claude Debussy").copy(artworkUrl100 = "https://is1-ssl.mzstatic.com.example.com/x/100x100bb.jpg")
        val weissenberg = song("Suite bergamasque, L. 75: III. Clair de lune", "Alexis Weissenberg", "Debussy: Piano Works")
        assertSame(weissenberg, CoverMatch.pick("Clair de lune", "Debussy", classical = true, listOf(bare, elsewhere, weissenberg)))
    }

    @Test
    fun `parentheticals, brackets and a dash's tail are no part of a title`() {
        assertEquals("bohemian rhapsody", CoverMatch.core("Bohemian Rhapsody (Piano Version) [Live]"))
        assertEquals("bohemian rhapsody", CoverMatch.core("Bohemian Rhapsody - Remastered 2011"))
        assertEquals("song", CoverMatch.core("Song (feat. Someone) - Radio Edit"))
        assertEquals("nocturne in e flat major op 9 no 2", CoverMatch.core("Nocturne in E-flat Major, Op. 9 No. 2"))
        assertEquals("fur elise", CoverMatch.core("Für Elise"))
        val queen = song("Bohemian Rhapsody - Remastered 2011", "Queen", "A Night at the Opera (2011 Remaster)")
        assertSame(queen, CoverMatch.pick("Bohemian Rhapsody (Piano Version)", "Queen", classical = false, listOf(queen)))
        assertNull("no title left, nothing to match", CoverMatch.pick("(Untitled)", "Queen", classical = false, listOf(queen)))
    }
}
