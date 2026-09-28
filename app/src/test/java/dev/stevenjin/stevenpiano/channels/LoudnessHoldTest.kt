// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.channels

import dev.stevenjin.stevenpiano.player.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The loudness a channel or a schedule holds, shared: whoever holds it last owns it, and what comes back is what was there first. */
class LoudnessHoldTest {
    private val deck = VelocityDeck()
    private val piano = Piano()
    private val hold = LoudnessHold(piano, deck)
    private val channel = Any()
    private val schedule = Any()

    @Test
    fun `a later owner takes over and keeps what was there first, and a superseded owner's release changes nothing`() {
        piano.loudness = PianoLoudness(90, fullPower = true)
        hold.hold(schedule, 60)
        piano.loudness = PianoLoudness(60, fullPower = false)   // as the piano now shows it
        hold.hold(channel, 40)
        assertSame(channel, hold.holder)
        hold.release(schedule)
        assertEquals("the schedule no longer holds it", listOf("hold 60", "hold 40"), piano.calls)
        hold.release(channel)
        assertEquals(listOf("hold 60", "hold 40", "release 90 full"), piano.calls)
        assertNull(hold.holder)
        hold.release(channel)
        assertEquals("released once", 3, piano.calls.size)
    }

    @Test
    fun `without the piano's volume it is the app's velocity, put back unless the person changed it`() {
        hold.hold(schedule, 40)
        assertEquals(70, deck.state.value.velocityPct)
        hold.hold(channel, 100)
        assertEquals(100, deck.state.value.velocityPct)
        hold.release(channel)
        assertEquals("what was there before the first hold", 100, deck.state.value.velocityPct)
        hold.hold(schedule, 0)
        assertEquals(50, deck.state.value.velocityPct)
        deck.setVelocity(120)   // the person
        hold.release(schedule)
        assertEquals(120, deck.state.value.velocityPct)
    }

    private class VelocityDeck : ChannelDeck {
        private val flow = MutableStateFlow(PlayerState())
        override val state: StateFlow<PlayerState> = flow

        override fun playAll(pieceIds: List<Long>, shuffle: Boolean, channel: String?) = Unit

        override fun addToQueue(pieceIds: List<Long>, channel: String?): Boolean = false

        override fun stop() = Unit

        override fun setVelocity(pct: Int) = flow.update { it.copy(velocityPct = pct) }
    }

    private class Piano : PianoVolume {
        var loudness: PianoLoudness? = null
        val calls = mutableListOf<String>()

        override fun current(): PianoLoudness? = loudness

        override fun hold(pct: Int) {
            calls += "hold $pct"
        }

        override fun release(previous: PianoLoudness) {
            calls += "release ${previous.volume}" + if (previous.fullPower) " full" else ""
        }
    }
}
