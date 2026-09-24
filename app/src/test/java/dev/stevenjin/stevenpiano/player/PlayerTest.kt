// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import dev.stevenjin.stevenpiano.ble.FakePianoLink
import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfException
import dev.stevenjin.stevenpiano.midi.SmfParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** The player with its real scheduler thread, on the real clock: ordering, not exact timing. */
class PlayerTest {
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val link = FakePianoLink()
    private val source = FakeSource()
    private val player = Player(link, source, scope, prepareThread = {})

    @After
    fun tearDown() {
        scope.cancel()
        main.close()
    }

    private fun piece(key: Int, lengthMs: Long) = SmfParser.parse(
        SmfBuilder(format = 0, division = 1000).track {
            tempo(0, 1_000_000)
            noteOn(0, key)
            noteOff(lengthMs, key)
        }.build(),
    )

    private suspend fun <T> onMain(block: () -> T): T = withContext(main) { block() }

    @Test
    fun `a queue plays through, advances by itself, and stop silences last`() = runBlocking {
        source.pieces[1] = piece(60, 30)
        source.pieces[2] = piece(62, 30)
        onMain { player.play(1, listOf(1, 2)) }
        withTimeout(5_000) {
            player.state.first { it.piece?.pieceId == 2L && it.status == PlaybackStatus.Stopped && link.messages.size >= 8 }
        }
        assertEquals(2, player.state.value.queueSize)
        assertEquals(1, player.state.value.queueIndex)
        assertTrue(onMain { player.stopAndFlush(300) })
        assertEquals(
            listOf(
                "90 3C 50", "80 3C 00", "B0 40 00", "B0 7B 00",
                "90 3E 50", "80 3E 00", "B0 40 00", "B0 7B 00",
                "B0 40 00", "B0 7B 00",
            ),
            link.messages,
        )
        withTimeout(1_000) { while (source.played.size < 2) delay(10) }
        assertEquals(listOf(1L, 2L), source.played.toList())
    }

    @Test
    fun `a dropped link pauses, and only Play resumes`() = runBlocking {
        source.pieces[1] = piece(60, 5_000)
        onMain { player.play(1) }
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Playing } }
        link.drop()
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Paused } }
        link.connect(null)
        delay(100)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
        onMain { player.resume() }
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Playing } }
        assertTrue(onMain { player.stopAndFlush(300) })
        assertEquals(listOf("B0 40 00", "B0 7B 00"), link.messages.takeLast(2))
    }

    @Test
    fun `a piece that can't be read says why`() = runBlocking {
        onMain { player.play(7) }
        val state = withTimeout(2_000) { player.state.first { it.problem != null } }
        assertEquals("This isn't a MIDI file, or it is damaged.", state.problem)
        assertNull(state.piece)
        assertEquals(PlaybackStatus.Stopped, state.status)
    }

    private class FakeSource : PieceSource {
        val pieces = ConcurrentHashMap<Long, MidiPiece>()
        val played = CopyOnWriteArrayList<Long>()

        override suspend fun load(pieceId: Long): PlayablePiece {
            val midi = pieces[pieceId] ?: throw SmfException("This isn't a MIDI file, or it is damaged.")
            return PlayablePiece(pieceId, "Piece $pieceId", "Composer", midi)
        }

        override suspend fun markPlayed(pieceId: Long) {
            played += pieceId
        }
    }
}
