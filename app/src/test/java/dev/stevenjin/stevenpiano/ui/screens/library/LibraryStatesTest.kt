// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

import dev.stevenjin.stevenpiano.channels.ChannelSummary
import dev.stevenjin.stevenpiano.data.Genres
import dev.stevenjin.stevenpiano.data.LibraryScope
import dev.stevenjin.stevenpiano.data.PlaylistSort
import dev.stevenjin.stevenpiano.data.builtin.LibraryFixture
import dev.stevenjin.stevenpiano.data.db.PlaylistSummary
import dev.stevenjin.stevenpiano.data.imports.ComposerNames
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library's state when the database fails to read (the v1.2 audit, F1): an error state, never a crash. And what
 * the genre chosen does to it (v1.14 — M37).
 */
class LibraryStatesTest {
    private val all = Selection(Category.All, null) to ""
    private val logged = mutableListOf<Throwable>()

    @Test
    fun `a listing that reads becomes the state, with the piece count`() = runTest {
        val state = libraryStates(flowOf(all), flowOf(3), { logged += it }) { _, _ -> flowOf(Listing.Pieces(emptyList())) }.first()
        assertTrue(state.loaded)
        assertFalse(state.unreadable)
        assertEquals(3, state.pieceCount)
    }

    @Test
    fun `a listing that throws becomes the unreadable state, logged, not a crash`() = runTest {
        val tooLarge = IllegalStateException("Row too big to fit into CursorWindow")
        val state = libraryStates(flowOf(all), flowOf(1), { logged += it }) { _, _ -> flow<Listing> { throw tooLarge } }.first()
        assertTrue(state.loaded)
        assertTrue(state.unreadable)
        assertFalse("an unreadable library is not an empty one", state.empty)
        assertEquals(Category.All, state.category)
        assertEquals(listOf<Throwable>(tooLarge), logged)
    }

    @Test
    fun `a count that throws is unreadable too`() = runTest {
        val count = flow<Int> { throw IllegalStateException("database disk image is malformed") }
        val state = libraryStates(flowOf(all), count, { logged += it }) { _, _ -> flowOf(Listing.Pieces(emptyList())) }.first()
        assertTrue(state.unreadable)
        assertEquals(1, logged.size)
    }

    @Test
    fun `after a failure, choosing another category reads again`() = runTest {
        val selections = MutableStateFlow(all)
        val states = libraryStates(selections, flowOf(0), { logged += it }) { sel, _ ->
            if (sel.category == Category.All) flow { throw IllegalStateException("broken") } else flowOf(Listing.Playlists(emptyList()))
        }
        val seen = mutableListOf<LibraryState>()
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { states.toList(seen) }
        selections.value = Selection(Category.Playlists, null) to ""
        runCurrent()
        collecting.cancel()
        assertEquals(listOf(true, false), seen.map { it.unreadable })
        assertEquals(Category.Playlists, seen.last().category)
    }

    @Test
    fun `the built-in playlists come first, in the order they were made, and an empty one is not shown`() {
        val all = listOf(
            PlaylistSummary(5, "Road trip", false, 3, 1_000),
            PlaylistSummary(9, "Recognisable", false, 12, 1_000, builtIn = true, builtInKey = "recognisable"),
            PlaylistSummary(2, "Bach", true, 40, 1_000),
            PlaylistSummary(8, "Popular", false, 17, 1_000, builtIn = true, builtInKey = "popular"),
            PlaylistSummary(11, "Epic on piano", false, 0, 0, builtIn = true, builtInKey = "epic"),
        )
        assertEquals(listOf(8L, 9L, 5L, 2L), PlaylistShelf.shown(all).map { it.id })
    }

