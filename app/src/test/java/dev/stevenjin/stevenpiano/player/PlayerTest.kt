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
import dev.stevenjin.stevenpiano.midi.KeyEvents
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** Whether a quiet time holds (v1.20 — M54), as the app's gate would answer: none unless a test says so. */
    @Volatile
    private var hush = false
    private val player = Player(link, source, scope, prepareThread = {}, quiet = { hush })

    /**
     * The player saw the link connected as it started: a new connection begins with the stop sequence
     * (v1.11 — M29), nothing playing. Each test starts after it, with pieces played as written: these tests are about
     * the queue and the order of what goes out, and expression (v1.16 — M44, Light at first) would shape the notes.
     */
    @Before
    fun connected() = runBlocking {
        onMain { player.setPerformance(PerformanceSettings(expression = ExpressionLevel.OFF, velocityFloor = 1)) }
        withTimeout(2_000) { while (link.messages.size < 2) delay(5) }
        assertEquals(listOf("B0 40 00", "B0 7B 00"), link.messages)
        assertEquals(listOf(true, true), link.sent.map { it.dropPending })
        link.clear()
    }

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
    fun `one piece per Play - the next one loads, waits at its start, and the next Play plays it, then stop silences last (v1_26 M61)`() = runBlocking {
        assertEquals(1, Player.MAX_IN_A_ROW)
        source.pieces[1] = piece(60, 30)
        source.pieces[2] = piece(62, 30)
        onMain { player.play(1, listOf(1, 2)) }
        withTimeout(5_000) { player.state.first { it.piece?.pieceId == 2L && !it.loading && it.status == PlaybackStatus.Stopped } }
        delay(300)
        assertEquals("the second piece waits, loaded, never started", listOf("90 3C 50", "80 3C 00", "B0 40 00", "B0 7B 00"), link.messages)
        assertEquals(PlaybackStatus.Stopped, player.state.value.status)
        assertEquals(1, player.state.value.queue.index)
        assertEquals(listOf(1L), source.played.toList())
        onMain { player.resume() }   // a person's Play
        withTimeout(5_000) { while (link.messages.size < 8) delay(10) }
        delay(300)
        assertEquals("and it alone plays: the queue is over", 8, link.messages.size)
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
    fun `a piece that leaves the library leaves the queue, and the one playing empties the player with the piano silenced`() = runBlocking {
        source.pieces[1] = piece(60, 5_000)
        source.pieces[2] = piece(62, 5_000)
        onMain { player.play(1, listOf(1, 2, 1, 2)) }
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Playing && it.piece?.pieceId == 1L } }
        onMain { player.forget(2) }
        assertEquals("the other piece's entries are gone", listOf(1L, 1L), player.state.value.queue.ids)
        assertEquals(PlaybackStatus.Playing, player.state.value.status)
        link.clear()
        onMain { player.forget(1) }
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Stopped } }
        assertNull(player.state.value.piece)
        assertEquals(emptyList<Long>(), player.state.value.queue.ids)
        withTimeout(2_000) { while (link.messages.size < 2) delay(5) }
        assertEquals("silenced first", listOf("B0 40 00", "B0 7B 00"), link.messages.take(2))
        onMain { player.play(2) }
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Playing && it.piece?.pieceId == 2L } }
        assertTrue(onMain { player.stopAndFlush(300) })
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
    fun `a new connection with nothing playing begins with the stop sequence, and a paused piece stays where it was`() = runBlocking {
        source.pieces[1] = piece(60, 5_000)
        onMain { player.play(1) }
        withTimeout(2_000) { while ("90 3C 50" !in link.messages) delay(5) }
        onMain { player.pause() }
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Paused } }
        val at = player.positionMicrosNow()
        link.drop()
        delay(50)
        link.clear()
        link.connect(null)
        withTimeout(2_000) { while (link.messages.size < 2) delay(5) }
        delay(50)
        assertEquals(listOf("B0 40 00", "B0 7B 00"), link.messages)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
        assertEquals(at, player.positionMicrosNow())

        link.clear()
        link.reconnectQuietly()   // a new epoch, still nothing playing: the stop sequence again
        withTimeout(2_000) { while (link.messages.size < 2) delay(5) }
        assertEquals(listOf("B0 40 00", "B0 7B 00"), link.messages)
        assertEquals(PlaybackStatus.Paused, player.state.value.status)
        assertTrue(onMain { player.stopAndFlush(300) })
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
        withTimeout(2_000) { while ("90 3C 50" !in link.messages) delay(5) }
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
        assertEquals("the live lane", listOf(true, true), link.sent.map { it.live })
        assertEquals(1L shl 36, player.activeKeysLow)
        link.drop()
        withTimeout(2_000) { player.liveSustain.first { !it } }
        assertEquals(listOf("90 3C 64", "B0 40 7F", "80 3C 00", "B0 40 00"), link.messages)
        assertEquals(0L, player.activeKeysLow)
    }

    @Test
    fun `a keyboard's keys go out through the player from any thread, and are refused while locked (v1_11 M29)`() = runBlocking {
        val chord = KeyEvents().apply {
            add(KeyEvents.DOWN, 60, 100)
            add(KeyEvents.DOWN, 64, 100)
        }
        Thread { player.external(chord, System.nanoTime()) }.apply { start() }.join()
        withTimeout(2_000) { while (link.messages.size < 2) delay(5) }
        assertEquals(listOf("90 3C 64", "90 40 64"), link.messages)
        player.silenceExternal()
        withTimeout(2_000) { while (link.messages.size < 4) delay(5) }
        assertEquals(listOf("80 3C 00", "80 40 00"), link.messages.drop(2))
        link.clear()
        onMain { player.lock("The piano's firmware is being updated.") }
        player.external(chord, System.nanoTime())
        delay(100)
        assertEquals(emptyList<String>(), link.messages)
        onMain { player.unlock() }
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
    fun `repeat one leaves the piece waiting at its start, and Play plays it again without reading it again`() = runBlocking {
        source.pieces[1] = piece(60, 30)
        onMain {
            player.setRepeat(RepeatMode.ONE)
            player.play(1)
        }
        withTimeout(6_000) { while ("B0 7B 00" !in link.messages) delay(10) }
        delay(2_000)   // past the 1.5 s gap: one piece per Play (v1.26 — M61), so it waits
        assertEquals("once", 1, link.messages.count { it == "90 3C 50" })
        assertEquals(PlaybackStatus.Paused, player.state.value.status)   // back at its start, ready
        onMain { player.resume() }
        withTimeout(6_000) { while (link.messages.count { it == "90 3C 50" } < 2) delay(10) }
        assertTrue(onMain { player.stopAndFlush(300) })
        assertEquals(listOf(1L), source.loads.toList())
        withTimeout(1_000) { while (source.played.size < 2) delay(10) }
        assertEquals(listOf(1L, 1L), source.played.toList())
        assertEquals(RepeatMode.ONE, player.state.value.queue.repeat)
    }

    @Test
    fun `repeat all wraps from the last piece to the first, one piece per Play`() = runBlocking {
        source.pieces[1] = piece(60, 30)
        source.pieces[2] = piece(62, 30)
        onMain {
            player.setRepeat(RepeatMode.ALL)
            player.play(1, listOf(1, 2))
        }
        for (expected in listOf(2L, 1L)) {
            withTimeout(8_000) { player.state.first { it.piece?.pieceId == expected && !it.loading && it.status != PlaybackStatus.Playing } }
            onMain { player.resume() }
            withTimeout(8_000) { player.state.first { it.piece?.pieceId == expected && it.status == PlaybackStatus.Playing } }
        }
        withTimeout(8_000) { while (noteOns().size < 3) delay(10) }
        assertTrue(onMain { player.stopAndFlush(300) })
        assertEquals(listOf("90 3C 50", "90 3E 50", "90 3C 50"), noteOns().take(3))
        assertEquals(0, player.state.value.queue.index)
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
        for (expected in listOf(3L, 2L)) {   // one piece per Play (v1.26 — M61): each waits, loaded, for it
            withTimeout(8_000) { player.state.first { it.piece?.pieceId == expected && !it.loading && it.status != PlaybackStatus.Playing } }
            onMain { player.resume() }
            withTimeout(8_000) { player.state.first { it.piece?.pieceId == expected && it.status == PlaybackStatus.Playing } }
        }
        withTimeout(8_000) { while (noteOns().size < 3) delay(10) }
        assertEquals(listOf("90 3C 50", "90 40 50", "90 3E 50"), noteOns())
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Stopped && it.queue.index == 2 } }
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
    fun `a piece begins after the pause before it, and the next one, waiting at its start, begins at once at its Play`() = runBlocking {
        source.pieces[1] = piece(60, 30)
        source.pieces[2] = piece(62, 30)
        val pressed = System.nanoTime()
        onMain {
            player.setPreRoll(200)
            player.play(1, listOf(1, 2))
        }
        withTimeout(8_000) { player.state.first { it.piece?.pieceId == 2L && !it.loading && it.status != PlaybackStatus.Playing } }
        val again = System.nanoTime()
        onMain { player.resume() }   // the next piece, loaded and waiting at its start: Play begins it at once, as Play always has
        withTimeout(8_000) { while (link.messages.count { it == "90 3E 50" } < 1) delay(10) }
        val sent = link.sent
        val first = sent.first { it.message == "90 3C 50" }
        val second = sent.first { it.message == "90 3E 50" }
        assertTrue("the first piece waited ${(first.atNanos - pressed) / 1_000_000} ms", first.atNanos - pressed >= 200_000_000L)
        assertTrue("the second piece came ${(second.atNanos - again) / 1_000_000} ms after Play", second.atNanos - again < 1_000_000_000L)
        assertTrue(onMain { player.stopAndFlush(300) })
    }

    @Test
    fun `play after a pause begins at once, without the pause before the piece`() = runBlocking {
        source.pieces[1] = piece(60, 5_000)
        onMain {
            player.setPreRoll(2_000)
            player.play(1)
        }
        withTimeout(5_000) { player.state.first { it.status == PlaybackStatus.Playing } }
        onMain { player.pause() }   // inside the pause before the piece: it holds the piece's start
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Paused } }
        assertTrue(link.messages.none { it.startsWith("90 ") })
        val resumed = System.nanoTime()
        onMain { player.resume() }
        withTimeout(2_000) { while (link.messages.none { it == "90 3C 50" }) delay(5) }
        val waitedMs = (link.sent.first { it.message == "90 3C 50" }.atNanos - resumed) / 1_000_000
        assertTrue("the first note came $waitedMs ms after Play", waitedMs < 1_000)
        assertTrue(onMain { player.stopAndFlush(300) })
    }

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
        assertEquals("composer1", loaded.composerKey)   // the library's key, for the composer's art
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

    @Test
    fun `a channel is set by its playAll and kept by next and additions, and a piece from a list, stop or a dismissal ends it`() = runBlocking {
        (1L..6L).forEach { source.pieces[it] = piece(60, 5_000) }
        fun channel() = player.state.value.channel
        fun uidOf(pieceId: Long): Long = player.state.value.queue.let { q -> q.uids[q.ids.indexOf(pieceId)] }

        onMain { player.playAll(listOf(1, 2, 3), channel = "calm") }
        assertEquals("calm", channel())
        onMain {
            player.next()
            player.addToQueue(listOf(4))   // the person's, joining the channel
            player.playNext(listOf(5))
        }
        assertEquals("calm", channel())
        onMain { player.skipToQueueEntry(uidOf(3)) }   // one the channel dealt
        assertEquals("calm", channel())
        onMain { player.skipToQueueEntry(uidOf(4)) }   // the person's own: the channel is over
        assertNull(channel())

        onMain {
            player.playAll(listOf(1, 2), channel = "epic")
            player.addToQueue(listOf(6), channel = "epic")   // the channel's own top-up
            player.skipToQueueEntry(uidOf(6))
        }
        assertEquals("epic", channel())
        onMain { player.play(3) }
        assertNull(channel())

        for (end in listOf<(Player) -> Unit>({ it.playAll(listOf(1, 2)) }, { it.stop() }, { it.leaveChannel() })) {
            onMain { player.playAll(listOf(1, 2), channel = "epic") }
            assertEquals("epic", channel())
            onMain { end(player) }
            assertNull(channel())
        }
        onMain { player.playAll(listOf(1, 2), channel = "epic") }
        assertTrue(onMain { player.stopAndFlush(300) })
        assertNull(channel())
    }

    @Test
    fun `locked for a firmware update, nothing plays or sounds until unlocked, and the lock reads as the problem`() = runBlocking {
        source.pieces[1] = piece(60, 5_000)
        source.pieces[2] = piece(62, 5_000)
        onMain { player.play(1, listOf(1, 2)) }
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Playing } }
        withTimeout(2_000) { while (link.messages.isEmpty()) delay(5) }
        onMain { player.lock("Updating the piano") }
        assertTrue(onMain { player.locked })
        assertEquals("Updating the piano", player.state.value.problem)
        assertTrue(withContext(main) { player.stopQuietly(300) })
        assertEquals(PlaybackStatus.Stopped, player.state.value.status)
        assertEquals("the stop sequence went, and was written", listOf("B0 40 00", "B0 7B 00"), link.messages.takeLast(2))
        link.clear()
        onMain {
            player.resume()
            player.togglePlayPause()
            player.next()
            player.previous()
            player.seek(1_000_000)
            player.play(2)
            player.playAll(listOf(1, 2), channel = "calm")
            player.addToQueue(listOf(2))
            player.liveNoteOn(60, 100)
            player.liveSustain(true)
        }
        delay(300)
        assertEquals("nothing reaches the piano while locked", emptyList<String>(), link.messages)
        assertEquals(PlaybackStatus.Stopped, player.state.value.status)
        assertEquals("the queue is as it was", 1L, player.state.value.queue.ids.first())
        assertNull(player.state.value.channel)

        onMain { player.unlock() }
        assertNull(player.state.value.problem)
        onMain { player.play(2) }
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Playing && it.piece?.pieceId == 2L } }
        assertTrue(onMain { player.stopAndFlush(300) })
    }

    @Test
    fun `a guest's request never starts playback, with nothing queued it waits, loaded, for Play (v1_20 M54)`() = runBlocking {
        source.pieces[1] = piece(60, 5_000)
        source.pieces[2] = piece(62, 5_000)
        onMain { player.queueWaiting(listOf(1)) }
        withTimeout(2_000) { player.state.first { it.piece?.pieceId == 1L && !it.loading } }
        delay(300)
        assertEquals(PlaybackStatus.Stopped, player.state.value.status)
        assertTrue("nothing reached the piano: ${link.messages}", noteOns().isEmpty())
        onMain { player.queueWaiting(listOf(2)) }
        assertEquals("the next request joins Up next", listOf(1L, 2L), player.state.value.queue.ids)
        delay(200)
        assertEquals(PlaybackStatus.Stopped, player.state.value.status)
        assertTrue("not played: nothing started", source.played.isEmpty())
        onMain { player.resume() }   // a person's Play
        withTimeout(2_000) { while ("90 3C 50" !in link.messages) delay(5) }
        assertTrue(onMain { player.stopAndFlush(300) })
    }

    @Test
    fun `while a quiet time holds nothing starts, a piece added waits loaded, and once lifted Play starts again (v1_20 M54)`() = runBlocking {
        source.pieces[1] = piece(60, 5_000)
        source.pieces[2] = piece(62, 5_000)
        hush = true
        onMain {
            player.play(1, listOf(1, 2))
            player.playAll(listOf(1, 2))
            player.playAll(listOf(1, 2), channel = "calm")
            player.resume()
            player.togglePlayPause()
            player.next()
            player.previous()
        }
        delay(300)
        assertEquals("nothing reached the piano", emptyList<String>(), link.messages.toList())
        assertTrue(player.state.value.queue.ids.isEmpty())
        assertNull(player.state.value.channel)
        assertFalse("added while quiet: it waits", onMain { player.addToQueue(listOf(1, 2)) })
        withTimeout(2_000) { player.state.first { it.piece?.pieceId == 1L && !it.loading } }
        onMain {
            player.skipToQueueEntry(player.state.value.queue.uids[1])
            player.resume()
        }
        delay(300)
        assertEquals(PlaybackStatus.Stopped, player.state.value.status)
        assertEquals("the queue as added", 0, player.state.value.queue.index)
        assertTrue("still nothing sounded: ${link.messages}", noteOns().isEmpty())
        hush = false   // Play anyway lifted it, or the block ended
        onMain { player.resume() }
        withTimeout(2_000) { while ("90 3C 50" !in link.messages) delay(5) }
        assertTrue(onMain { player.stopAndFlush(300) })
    }

    @Test
    fun `a piece that ends during a quiet time leaves the next one waiting, loaded, never started (v1_20 M54)`() = runBlocking {
        source.pieces[3] = piece(64, 30)
        source.pieces[1] = piece(60, 5_000)
        onMain { player.play(3, listOf(3, 1)) }
        withTimeout(2_000) { while ("90 40 50" !in link.messages) delay(5) }
        hush = true   // a block began as it played: its end does not start the next
        withTimeout(5_000) { player.state.first { it.piece?.pieceId == 1L && !it.loading } }
        delay(300)
        assertEquals(PlaybackStatus.Stopped, player.state.value.status)
        assertFalse("the next piece never sounded", "90 3C 50" in link.messages)
        assertTrue(onMain { player.stopAndFlush(300) })
    }

    @Test
    fun `each piece that sounded is told once - to its end, replaced, or stopped, and a pause is no end (v1_26 M61)`() = runBlocking {
        val heard = CopyOnWriteArrayList<Played>()
        val listening = scope.launch { player.played.collect { heard += it } }
        source.pieces[1] = piece(60, 30)
        source.pieces[2] = piece(62, 5_000)
        source.pieces[3] = piece(64, 5_000)
        onMain { player.play(1, listOf(1, 2, 3)) }
        withTimeout(5_000) { while (heard.isEmpty()) delay(10) }
        assertEquals(listOf(Played(1, ended = true)), heard.toList())
        withTimeout(5_000) { player.state.first { it.piece?.pieceId == 2L && !it.loading } }
        delay(200)
        assertEquals("loaded and waiting is not sounding", 1, heard.size)
        onMain { player.resume() }
        withTimeout(5_000) { player.state.first { it.status == PlaybackStatus.Playing } }
        onMain { player.pause() }
        withTimeout(2_000) { player.state.first { it.status == PlaybackStatus.Paused } }
        delay(200)
        assertEquals("a pause is no end", 1, heard.size)
        onMain { player.next() }
        withTimeout(5_000) { player.state.first { it.piece?.pieceId == 3L && it.status == PlaybackStatus.Playing } }
        withTimeout(2_000) { while (heard.size < 2) delay(10) }
        assertEquals(Played(2, ended = false), heard[1])
        onMain { player.stop() }
        withTimeout(2_000) { while (heard.size < 3) delay(10) }
        assertEquals(Played(3, ended = false), heard[2])
        delay(200)
        assertEquals("each once", 3, heard.size)
        listening.cancel()
    }

    /** The safety check's say, as a test sets it: every start [open]s, is refused, or is kept for later ([kept]). */
    private class TestGate : StartGate {
        var mode = Mode.REFUSE
        val asked = CopyOnWriteArrayList<Long?>()

        @Volatile
        var kept: (() -> Unit)? = null

        enum class Mode { OPEN, REFUSE, KEEP }

        override val open: Boolean get() = mode == Mode.OPEN

        override fun admit(pieceId: Long?, start: () -> Unit): Boolean {
            asked += pieceId
            return when (mode) {
                Mode.OPEN -> true
                Mode.REFUSE -> false
                Mode.KEEP -> {
                    kept = start
                    false
                }
            }
        }
    }

    @Test
    fun `the safety check is asked at every start - refused, nothing starts, kept, it starts once let go (v1_26 M61)`() = runBlocking {
        val gate = TestGate()
        val gated = Player(link, source, scope, prepareThread = {}, gate = gate)
        source.pieces[1] = piece(60, 5_000)
        source.pieces[2] = piece(62, 5_000)
        delay(200)   // the new player's own stop sequence, as it sees the link
        link.clear()
        onMain {
            gated.setPerformance(PerformanceSettings(expression = ExpressionLevel.OFF, velocityFloor = 1))
            gated.play(1, listOf(1, 2))
            gated.playAll(listOf(2, 1))
            gated.playAll(listOf(1), channel = "calm")
        }
        assertEquals(listOf<Long?>(1L, 2L, 1L), gate.asked.toList())
        assertTrue("nothing queued", gated.state.value.queue.ids.isEmpty())
        // What joins an empty queue waits, loaded, for a Play the gate lets go; Play, Next, an entry: refused.
        assertFalse(onMain { gated.addToQueue(listOf(1, 2)) })
        withTimeout(2_000) { gated.state.first { it.piece?.pieceId == 1L && !it.loading } }
        onMain {
            gated.resume()
            gated.togglePlayPause()
            gated.next()
            gated.skipToQueueEntry(gated.state.value.queue.uids[1])
            gated.previous()
        }
        delay(300)
        assertTrue("nothing reached the piano: ${link.messages}", noteOns().isEmpty())
        assertEquals(PlaybackStatus.Stopped, gated.state.value.status)
        assertEquals(listOf<Long?>(1L, 2L, 1L, 1L, 1L, 2L, 2L, 1L), gate.asked.toList())

        // Kept: nothing now; let go (the check passed), the same start goes.
        gate.mode = TestGate.Mode.KEEP
        onMain { gated.resume() }
        delay(200)
        assertTrue(noteOns().isEmpty())
        gate.mode = TestGate.Mode.OPEN
        onMain { gate.kept!!.invoke() }
        withTimeout(2_000) { while ("90 3C 50" !in link.messages) delay(5) }
        assertTrue(onMain { gated.stopAndFlush(300) })
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
            return PlayablePiece(pieceId, "Piece $pieceId", "Composer", midi, composerKey = "composer$pieceId")
        }

        override suspend fun markPlayed(pieceId: Long) {
            played += pieceId
        }
    }
}
