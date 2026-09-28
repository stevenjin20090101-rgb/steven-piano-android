// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import dev.stevenjin.stevenpiano.ble.ConsoleChannel
import dev.stevenjin.stevenpiano.ble.FakePianoLink
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.PianoLink
import dev.stevenjin.stevenpiano.channels.ChannelDeck
import dev.stevenjin.stevenpiano.channels.LoudnessHold
import dev.stevenjin.stevenpiano.channels.PianoLoudness
import dev.stevenjin.stevenpiano.channels.PianoVolume
import dev.stevenjin.stevenpiano.data.db.ScheduleEntity
import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import dev.stevenjin.stevenpiano.midi.MidiBatch
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.player.QueueSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A schedule's start on virtual time: the piano asked for and waited for (20 s at most, then the
 * start is missed and says so), its settings waited for, then the play at the schedule's volume,
 * held and put back as a channel's is; and its end stopping only what it started.
 */
class ScheduleRunnerTest {
    private val wednesday = ZonedDateTime.of(LocalDateTime.parse("2026-09-30T12:30"), ZoneId.of("America/New_York"))
    private val start = Occurrence(1, Edge.START, wednesday)
    private val end = Occurrence(1, Edge.END, wednesday.plusMinutes(45))
    private val deck = FakeDeck()
    private val volume = FakePiano()
    private val outcomes = MemoryOutcomes()
    private val trail = mutableListOf<String>()
    private val piano = MutableStateFlow<PianoState>(PianoState.Ready(mapOf("volume" to "85"), emptyMap()))
    private var services = 0

    private fun schedule(kind: ScheduleKind, target: String, volumePct: Int? = 60, id: Long = 1) =
        ScheduleEntity(id, Occurrences.WEEKDAYS, 750, kind, target, 795, volumePct, true, createdAt = 0)

    private fun TestScope.runner(link: PianoLink = FakePianoLink()): ScheduleRunner = ScheduleRunner(
        link = link,
        lastAddress = { "AA:BB:CC:DD:EE:FF" },
        piano = piano,
        deck = deck,
        loudness = LoudnessHold(volume, deck),
        startService = { services++ },
        outcomes = outcomes,
        scope = backgroundScope,
        log = { trail += it },
    ).also {
        it.start()
        runCurrent()
    }

    @Test
    fun `the piano connects two seconds after the alarm, and the playlist plays at the schedule's volume`() = runTest {
        val link = SlowLink(backgroundScope, connectAfterMs = 2_000)
        val runner = runner(link)
        runner.prepare()
        assertEquals("the playback service came up at the alarm", 1, services)
        assertTrue(runner.starting.value)
        runner.fire(start, schedule(ScheduleKind.PLAYLIST, "10"))
        assertEquals("asked for the last piano", listOf("AA:BB:CC:DD:EE:FF"), link.connects)
        assertEquals("played once connected", 2_000L, currentTime)
        assertEquals(listOf("playAll [1, 2, 3]"), deck.calls)
        assertEquals(listOf("hold 60"), volume.calls)
        assertEquals("Last: Wednesday 12:30, Evening", outcomes.line)
        assertFalse(runner.starting.value)
        assertTrue(trail.single(), trail.single().startsWith("Schedule started: Wednesday 12:30 (playlist 10"))
    }

    @Test
    fun `a piano that never comes makes the start missed after 20 s, in the link's trail and on the page`() = runTest {
        val runner = runner(SlowLink(backgroundScope, connectAfterMs = null))
        runner.prepare()
        runner.fire(start, schedule(ScheduleKind.CHANNEL, "calm"))
        assertEquals(20_000L, currentTime)
        assertEquals(emptyList<String>(), deck.calls)
        assertEquals(emptyList<String>(), volume.calls)
        assertEquals(listOf("Missed: Wednesday 12:30 (piano not connected)"), trail)
        assertEquals("Missed: Wednesday 12:30 (piano not connected)", outcomes.line)
        assertFalse("the service may go", runner.starting.value)
    }

