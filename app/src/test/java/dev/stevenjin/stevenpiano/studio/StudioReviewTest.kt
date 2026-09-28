// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.player.NowPlaying
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.PlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Keep or Discard after a first listen (v1.7 — M23): a piece Studio made is asked about once 15 s of it
 * have played (or all of it, when shorter), while it is loaded, until Keep or Discard; Discard takes it
 * out of the library; the pieces waiting are kept across restarts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudioReviewTest {
    private class Store(initial: Set<Long> = emptySet()) : ReviewStore {
        val saved = MutableStateFlow(initial)
        override val undecided = saved

        override suspend fun save(ids: Set<Long>) {
            saved.value = ids
        }
    }

    private class Player : ReviewPlayer {
        override val state = MutableStateFlow(PlayerState())
        var position = 0L

        override fun positionMicrosNow(): Long = position

        fun load(id: Long, durationMicros: Long, status: PlaybackStatus = PlaybackStatus.Playing) {
            state.value = PlayerState(status = status, piece = NowPlaying(id, "Take $id", "Made in Studio", durationMicros, NoteList.Empty))
        }
    }

    private class Library(private val missing: Set<Long> = emptySet()) : StudioLibrary {
        val discarded = mutableListOf<Long>()

        override suspend fun exists(pieceId: Long): Boolean = pieceId !in missing

        override suspend fun add(fileName: String, bytes: ByteArray, title: String, composer: String): Long? = null

        override suspend fun describe(pieceId: Long, description: String) = Unit

        override suspend fun discard(pieceId: Long) {
            discarded += pieceId
        }
    }

    private fun TestScope.review(store: Store = Store(), player: Player = Player(), library: Library = Library()) =
        StudioReview(store, player, library, backgroundScope).also {
            it.start()
            runCurrent()
        }

    @Test
    fun `a new piece is asked about once 15 seconds of it have played, and Keep ends the question`() = runTest {
        val store = Store()
        val player = Player()
        val review = review(store, player)
        review.made(7)
        assertEquals(setOf(7L), store.saved.value)
        player.load(7, 180_000_000)
        runCurrent()
        player.position = 14_900_000
        advanceTimeBy(1_000)
        assertNull("not heard yet", review.asking.value)
        player.position = 15_000_000
        advanceTimeBy(300)
        assertEquals(7L, review.asking.value)
        review.keep(7)
        runCurrent()
        assertNull(review.asking.value)
        assertEquals(emptySet<Long>(), store.saved.value)
        player.load(7, 180_000_000)
        advanceTimeBy(1_000)
        assertNull("kept: never asked again", review.asking.value)
    }

    @Test
    fun `Discard takes the piece out of the library`() = runTest {
        val library = Library()
        val player = Player()
        val review = review(player = player, library = library)
        review.made(8)
        player.load(8, 60_000_000)
        advanceTimeBy(300)
        player.position = 20_000_000
        advanceTimeBy(300)
        assertEquals(8L, review.asking.value)
        review.discard(8)
        runCurrent()
        assertEquals(listOf(8L), library.discarded)
        assertEquals(setOf(8L), review.discardedNow.value)
        assertEquals(emptySet<Long>(), review.undecided.value)
        assertNull(review.asking.value)
    }

    @Test
    fun `a short piece is heard at its end, a paused one isn't listened to, and other pieces are never asked about`() = runTest {
        val player = Player()
        val review = review(player = player)
        review.made(9)
        player.load(9, 5_000_000, PlaybackStatus.Paused)
        player.position = 5_000_000
        advanceTimeBy(1_000)
        assertNull("paused: not listening", review.asking.value)
        player.position = -2_000_000   // the pause before a piece
        player.load(9, 5_000_000)
        advanceTimeBy(300)
        player.position = 4_960_000
        advanceTimeBy(300)
        assertEquals(9L, review.asking.value)
        player.load(10, 5_000_000)
        player.position = 5_000_000
        advanceTimeBy(1_000)
        assertNull("not a Studio piece waiting", review.asking.value)
        player.load(9, 5_000_000, PlaybackStatus.Stopped)
        runCurrent()
        assertEquals("heard once, asked whenever it is loaded", 9L, review.asking.value)
    }

    @Test
    fun `the pieces waiting are read back after a restart`() = runTest {
        val player = Player()
        val review = review(Store(setOf(3L, 4L)), player)
        assertEquals(setOf(3L, 4L), review.undecided.value)
        player.load(4, 30_000_000)
        advanceTimeBy(300)
        player.position = 16_000_000
        advanceTimeBy(300)
        assertEquals(4L, review.asking.value)
    }

    @Test
    fun `pieces deleted from the library meanwhile no longer wait`() = runTest {
        val store = Store(setOf(3L, 4L))
        val review = review(store, library = Library(missing = setOf(3L)))
        assertEquals(setOf(4L), review.undecided.value)
        assertEquals(setOf(4L), store.saved.value)
    }

    @Test
    fun `the last piece's position doesn't count for the next one`() = runTest {
        val player = Player()
        val review = review(player = player)
        review.made(11)
        player.load(5, 180_000_000)
        player.position = 50_000_000   // another piece, 50 s in
        runCurrent()
        player.load(11, 10_000_000)   // the new piece loaded, its clock not yet moved off the last one
        advanceTimeBy(1_000)
        assertNull("50 s of the last piece is not 10 s of this one", review.asking.value)
        player.position = -2_000_000   // its own clock: the pause before it
        advanceTimeBy(300)
        assertNull(review.asking.value)
        player.position = 9_960_000   // its end
        advanceTimeBy(300)
        assertEquals(11L, review.asking.value)
    }
}
