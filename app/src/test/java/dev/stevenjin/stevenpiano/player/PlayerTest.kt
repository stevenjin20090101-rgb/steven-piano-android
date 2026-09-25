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
    fun `a new epoch while playing re-syncs the piano, even with no drop seen`() = runBlocking {
        val pedalled = SmfParser.parse(
            SmfBuilder(format = 0, division = 1000).track {
                tempo(0, 1_000_000)
                cc(0, 64, 127)
                noteOn(0, 60)
                noteOff(5_000, 60)
            }.build(),
        )
        source.pieces[1] = pedalled
        onMain { player.play(1) }
        withTimeout(2_000) { while (link.messages.size < 2) delay(5) }
        link.clear()
        link.reconnectQuietly()
        withTimeout(2_000) { while (link.messages.size < 3) delay(5) }
        assertEquals(listOf("B0 40 00", "B0 7B 00", "B0 40 7F"), link.messages)   // silence, then the pedal again
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
        assertTrue(onMain { player.stopAndFlush(300) })
    }

    @Test
    fun `live keys go out through the player, and a dropped link lets go of them`() = runBlocking {
        onMain {
            player.liveNoteOn(60, 100)
            player.liveSustain(true)
        }
        withTimeout(2_000) { player.liveSustain.first { it } }
        assertEquals(listOf("90 3C 64", "B0 40 7F"), link.messages)
        assertEquals(1L shl 36, player.activeKeysLow)
        link.drop()
        withTimeout(2_000) { player.liveSustain.first { !it } }
        assertEquals(listOf("90 3C 64", "B0 40 7F", "80 3C 00", "B0 40 00"), link.messages)
        assertEquals(0L, player.activeKeysLow)
    }

    @Test
    fun `leaving the Keys screen lets go of its keys while the piece plays on`() = runBlocking {
        source.pieces[1] = piece(64, 5_000)
        onMain { player.play(1) }
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Playing } }
        withTimeout(2_000) { while (link.messages.isEmpty()) delay(5) }
        onMain {
            player.liveNoteOn(60, 100)
            player.silenceLive()
        }
        withTimeout(2_000) { while (link.messages.size < 3) delay(5) }
        assertEquals(listOf("90 40 50", "90 3C 64", "80 3C 00"), link.messages)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
        assertEquals(1L shl 40, player.activeKeysLow)
        assertTrue(onMain { player.stopAndFlush(300) })
    }

    @Test
    fun `repeat one plays the piece again from the top without reading it again`() = runBlocking {
        source.pieces[1] = piece(60, 30)
        onMain {
            player.setRepeat(RepeatMode.ONE)
            player.play(1)
        }
        withTimeout(6_000) { while (link.messages.count { it == "90 3C 50" } < 2) delay(10) }
        assertTrue(onMain { player.stopAndFlush(300) })
        assertEquals(listOf(1L), source.loads.toList())
        withTimeout(1_000) { while (source.played.size < 2) delay(10) }
        assertEquals(listOf(1L, 1L), source.played.toList())
        assertEquals(RepeatMode.ONE, player.state.value.queue.repeat)
    }

    @Test
    fun `repeat all wraps from the last piece to the first`() = runBlocking {
        source.pieces[1] = piece(60, 30)
        source.pieces[2] = piece(62, 30)
        onMain {
            player.setRepeat(RepeatMode.ALL)
            player.play(1, listOf(1, 2))
        }
        withTimeout(8_000) { while (noteOns().size < 3) delay(10) }
        assertTrue(onMain { player.stopAndFlush(300) })
        assertEquals(listOf("90 3C 50", "90 3E 50", "90 3C 50"), noteOns().take(3))
        assertEquals(0, player.state.value.queueIndex)
    }

    @Test
    fun `play next plays before the rest of the queue`() = runBlocking {
        source.pieces[1] = piece(60, 30)
        source.pieces[2] = piece(62, 30)
        source.pieces[3] = piece(64, 30)
        onMain {
            player.play(1, listOf(1, 2))
            player.playNext(listOf(3))
        }
        assertEquals(listOf(1L, 3L, 2L), player.state.value.queue.ids)
        withTimeout(8_000) { while (noteOns().size < 3) delay(10) }
        assertEquals(listOf("90 3C 50", "90 40 50", "90 3E 50"), noteOns())
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Stopped && it.queueIndex == 2 } }
        assertTrue(onMain { player.stopAndFlush(300) })
        assertEquals(listOf(1L, 3L, 2L), source.loads.toList())
    }

    @Test
    fun `with nothing queued, play next and add to queue start playing`() = runBlocking {
        source.pieces[4] = piece(65, 5_000)
        val started = onMain { player.addToQueue(listOf(4)) }
        assertTrue(started)
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Playing } }
        assertTrue(!onMain { player.addToQueue(listOf(4)) })   // now it queues
        assertEquals(listOf(4L, 4L), player.state.value.queue.ids)
        assertTrue(onMain { player.stopAndFlush(300) })
    }

    private fun noteOns(): List<String> = link.messages.filter { it.startsWith("90 ") && !it.endsWith(" 00") }

    @Test
    fun `Next tapped again and again reads and works out only the first piece and the last`() = runBlocking {
        for (id in 1L..5L) source.pieces[id] = piece(59 + id.toInt(), 5_000)
        source.loadDelayMs = 100   // reading a file takes a while
        onMain { player.play(1, listOf(1, 2, 3, 4, 5)) }
        repeat(4) {
            delay(10)
            onMain { player.next() }
        }
        withTimeout(5_000) { player.state.first { it.piece?.pieceId == 5L } }
        assertEquals(listOf(1L, 5L), source.loads.toList())   // 2, 3 and 4 were replaced while they waited
        assertTrue(onMain { player.stopAndFlush(300) })
    }

    @Test
    fun `a piece that can't be read says why`() = runBlocking {
        onMain { player.play(7) }
        val state = withTimeout(2_000) { player.state.first { it.problem != null } }
        assertEquals("This isn't a MIDI file, or it is damaged.", state.problem)
        assertNull(state.piece)
        assertEquals(PlaybackStatus.Stopped, state.status)
    }

    @Test
    fun `a piece arrives with its hands and fingering, and transposing fingers it again`() = runBlocking {
        source.pieces[1] = SmfParser.parse(
            SmfBuilder(format = 1, division = 480).track { tempo(0, 500_000) }
                .track {
                    name(0, "Piano right")
                    listOf(60, 62, 64).forEachIndexed { k, key -> noteOn(k * 480L, key); noteOff(k * 480L + 480, key) }
                }
                .track {
                    name(0, "Piano left")
                    noteOn(0, 48)
                    noteOff(1_440, 48)
                }
                .build(),
        )
        onMain { player.play(1) }
        val loaded = withTimeout(5_000) { player.state.first { it.piece?.pieceId == 1L } }.piece!!
        assertEquals(4, loaded.hands.size)
        fun rightHand(p: NowPlaying) = (0 until 4).filter { p.notes.note(it) >= 60 }.map { p.fingers[it].toInt() }
        assertEquals(listOf(1, 2, 3), rightHand(loaded))
        assertEquals(loaded.fingers, loaded.fingersFor(0, true))
        assertNull(loaded.fingersFor(1, true))
        onMain { player.setTranspose(1) }   // C D E becomes C♯ D♯ F: the thumb leaves C♯
        val moved = withTimeout(5_000) { player.state.first { it.piece?.fingersFor(1, true) != null } }.piece!!
        assertTrue(rightHand(moved)[0] != 1)
        assertTrue(onMain { player.stopAndFlush(300) })
    }

    private class FakeSource : PieceSource {
        val pieces = ConcurrentHashMap<Long, MidiPiece>()
        val played = CopyOnWriteArrayList<Long>()
        val loads = CopyOnWriteArrayList<Long>()

        @Volatile
        var loadDelayMs = 0L

        override suspend fun load(pieceId: Long): PlayablePiece {
            loads += pieceId
            delay(loadDelayMs)
            val midi = pieces[pieceId] ?: throw SmfException("This isn't a MIDI file, or it is damaged.")
            return PlayablePiece(pieceId, "Piece $pieceId", "Composer", midi)
        }

        override suspend fun markPlayed(pieceId: Long) {
            played += pieceId
        }
    }
}