    @Test
    fun `connected already it plays at once, after waiting a moment for the piano's settings`() = runTest {
        piano.value = PianoState.Unknown
        backgroundScope.launch {
            delay(1_200)
            piano.value = PianoState.Ready(mapOf("volume" to "85"), emptyMap())
        }
        val runner = runner(FakePianoLink())
        runner.fire(start, schedule(ScheduleKind.PIECE, "5"))
        assertEquals("the settings came after 1.2 s", 1_200L, currentTime)
        assertEquals(listOf("play 5"), deck.calls)
        assertEquals(listOf("hold 60"), volume.calls)
    }

    @Test
    fun `a channel plays at the schedule's volume through the channels, and its end stops it`() = runTest {
        val runner = runner()
        runner.fire(start, schedule(ScheduleKind.CHANNEL, "calm", volumePct = 40))
        assertEquals(listOf("channel calm 40"), deck.calls)
        assertEquals("the channel holds its own volume", emptyList<String>(), volume.calls)
        runCurrent()
        runner.end(end)
        assertEquals(listOf("channel calm 40", "stop"), deck.calls)
        assertTrue(trail.last(), trail.last().startsWith("Schedule ended: Wednesday 13:15"))
    }

    @Test
    fun `its end stops only what it started, so a piece the person chose meanwhile plays on at the person's volume`() = runTest {
        val runner = runner()
        runner.fire(start, schedule(ScheduleKind.PLAYLIST, "10"))
        deck.playing()
        runCurrent()
        deck.personPlays(42)
        runCurrent()
        assertEquals("the volume came back as the person took over", listOf("hold 60", "release 85"), volume.calls)
        runner.end(end)
        assertEquals("nothing stopped", listOf("playAll [1, 2, 3]"), deck.calls)
        assertEquals(listOf("hold 60", "release 85"), volume.calls)
    }

    @Test
    fun `its end stops the playlist still playing and puts the volume back`() = runTest {
        val runner = runner()
        runner.fire(start, schedule(ScheduleKind.PLAYLIST, "10"))
        deck.playing()
        runCurrent()
        deck.nextPiece()
        runCurrent()
        runner.end(end)
        assertEquals(listOf("playAll [1, 2, 3]", "stop"), deck.calls)
        assertEquals(listOf("hold 60", "release 85"), volume.calls)
    }

    @Test
    fun `the gap between two pieces is not an end, but a list that has run out is, and gives the volume back`() = runTest {
        val runner = runner()
        runner.fire(start, schedule(ScheduleKind.PLAYLIST, "10"))
        runCurrent()
        deck.loaded()   // the real player's moment between the piece loaded and its play published
        runCurrent()
        advanceTimeBy(ScheduleRunner.IDLE_GRACE_MS + 1_000)
        assertEquals("loaded, not yet played: still its run", listOf("hold 60"), volume.calls)
        deck.playing()
        runCurrent()
        deck.stopped()   // the gap after the first piece
        runCurrent()
        advanceTimeBy(1_500)
        deck.playing()   // the second piece
        runCurrent()
        advanceTimeBy(ScheduleRunner.IDLE_GRACE_MS + 1_000)
        assertEquals(listOf("hold 60"), volume.calls)
        deck.stopped()   // the list ran out
        runCurrent()
        advanceTimeBy(ScheduleRunner.IDLE_GRACE_MS + 1)
        runCurrent()
        assertEquals(listOf("hold 60", "release 85"), volume.calls)
        runner.end(end)
        assertEquals("nothing left to stop", listOf("playAll [1, 2, 3]"), deck.calls)
    }

