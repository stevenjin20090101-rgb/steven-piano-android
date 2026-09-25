// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.library

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

/** The Library's state when the database fails to read (the v1.2 audit, F1): an error state, never a crash. */
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
}
