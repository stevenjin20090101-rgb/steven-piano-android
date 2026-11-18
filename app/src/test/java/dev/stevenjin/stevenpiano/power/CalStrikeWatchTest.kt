// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import dev.stevenjin.stevenpiano.piano.LoudnessSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The power watch and the loudness steps' `calstrike` (M83; piano safety): a strike goes over the piano's console, not the
 * router, so it counts as that key held for its 100 ms and the release lag ([LoudnessSteps.heldSpan],
 * [HeldKeys.struckOutside]), and no more. Over a calibration's strikes, one every 310 ms, the watches never trip; a coil left
 * on outside a strike, or a board latched on, still trips them. The supply: 12 W idle, a coil's 12 W from when the piano
 * struck it (the line's way there) for 100 ms and its release tick; each reading the mean of the second that ended a lag
 * before it was asked.
 */
class CalStrikeWatchTest {
    /** A calibration from [START] for [ms]: its strikes as the wiring records them, and the supply behind them. */
    private class Calibration(val ms: Long) {
        val keys = HeldKeys { now }
        var now = 0L
        var stuckFrom: Long? = null
        var boardFrom: Long? = null
        private val draw = DoubleArray((ms / STEP_MS).toInt()) { IDLE }

        /** When each line went. */
        private val sent = generateSequence(0L) { it + STRIKE_EVERY_MS }.takeWhile { it + STRIKE_SPAN_MS < ms }.toList()
        private var recorded = 0

        init {
            // The coil: on from when the piano read the line, its 100 ms, then the verified OFF on the next tick.
            for (at in sent) add(at + LINK_MS, at + LINK_MS + LoudnessSteps.STRIKE_MS + TICK_MS, PowerWatch.COIL_WATTS)
        }

        /** The wiring's records up to [at]: each strike once its reply came, as [LoudnessSteps.heldSpan] spans it. */
        private fun recordTo(at: Long) {
            while (recorded < sent.size && sent[recorded] + 2 * LINK_MS <= at) {
                val line = sent[recorded++]
                now = line + 2 * LINK_MS
                val (from, to) = LoudnessSteps.heldSpan(line, now, LoudnessSteps.STRIKE_MS)
                keys.struckOutside(from, to)
            }
            now = at
        }

        private fun add(from: Long, to: Long, watts: Double) {
            for (i in (from / STEP_MS).toInt() until minOf(draw.size, (to / STEP_MS).toInt())) draw[i] += watts
        }

        /** The plug's reading asked at [at], [lag] ms late: the mean of the second before. */
        private fun reading(at: Long, lag: Long): Double {
            val to = ((at - lag) / STEP_MS).toInt().coerceIn(0, draw.size)
            val from = (to - (1_000 / STEP_MS).toInt()).coerceAtLeast(0)
            if (to <= from) return IDLE
            return (from until to).sumOf { draw[it] } / (to - from)
        }

        /** Every second, the board rule and the floor watch as the keeper runs them while a piece (or this) plays. */
        fun watch(lag: Long): Pair<Long?, Long?> {
            stuckFrom?.let { add(it, ms, PowerWatch.COIL_WATTS) }
            boardFrom?.let { add(it, ms, BOARD_WATTS) }
            val board = BoardWatch(IDLE)
            val floor = PowerWatch(IDLE)
            var boardAt: Long? = null
            var floorAt: Long? = null
            var at = 2_000L
            var looks = 0
            var floorNext = at
            while (at < ms) {
                recordTo(at)
                val watts = reading(at, lag)
                val wide = PowerWatch.sample(at, at, watts, ::held, { _, _ -> false }, true, lagMs = BoardWatch.LAG_MS)
                if (boardAt == null && board.playing(wide) is PowerVerdict.BoardOn) boardAt = at
                if (at >= floorNext) {
                    floorNext = at + PowerWatch.lookIn(looks++)
                    val sample = PowerWatch.sample(at, at, watts, ::held, { _, _ -> false }, true)
                    if (floorAt == null && floor.reading(sample) is PowerVerdict.Stuck) floorAt = at
                }
                at += 1_000
            }
            return boardAt to floorAt
        }

        private fun held(from: Long, to: Long): Double = keys.held(from, to, HeldKeys.HOLD_CEILING_MS)
    }

    @Test
    fun `a calstrike counts as its key held for its 100 ms and the release lag, from the line going, and no more`() {
        val (from, to) = LoudnessSteps.heldSpan(sentMs = 1_000, repliedMs = 1_040, struckMs = 100)
        assertEquals(1_000L, from)
        assertEquals("the reply, its 100 ms, the release lag", 1_040L + 100 + LoudnessSteps.RELEASE_LAG_MS, to)
        val keys = HeldKeys { 2_000L }
        assertFalse(keys.struckSince(0))
        keys.struckOutside(from, to)
        assertEquals(1.0, keys.held(from, to, HeldKeys.HOLD_CEILING_MS), 1e-9)
        assertEquals("none of it after", 0.0, keys.held(to, to + 1_000, HeldKeys.HOLD_CEILING_MS), 1e-9)
        assertEquals("none before", 0.0, keys.held(0, from, HeldKeys.HOLD_CEILING_MS), 1e-9)
        assertEquals("a second's share", (to - from) / 1_000.0, keys.held(1_000, 2_000, HeldKeys.HOLD_CEILING_MS), 1e-9)
        assertTrue("a strike, for the check after", keys.struckSince(from))
        assertFalse(keys.struckSince(from + 1))
        keys.struckOutside(5_000, 5_000)
        assertFalse("nothing for a span of nothing", keys.struckSince(5_000))
    }

    @Test
    fun `ten minutes of strikes fill the strokes' ring without losing the latest`() {
        var clock = 0L
        val keys = HeldKeys { clock }
        for (i in 0 until 2_000) {
            clock = i * STRIKE_EVERY_MS
            keys.struckOutside(clock, clock + 150)
        }
        assertEquals(150.0 / 1_000, keys.held(clock, clock + 1_000, HeldKeys.HOLD_CEILING_MS), 1e-9)
    }

    @Test
    fun `a calibration's strikes never trip the board rule or the floor watch, however late the plug's readings`() {
        for (lag in LAGS) {
            val (board, floor) = Calibration(120_000).watch(lag)
            assertNull("$lag ms late: board", board)
            assertNull("$lag ms late: floor", floor)
        }
    }

    @Test
    fun `a coil left on outside a strike still trips the floor watch, and a board latched on the board rule`() {
        for (lag in LAGS) {
            val coil = Calibration(120_000).apply { stuckFrom = 20_000 }.watch(lag).second
            assertNotNull("$lag ms late: the coil found", coil)
            assertTrue("$lag ms late: within 25 s (${coil!! - 20_000} ms)", coil - 20_000 <= 25_000)
            val board = Calibration(120_000).apply { boardFrom = 20_000 }.watch(lag).first
            assertNotNull("$lag ms late: the board found", board)
            assertTrue("$lag ms late: within 9 s (${board!! - 20_000} ms)", board - 20_000 <= 9_000)
        }
    }

    private companion object {
        val LAGS = longArrayOf(0, 500, 1_000)
        const val STEP_MS = 10L
        const val IDLE = 12.0
        const val BOARD_WATTS = 144.0
        const val STRIKE_EVERY_MS = 310L
        const val STRIKE_SPAN_MS = 400L

        /** The line's way to the piano (and its reply's back), and the firmware's release tick. */
        const val LINK_MS = 20L
        const val TICK_MS = 10L
    }
}