    @Test
    fun `newest first, the built-in playlists come after the person's, an empty one still not shown`() {
        val all = listOf(
            PlaylistSummary(5, "Road trip", false, 3, 1_000),
            PlaylistSummary(9, "Recognisable", false, 12, 1_000, builtIn = true, builtInKey = "recognisable"),
            PlaylistSummary(2, "Bach", true, 40, 1_000),
            PlaylistSummary(8, "Popular", false, 17, 1_000, builtIn = true, builtInKey = "popular"),
            PlaylistSummary(11, "Epic on piano", false, 0, 0, builtIn = true, builtInKey = "epic"),
            PlaylistSummary(14, "MIDI", true, 265, 1_000),
        )
        assertEquals(listOf(14L, 5L, 2L, 8L, 9L), PlaylistShelf.shown(all, PlaylistSort.NEWEST, listOf("popular", "recognisable", "epic")).map { it.id })
        assertEquals("the sort's pop-up button reads it to TalkBack", "Sort playlists, Newest first", sortDescription(PlaylistSort.NEWEST))
        assertEquals("Sort playlists, Name", sortDescription(PlaylistSort.NAME))
    }

    @Test
    fun `the genre names the chips, is what the listing is asked for and narrows the channels, and a name's Move follows its pieces`() = runTest {
        // No second "All" beside the switch's; the composers are artists under Modern only.
        assertEquals(listOf("Pieces", "Playlists", "Composers", "Favorites", "Recent"), Category.entries.map { it.label(LibraryScope.All) })
        assertEquals("Composers", Category.Composers.label(LibraryScope.Classical))
        assertEquals(listOf("Pieces", "Playlists", "Artists", "Favorites", "Recent"), Category.entries.map { it.label(LibraryScope.Modern) })

        val asked = mutableListOf<LibraryScope>()
        val state = libraryStates(flowOf(Selection(Category.Favorites, null, LibraryScope.Modern) to ""), flowOf(5), { logged += it }) { sel, _ ->
            asked += sel.scope
            flowOf(Listing.Pieces(emptyList()))
        }.first()
        assertEquals(listOf(LibraryScope.Modern), asked)
        assertEquals(LibraryScope.Modern, state.scope)
        assertFalse("a genre with nothing in it is not an empty library: the switch stays", state.empty)

        // The channels listed under a genre are its own; Everything (no genre) only under All.
        val cards = listOf(
            ChannelSummary("classical", "Classical", listOf(1L), emptyList(), Genres.CLASSICAL),
            ChannelSummary("modern", "Modern", listOf(2L), emptyList(), Genres.MODERN),
            ChannelSummary("calm", "Calm", listOf(1L), emptyList(), Genres.CLASSICAL),
            ChannelSummary("everything", "Everything", listOf(1L, 2L), emptyList()),
        )
        assertEquals(cards, GenreListing.channels(cards, LibraryScope.All))
        assertEquals(listOf("classical", "calm"), GenreListing.channels(cards, LibraryScope.Classical).map { it.key })
        assertEquals(listOf("modern"), GenreListing.channels(cards, LibraryScope.Modern).map { it.key })

        // A name's Move is to the genre other than the one most of its pieces have; none without one.
        fun piece(id: Long, key: String, genre: Int) = LibraryFixture.piece(id, "Piece $id", "").copy(composerKey = key, genre = genre)
        val pieces = listOf(
            piece(1, "ed sheeran", Genres.MODERN), piece(2, "ed sheeran", Genres.MODERN), piece(3, "ed sheeran", Genres.CLASSICAL),
            piece(4, "chopin", Genres.CLASSICAL),
            piece(5, "", Genres.CLASSICAL),
            piece(6, ComposerNames.STUDIO_KEY, Genres.NONE),
            piece(7, "satie", Genres.CLASSICAL), piece(8, "satie", Genres.MODERN),
        )
        val genres = GenreListing.byKey(pieces)
        assertEquals(mapOf("ed sheeran" to Genres.MODERN, "chopin" to Genres.CLASSICAL), genres)
        assertEquals(listOf("Move to Classical", "Move to Modern"), listOf("ed sheeran", "chopin").map { moveLabel(moveTarget(genres[it])!!) })
        assertEquals("the blank name, a made-here one and a tie offer no Move", listOf(null, null, null), listOf("", ComposerNames.STUDIO_KEY, "satie").map { moveTarget(genres[it]) })
        assertEquals("Moved to Modern.", movedLine(Genres.MODERN))
    }
}
