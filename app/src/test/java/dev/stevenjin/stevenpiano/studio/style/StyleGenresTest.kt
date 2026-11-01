// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.style

import dev.stevenjin.stevenpiano.data.Genres
import dev.stevenjin.stevenpiano.data.builtin.LibraryFixture
import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import dev.stevenjin.stevenpiano.studio.compose.Genre
import dev.stevenjin.stevenpiano.studio.compose.Mood
import dev.stevenjin.stevenpiano.studio.compose.MusicKey
import dev.stevenjin.stevenpiano.studio.compose.SeedFacts
import dev.stevenjin.stevenpiano.ui.screens.studio.GENRE_SUGGESTIONS
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Studio's genre styles in the idea box (v1.37 — M74): the words, the seed drawn from his own songs of the genre
 * (the playlists named for it, never a fragment or Studio's own), the fallback to Modern, an explicit "after"
 * winning, the follow-ups keeping the genre, and a genre's defaults.
 */
class StyleGenresTest {
    private fun piece(id: Long, title: String, composer: String, seconds: Int = 180, genre: Int = Genres.MODERN) =
        LibraryFixture.piece(id, title, composer, notes = 600, durationMs = seconds * 1_000L).copy(genre = genre)

    private val pieces = listOf(
        piece(1, "Shape of You", "Ed Sheeran"),
        piece(2, "Levitating", "Dua Lipa"),
        piece(3, "Around the World", "Daft Punk"),
        piece(4, "Short Intro", "Daft Punk", seconds = 20),   // a fragment
        piece(5, "Calm, after Levitating", ComposerNames.STUDIO, genre = Genres.NONE),   // Studio's own
        piece(6, "Clair de lune", "Debussy", genre = Genres.CLASSICAL),
        piece(7, "Dynamite", "BTS"),
        piece(8, "Bohemian Rhapsody", "Queen"),
    )
    private val playlists = listOf(
        PlaylistPool("House & Dance", listOf(2, 3, 4, 5)),
        PlaylistPool("K-pop", listOf(7)),
        PlaylistPool("Pop", listOf(1)),
        PlaylistPool("Workout", listOf(8)),
    )
    private val library = StyleLibrary.of(pieces, emptyList(), emptyList(), playlists)

    private fun parse(text: String, previous: PreviousTurn? = null, roll: Long = 0L) = StylePrompt.parse(text, library, previous, roll)

    @Test
    fun `chill lofi 3 minutes is lo-fi for three minutes`() {
        val result = parse("chill lofi 3 minutes")
        assertEquals(Genre.LoFi, result.spec.genre)
        assertEquals(3, result.spec.minutes)
        assertEquals(Mood.Calm, result.spec.mood)
        assertEquals(emptyList<String>(), result.unused)
        assertTrue(result.line, result.line.startsWith("Lo-fi · Calm · 3 min · "))
    }

    @Test
    fun `sad house in D minor is house, melancholy, D minor`() {
        val result = parse("sad house in D minor")
        assertEquals(Genre.House, result.spec.genre)
        assertEquals(Mood.Melancholy, result.spec.mood)
        assertEquals(MusicKey(2, true), result.spec.key)
        assertEquals(emptyList<String>(), result.unused)
        assertTrue(result.line, result.line.startsWith("House · Melancholy · D minor · 2 min · after "))
    }

    @Test
    fun `every genre's words, and each suggestion chip, every word understood`() {
        val words = mapOf(
            Genre.LoFi to listOf("lofi", "Lo-fi", "lo fi", "chillhop"),
            Genre.House to listOf("house", "dance", "EDM"),
            Genre.Chill to listOf("chill", "ambient", "relaxing"),
            Genre.Cinematic to listOf("cinematic", "a ballad", "film", "pop ballad", "emotional"),
        )
        for ((genre, list) in words) {
            for (text in list) {
                val result = parse(text)
                assertEquals(text, genre, result.spec.genre)
                assertEquals(text, emptyList<String>(), result.unused)
                assertEquals("$text: the genre's own mood", genre.mood, result.spec.mood)
            }
        }
        assertEquals(listOf(Genre.LoFi, Genre.House, Genre.Chill, Genre.Cinematic), GENRE_SUGGESTIONS.map { parse(it).spec.genre })
        for (chip in GENRE_SUGGESTIONS) assertEquals(chip, emptyList<String>(), parse(chip).unused)
        assertNull("a ballade is still the form", parse("a ballade").spec.genre)
        assertNull("pop alone is still the Modern channel", parse("pop").spec.genre)
        assertNull("a negated genre is none", parse("not house").spec.genre)
    }

    @Test
    fun `a genre's seed is drawn at random from its playlists, never a fragment or Studio's own`() {
        val draws = (0L..40L).map { roll ->
            val result = parse("house", roll = roll)
            assertEquals(SeedAsk.Pool("genre:house", "house songs"), result.spec.seed)
            assertEquals("House & Dance, without the fragment and Studio's piece", setOf(2L, 3L), result.candidates.toSet())
            assertEquals(parse("house", roll = roll), StylePrompt.parse("house", StyleLibrary.of(pieces.reversed(), emptyList(), emptyList(), playlists.reversed()), null, roll))
            result.candidates.first()
        }
        assertEquals("at random", setOf(2L, 3L), draws.toSet())
        assertEquals("K-pop and Pop, matched ignoring case and punctuation", setOf(1L, 7L), parse("pop ballad").candidates.toSet())
        assertTrue(GenreSeeds.holds(Genre.House, "house & DANCE") && GenreSeeds.holds(Genre.LoFi, "R&B/Soul") && !GenreSeeds.holds(Genre.House, "Housework"))
    }

    @Test
    fun `the line and the title say the genre and the song`() {
        val result = parse("house, 3 minutes", roll = 5)
        val first = library.byId.getValue(result.candidates.first())
        assertEquals("House · 3 min · after ${first.title} (${first.composerShort})", result.line)
        assertEquals("House, after Levitating", StylePrompt.title(Mood.Bright, result.spec.seed, "Levitating", Genre.House))
        assertEquals("House piece", StylePrompt.title(Mood.Bright, SeedAsk.Default, "Levitating", Genre.House))
        assertEquals("Lo-fi, after Dynamite", StylePrompt.title(Mood.Calm, SeedAsk.Piece(7), "Dynamite", Genre.LoFi))
        assertEquals("no genre: as before", "Calm piece", StylePrompt.title(Mood.Calm, SeedAsk.Default, null))
    }

    @Test
    fun `with none of its songs any Modern piece, and the line says so, and with no Modern piece the default seed`() {
        val result = parse("lofi")
        assertTrue(result.candidates.isNotEmpty())
        assertTrue("Modern, no fragment, no classical", result.candidates.all { it in setOf(1L, 2L, 3L, 7L, 8L) })
        val first = library.byId.getValue(result.candidates.first())
        assertTrue(result.line, result.line.endsWith("No lo-fi songs yet: after ${first.title} (${first.composerShort})"))

        val classical = StyleLibrary.of(listOf(piece(6, "Clair de lune", "Debussy", genre = Genres.CLASSICAL)), emptyList(), emptyList())
        val none = StylePrompt.parse("lofi", classical)
        assertEquals(SeedAsk.Default, none.spec.seed)
        assertTrue(none.candidates.isEmpty())
        assertEquals("Lo-fi · 2 min · No lo-fi songs yet", none.line)
    }

    @Test
    fun `an explicit after still wins`() {
        val result = parse("house after clair de lune")
        assertEquals(Genre.House, result.spec.genre)
        assertTrue(result.spec.seed is SeedAsk.Title)
        assertEquals(6L, result.candidates.first())
        assertEquals(emptyList<String>(), result.unused)
        assertTrue(result.line, result.line.endsWith("after Clair de lune (Debussy)"))
    }

    @Test
    fun `the follow-ups keep the genre, and its song unless a different one is asked`() {
        val first = parse("lofi", roll = 3)
        val seed = first.candidates.first()
        val previous = PreviousTurn(first.spec, bpm = 74, seedPieceId = seed)
        val slower = parse("slower", previous, roll = 9)
        assertTrue(slower.refinement)
        assertEquals(Genre.LoFi, slower.spec.genre)
        assertEquals(TempoAsk.Exact(63), slower.spec.tempo)
        assertEquals("the same song", seed, slower.candidates.first())
        val another = parse("another", previous, roll = 9)
        assertTrue(another.again)
        assertEquals(first.spec, another.spec)
        assertEquals(seed, another.candidates.first())
        val different = parse("something different", previous, roll = 9)
        assertEquals(Genre.LoFi, different.spec.genre)
        assertEquals(1, different.spec.variant)
        assertFalse("a new genre starts afresh", parse("house", previous).refinement)
        assertEquals(first.spec, StyleSpec.decode(first.spec.encode()))
        assertEquals("Another like it keeps the song", seed, StylePrompt.candidates(first.spec, library, roll = 99, keep = seed).first())
    }

    @Test
    fun `a genre's defaults - its tempo in the gentle half of its range, lo-fi's minor - and what is asked wins`() {
        val c = SeedFacts(MusicKey(0, false), 128, 128.0)
        val lofi = SeedPicker.request(StyleSpec(Mood.Calm, genre = Genre.LoFi), c)
        assertEquals(77, lofi.bpm)
        assertEquals("C major's relative minor: no note moves", MusicKey(9, true), lofi.key)
        assertEquals(Genre.LoFi, lofi.genre)
        assertEquals(120, SeedPicker.request(StyleSpec(Mood.Bright, genre = Genre.House), SeedFacts(MusicKey(0, false), 100, 100.0)).bpm)
        assertEquals(70, SeedPicker.request(StyleSpec(Mood.Melancholy, genre = Genre.Cinematic), c).bpm)
        assertEquals(90, SeedPicker.request(StyleSpec(Mood.Bright, tempo = TempoAsk.Exact(90), genre = Genre.House), c).bpm)
        assertEquals(MusicKey(0, false), SeedPicker.request(StyleSpec(Mood.Calm, minor = false, genre = Genre.LoFi), c).key)
        assertNull("no genre: the seed's own tempo", SeedPicker.request(StyleSpec(Mood.Calm), c).bpm)

        // A genre's songs are taken as drawn, the first that reads: not the one nearest in mode or tempo.
        val spec = parse("house", roll = 1).spec
        val facts = mapOf(10L to SeedFacts(MusicKey(0, false), 60, 60.0), 11L to SeedFacts(MusicKey(9, true), 122, 122.0))
        val pick = runBlocking { SeedPicker.pick(spec, listOf(99L, 10L, 11L), { facts[it] }) }
        assertEquals(10L, pick?.pieceId)
    }
}