    @Test
    fun `a schedule that follows another takes the loudness over and keeps what comes back`() = runTest {
        val runner = runner()
        runner.fire(start, schedule(ScheduleKind.PLAYLIST, "10", volumePct = 60))
        deck.playing()
        runCurrent()
        runner.fire(Occurrence(2, Edge.START, wednesday.plusMinutes(10)), schedule(ScheduleKind.PIECE, "5", volumePct = 30, id = 2))
        runCurrent()
        assertEquals(listOf("hold 60", "hold 30"), volume.calls)
        runner.end(end)
        assertEquals("the first one's end touches nothing now", listOf("playAll [1, 2, 3]", "play 5"), deck.calls)
        runner.end(Occurrence(2, Edge.END, wednesday.plusMinutes(40)))
        assertEquals(listOf("hold 60", "hold 30", "release 85"), volume.calls)
    }

    @Test
    fun `what can't play is missed with its reason, and what played before plays on`() = runTest {
        val runner = runner()
        deck.playlists[11] = emptyList()
        runner.fire(start, schedule(ScheduleKind.PLAYLIST, "11"))
        assertEquals("Missed: Wednesday 12:30 (the playlist has no pieces)", outcomes.line)
        runner.fire(start, schedule(ScheduleKind.PIECE, "99"))
        assertEquals("Missed: Wednesday 12:30 (the piece was deleted)", outcomes.line)
        runner.fire(start, schedule(ScheduleKind.CHANNEL, "tiny"))
        assertEquals("Missed: Wednesday 12:30 (the channel needs more pieces)", outcomes.line)
        assertEquals(emptyList<String>(), deck.calls)
        assertEquals(emptyList<String>(), volume.calls)
    }

    @Test
    fun `while the piano's firmware is updated a start is missed, in the link's trail and on the page, and nothing plays`() = runTest {
        val link = SlowLink(backgroundScope, connectAfterMs = 2_000)
        val runner = runner(link)
        deck.locked = true
        runner.prepare()
        runner.fire(start, schedule(ScheduleKind.CHANNEL, "calm"))
        assertEquals("not even the piano was asked for", emptyList<String?>(), link.connects)
        assertEquals(emptyList<String>(), deck.calls)
        assertEquals(emptyList<String>(), volume.calls)
        assertEquals(listOf("Missed: Wednesday 12:30 (the piano was updating)"), trail)
        assertEquals("Missed: Wednesday 12:30 (the piano was updating)", outcomes.line)
        assertFalse("the service may go", runner.starting.value)
    }

    @Test
    fun `an update that begins while the schedule waits for the piano turns it away too`() = runTest {
        val runner = runner(SlowLink(backgroundScope, connectAfterMs = 2_000))
        backgroundScope.launch {
            delay(1_000)
            deck.locked = true
        }
        runner.fire(start, schedule(ScheduleKind.PLAYLIST, "10"))
        assertEquals("judged once the piano came", 2_000L, currentTime)
        assertEquals(emptyList<String>(), deck.calls)
        assertEquals(emptyList<String>(), volume.calls)
        assertEquals(listOf("Missed: Wednesday 12:30 (the piano was updating)"), trail)
    }

    @Test
    fun `a schedule without a volume leaves the piano as it is`() = runTest {
        val runner = runner()
        runner.fire(start, schedule(ScheduleKind.PLAYLIST, "10", volumePct = null))
        assertEquals(listOf("playAll [1, 2, 3]"), deck.calls)
        assertEquals(emptyList<String>(), volume.calls)
    }

    /** A link that is not connected, and connects [connectAfterMs] after it is asked (never, when null). */
    private class SlowLink(private val scope: CoroutineScope, private val connectAfterMs: Long?) : PianoLink {
        private val flow = MutableStateFlow<LinkState>(LinkState.Disconnected)
        override val state: StateFlow<LinkState> = flow
        override val console: ConsoleChannel? = null
        val connects = mutableListOf<String?>()

        override fun connect(address: String?) {
            connects += address
            flow.value = LinkState.Scanning
            val after = connectAfterMs ?: return
            scope.launch {
                delay(after)
                flow.value = LinkState.Connected("Steven Piano", 255)
            }
        }

        override fun disconnect() {
            flow.value = LinkState.Disconnected
        }

