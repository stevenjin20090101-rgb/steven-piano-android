// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import dev.stevenjin.stevenpiano.ui.PowerCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The On hours (v1.39 — M76): within them the rail's standing want, outside it none, and a start from afar refused while
 * the piano rests (the keeper's side is in [RailKeeperTest]); and Now playing's power line, its peak per piece.
 */
class OnHoursTest {
    private fun at(hour: Int, minute: Int = 0): ZonedDateTime = ZonedDateTime.of(2026, 11, 1, hour, minute, 0, 0, ZoneId.of("Europe/London"))

    @Test
    fun `within the hours wanted, outside not, 7_00 to 22_00 by default, and past midnight too`() {
        val hours = OnHours()
        assertEquals(OnHours(true, 7 * 60, 22 * 60), hours)
        assertTrue(hours.wanted(at(7)))
        assertTrue(hours.wanted(at(21, 59)))
        assertFalse("22:00 is outside", hours.wanted(at(22)))
        assertFalse(hours.wanted(at(6, 59)))
        assertTrue(hours.outside(at(23)))
        val night = OnHours(start = 20 * 60, end = 2 * 60)
        assertTrue(night.wanted(at(1)))
        assertFalse(night.wanted(at(12)))
        assertFalse("no hours: no standing want, nothing outside", OnHours(on = false).wanted(at(12)) || OnHours(on = false).outside(at(23)))
    }

    @Test
    fun `a panel Play outside the hours is refused while the piano rests, never within them or once a person woke it`() {
        val hours = OnHours()
        assertTrue(hours.refusesRemote(at(23), resting = true))
        assertEquals("The piano is off until 7:00", PowerCopy.offUntil(hours))
        assertFalse("a person at the tablet woke it", hours.refusesRemote(at(23), resting = false))
        assertFalse("within the hours", hours.refusesRemote(at(12), resting = true))
        assertFalse("no hours", OnHours(on = false).refusesRemote(at(23), resting = true))
        assertEquals("Piano off until 7:00 · press Play here to wake it", PowerCopy.asleep(hours, outside = true))
    }

    @Test
    fun `Now playing's power - the watts now under 3 s old, and the peak starts again with each piece`() {
        var clock = 0L
        val power = PiecePower { clock }
        power.pieceStarts()
        power.reading(12.3, playing = true)
        power.reading(145.0, playing = true)
        clock = 1_000
        power.reading(20.0, playing = true)
        assertEquals("20.0 W now · peak 145.0 W this piece", PowerCopy.nowLine(power.state.value.fresh(clock), power.state.value.peak))
        clock = 5_000
        assertNull("a reading 4 s old is none", power.state.value.fresh(clock))
        assertEquals("— W", PowerCopy.nowLine(power.state.value.fresh(clock), power.state.value.peak))
        power.pieceStarts()   // the next piece
        power.reading(9.0, playing = true)
        assertEquals(9.0, power.state.value.peak!!, 1e-9)
        power.reading(30.0, playing = false)
        assertEquals("not playing: no peak", 9.0, power.state.value.peak!!, 1e-9)
    }
}
