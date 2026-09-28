// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.channels

import dev.stevenjin.stevenpiano.ble.FakePianoLink
import dev.stevenjin.stevenpiano.midi.SmfBuilder
import dev.stevenjin.stevenpiano.midi.SmfParser
import dev.stevenjin.stevenpiano.player.PieceSource
import dev.stevenjin.stevenpiano.player.PlayablePiece
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.player.QueueSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Endless play from a channel's pool: the first 25, a top-up of 10 whenever fewer than 5 are up
 * next, no repeats until the pool is exhausted, a fresh shuffle without the last ones dealt, the
 * channel ending when the player's channel stops being it (stop, a piece played from a list), and
 * the channel's volume on the piano or as the app's velocity, put back when it ends.
 */
class ChannelPlayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val deck = FakeDeck()
    private val piano = FakePiano()
    private val pools = HashMap<String, List<Long>>()
    private val volumes = HashMap<String, Int>()
    private val channels = ChannelPlayer(deck, pools::get, { volumes[it] ?: ChannelPlayer.DEFAULT_VOLUME }, piano, scope, Random(17)).also { it.start() }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a channel starts with 25 of its pool, shuffled, and tops up 10 when fewer than 5 are up next`() {
        pools["everything"] = (1L..100L).toList()
        assertTrue(channels.play("everything"))
        val first = deck.queue
        assertEquals(25, first.size)
        assertEquals("everything", deck.state.value.channel)
        assertTrue(first != (1L..25L).toList())   // shuffled
        repeat(20) { deck.advance() }   // 4 up next now: under 5
        assertEquals(35, deck.queue.size)
        assertEquals(first, deck.queue.take(25))
        assertEquals(listOf("everything"), deck.topUps.distinct())
    }

    @Test
    fun `nothing repeats until the pool is exhausted, and a fresh deck leaves out the last 20 dealt`() {
        pools["romantic"] = (1L..60L).toList()
        channels.play("romantic")
        while (deck.queue.size < 60) deck.advance()
        val firstPass = deck.queue.take(60)
        assertEquals((1L..60L).toSet(), firstPass.toSet())   // every piece once
        while (deck.queue.size < 100) deck.advance()
        val secondPass = deck.queue.subList(60, 100)
        assertTrue(secondPass.take(40).none { it in firstPass.takeLast(20) })
        assertEquals(40, secondPass.take(40).toSet().size)
    }

    @Test
    fun `a small pool still shuffles, leaving out at most half of it`() {
        pools["calm"] = listOf(7L, 8L, 9L)
        channels.play("calm")
        val dealt = deck.queue
        assertEquals(25, dealt.size)
        assertEquals(setOf(7L, 8L, 9L), dealt.take(3).toSet())
        dealt.zipWithNext().forEach { (a, b) -> assertTrue("$dealt", a != b) }   // never the same piece twice running
    }

    @Test
    fun `a pool under three pieces, or one not worked out yet, does not play`() {
        pools["nocturnes"] = listOf(1L, 2L)
        assertFalse(channels.play("nocturnes"))
        assertFalse(channels.play("unknown"))
        assertTrue(deck.queue.isEmpty())
        assertNull(deck.state.value.channel)
    }

    @Test
    fun `stop ends the channel, and a piece played from a list ends it too`() {
        pools["everything"] = (1L..40L).toList()
        channels.play("everything")
        channels.stop()
        assertNull(deck.state.value.channel)
        assertNull(channels.playing)
        repeat(30) { deck.advance() }
        assertEquals("no more top-ups", 25, deck.queue.size)

        channels.play("everything")
        deck.playPiece(99)   // a tap in the Library
        assertNull(channels.playing)
        assertEquals(listOf(99L), deck.queue)
    }

    @Test
    fun `on a piano with a volume the channel holds it, and puts the piano's back when it ends`() {
        piano.volume = 85
        volumes["calm"] = 40
        pools["calm"] = (1L..10L).toList()
        channels.play("calm")
        assertEquals(listOf("hold 40"), piano.calls)
        assertEquals(100, deck.state.value.velocityPct)   // the app's velocity is not touched
        channels.stop()
        assertEquals(listOf("hold 40", "release 85"), piano.calls)
    }

    @Test
    fun `a piano at full power gets its full power back with its volume`() {
        piano.volume = 100
        piano.fullPower = true
        pools["calm"] = (1L..10L).toList()
        channels.play("calm")
        channels.stop()
        assertEquals(listOf("hold 70", "release 100 full"), piano.calls)
    }

    @Test
    fun `without a piano volume the channel sets the app's velocity, and puts it back unless the person changed it`() {
        volumes["calm"] = 60
        pools["calm"] = (1L..10L).toList()
        channels.play("calm")
        assertEquals(80, deck.state.value.velocityPct)   // 50 + 60 / 2
        channels.stop()
        assertEquals(100, deck.state.value.velocityPct)

        channels.play("calm")
        deck.setVelocity(120)   // the person moved Velocity meanwhile
        channels.stop()
        assertEquals(120, deck.state.value.velocityPct)
        assertTrue(piano.calls.isEmpty())
    }

    @Test
    fun `a channel that follows another keeps the first one's volume to put back`() {
        piano.volume = 90
        volumes["calm"] = 30
        volumes["epic"] = 100
        pools["calm"] = (1L..10L).toList()
        pools["epic"] = (11L..20L).toList()
        channels.play("calm")
        channels.play("epic")
        assertEquals("epic", channels.playing)
        channels.stop()
        assertEquals(listOf("hold 30", "hold 100", "release 90"), piano.calls)
    }

    @Test
    fun `a new volume for the channel playing is heard at once`() {
        pools["calm"] = (1L..10L).toList()
        channels.play("calm")
        channels.volumeChanged("epic", 10)   // not playing: nothing now
        assertEquals(85, deck.state.value.velocityPct)
        channels.volumeChanged("calm", 20)
        assertEquals(60, deck.state.value.velocityPct)
        channels.stop()
        assertEquals(100, deck.state.value.velocityPct)
    }

    @Test
    fun `the channel volume becomes a velocity of 50 to 100 percent`() {
        assertEquals(50, ChannelPlayer.velocityFor(0))
        assertEquals(85, ChannelPlayer.velocityFor(70))
        assertEquals(100, ChannelPlayer.velocityFor(100))
        assertEquals(100, ChannelPlayer.velocityFor(250))
    }

    @Test
    fun `on the real player a channel keeps its run from its first moment, with a watcher that runs at once`() = runBlocking {
        // The app's watcher runs on Dispatchers.Main.immediate: an update made on the main thread
        // reaches it at once, in the middle of whatever made it. Unconfined does the same here.
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val playerScope = CoroutineScope(SupervisorJob() + main)
        val player = Player(FakePianoLink(), ShortPieces, playerScope, prepareThread = {})
        val piano = FakePiano().apply { volume = 100 }
        val watcher = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val channels = ChannelPlayer(player, { (1L..40L).toList() }, { 70 }, piano, watcher, Random(3))
        try {
            withContext(main) {
                channels.start()
                assertTrue(channels.play("calm"))
            }
            assertEquals("calm", channels.playing)
            assertEquals("calm", player.state.value.channel)
            assertEquals("held, and not let go straight after", listOf("hold 70"), piano.calls)
            assertEquals(25, player.state.value.queue.ids.size)
            withContext(main) { player.stopAndFlush(300) }
            assertNull(channels.playing)
            assertEquals(listOf("hold 70", "release 100"), piano.calls)
        } finally {
            watcher.cancel()
            playerScope.cancel()
            main.close()
        }
    }

    /** Every piece a short note, for the real player. */
    private object ShortPieces : PieceSource {
        override suspend fun load(pieceId: Long): PlayablePiece = PlayablePiece(
            pieceId,
            "Piece $pieceId",
            "Composer",
            SmfParser.parse(SmfBuilder(format = 0, division = 1000).track { tempo(0, 1_000_000); noteOn(0, 60); noteOff(5_000, 60) }.build()),
        )

        override suspend fun markPlayed(pieceId: Long) = Unit
    }

    /** A player whose queue the test moves on by hand. */
    private class FakeDeck : ChannelDeck {
        private val flow = MutableStateFlow(PlayerState())
        override val state: StateFlow<PlayerState> = flow
        val topUps = ArrayList<String?>()
        val queue: List<Long> get() = flow.value.queue.ids

        override fun playAll(pieceIds: List<Long>, shuffle: Boolean, channel: String?) {
            flow.update { it.copy(queue = snapshot(pieceIds, 0), channel = channel) }
        }

        override fun addToQueue(pieceIds: List<Long>, channel: String?): Boolean {
            topUps += channel
            flow.update { it.copy(queue = snapshot(it.queue.ids + pieceIds, it.queue.index)) }
            return false
        }

        override fun stop() = flow.update { it.copy(channel = null) }

        override fun setVelocity(pct: Int) = flow.update { it.copy(velocityPct = pct) }

        /** The piece playing ended, and the next began. */
        fun advance() = flow.update { it.copy(queue = snapshot(it.queue.ids, it.queue.index + 1)) }

        /** A piece tapped in the Library: a queue of its own, and no channel. */
        fun playPiece(id: Long) = flow.update { it.copy(queue = snapshot(listOf(id), 0), channel = null) }

        private fun snapshot(ids: List<Long>, index: Int) = QueueSnapshot(ids, ids.indices.map { it + 1L }, index)
    }

    /** The piano's volume, as the console would see the calls. */
    private class FakePiano : PianoVolume {
        var volume: Int? = null
        var fullPower = false
        val calls = ArrayList<String>()

        override fun current(): PianoLoudness? = volume?.let { PianoLoudness(it, fullPower) }

        override fun hold(pct: Int) {
            calls += "hold $pct"
        }

        override fun release(previous: PianoLoudness) {
            calls += "release ${previous.volume}" + if (previous.fullPower) " full" else ""
        }
    }
}