        override fun send(batch: MidiBatch, dropPending: Boolean) = Unit

        override fun flush(timeoutMs: Long): Boolean = true

        override fun emergencySilence(timeoutMs: Long): Boolean = false
    }

    /** The player and the channels as a schedule sees them; every play written down, the state moved on by hand. */
    private class FakeDeck : ScheduleDeck, ChannelDeck {
        private val flow = MutableStateFlow(PlayerState())
        override val state: StateFlow<PlayerState> = flow

        /** The player's lock, as a firmware update holds it. */
        override var locked = false
        val calls = mutableListOf<String>()
        val playlists = mutableMapOf(10L to listOf(1L, 2L, 3L))
        private var nextUid = 1L

        override suspend fun playChannel(key: String, volumePct: Int?): Boolean {
            if (key != "calm") return false
            calls += "channel $key $volumePct"
            flow.update { it.copy(channel = key, queue = queue(listOf(1L, 2L)), status = PlaybackStatus.Playing) }
            return true
        }

        override suspend fun playlistPieces(id: Long): List<Long> = playlists[id].orEmpty()

        override suspend fun hasPiece(id: Long): Boolean = id in 1L..10L

        override suspend fun nameOf(kind: ScheduleKind, target: String): String = when (kind) {
            ScheduleKind.CHANNEL -> "Calm"
            ScheduleKind.PLAYLIST -> "Evening"
            ScheduleKind.PIECE -> "Clair de lune"
        }

        override fun playAll(ids: List<Long>) {
            calls += "playAll $ids"
            flow.update { it.copy(channel = null, queue = queue(ids), loading = true, status = PlaybackStatus.Stopped) }
        }

        override fun play(id: Long) {
            calls += "play $id"
            flow.update { it.copy(channel = null, queue = queue(listOf(id)), loading = true, status = PlaybackStatus.Stopped) }
        }

        override fun stop() {
            calls += "stop"
            flow.update { it.copy(channel = null, loading = false, status = PlaybackStatus.Stopped) }
        }

        override fun playAll(pieceIds: List<Long>, shuffle: Boolean, channel: String?) = error("A schedule plays channels through the channels")

        override fun addToQueue(pieceIds: List<Long>, channel: String?): Boolean = error("not a schedule's")

        override fun setVelocity(pct: Int) = flow.update { it.copy(velocityPct = pct) }

        /** The piece loaded and playing. */
        fun playing() = flow.update { it.copy(loading = false, status = PlaybackStatus.Playing) }

        /** Loaded, and the player not yet playing it. */
        fun loaded() = flow.update { it.copy(loading = false, status = PlaybackStatus.Stopped) }

        /** Between two pieces, or at the end of the list. */
        fun stopped() = flow.update { it.copy(loading = false, status = PlaybackStatus.Stopped) }

        fun nextPiece() = flow.update { it.copy(queue = it.queue.copy(index = it.queue.index + 1)) }

        /** The person played a piece from the Library: a queue of its own. */
        fun personPlays(id: Long) = flow.update { it.copy(queue = queue(listOf(id)), loading = false, status = PlaybackStatus.Playing) }

        private fun queue(ids: List<Long>): QueueSnapshot = QueueSnapshot(ids, ids.map { nextUid++ }, 0)
    }

    /** The piano's own volume, as the console would see the calls. */
    private class FakePiano : PianoVolume {
        val calls = mutableListOf<String>()

        override fun current(): PianoLoudness = PianoLoudness(85, fullPower = false)

        override fun hold(pct: Int) {
            calls += "hold $pct"
        }

        override fun release(previous: PianoLoudness) {
            calls += "release ${previous.volume}"
        }
    }

    private class MemoryOutcomes : ScheduleOutcomes {
        private val flow = MutableStateFlow<String?>(null)
        override val last: Flow<String?> = flow
        val line: String? get() = flow.value

        override suspend fun record(line: String) {
            flow.value = line
        }
    }
}
