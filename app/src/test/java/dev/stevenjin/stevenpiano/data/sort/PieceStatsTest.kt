// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.sort

import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.player.Played
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M82: the measures the Hardest and Most keys sorts read, on made-up notes; and which plays count. */
class PieceStatsTest {
    /** One made-up note: its key, when it starts and ends (ms), its channel (0-based; 9 is the drums). */
    private data class N(val key: Int, val fromMs: Long, val toMs: Long, val channel: Int = 0)

    private fun stats(vararg notes: N): PieceStats = stats(notes.toList())

    private fun stats(notes: List<N>): PieceStats {
        val sorted = notes.sortedBy { it.fromMs }
        return PieceStats.of(
            NoteList(
                LongArray(sorted.size) { sorted[it].fromMs * 1000 },
                LongArray(sorted.size) { sorted[it].toMs * 1000 },
                ByteArray(sorted.size) { sorted[it].key.toByte() },
                ByteArray(sorted.size) { 80 },
                ByteArray(sorted.size) { sorted[it].channel.toByte() },
            ),
        )
    }

    @Test
    fun `a chord's keys are held together, each moment that reaches the peak counted, a key struck again while held one key`() {
        val s = stats(
            N(48, 0, 1000), N(55, 0, 1000), N(60, 5, 1000), N(64, 10, 1000),   // a four-note chord, rolled within 10 ms
            N(67, 1000, 1500),   // let go as the next is struck: never five
            N(48, 2000, 3000), N(52, 2000, 3000), N(55, 2000, 3000), N(60, 2000, 3000),   // the second time at four
            N(60, 3500, 5000), N(60, 4000, 4500), N(62, 4000, 4500),   // 60 again while held: two keys, not three
            N(30, 0, 5000, channel = 9),   // the drums: not the piano's
        )
        assertEquals(4, s.mostKeys)
        assertEquals(2, s.mostKeysMoments)
        assertEquals("the highest key minus the lowest, the drums left out", 67 - 48, s.keyRange)
    }

    @Test
    fun `a fast run is its notes a second, one key at a time`() {
        // 10 a second for 12 s, up and down a semitone at a time between 60 and 66.
        val run = (0 until 120).map { N(60 + (it % 12).let { step -> if (step <= 6) step else 12 - step }, it * 100L, it * 100L + 80) }
        val s = stats(run)
        assertEquals(10.0, s.busiestPerSecond, 1e-9)
        assertEquals(1, s.mostKeys)
        assertEquals(0.0, s.leapsPerSecond, 1e-9)
        assertEquals(6, s.keyRange)
    }

    @Test
    fun `leaps are the semitones jumped between moments more than 7 apart, a chord one moment, per second`() {
        // An octave and a half there and back, 4 times a second for 12 s: the busiest 10 s from the start hold 40 onsets,
        // and 39 jumps of 36 into them.
        val leaps = stats((0 until 48).map { N(if (it % 2 == 0) 36 else 72, it * 250L, it * 250L + 200) })
        assertEquals(39 * 36 / 10.0, leaps.leapsPerSecond, 1e-9)
        assertEquals(4.0, leaps.busiestPerSecond, 1e-9)
        // A chord spanning the jump is no leap: its nearest key is 4 away. Nor is a jump of 7 exactly.
        val near = stats(
            listOf(N(36, 0, 200), N(72, 0, 200), N(40, 250, 450), N(47, 500, 700)) +
                (0 until 40).map { N(47, 1000L + it * 250, 1000L + it * 250 + 200) },
        )
        assertEquals(0.0, near.leapsPerSecond, 1e-9)
    }

    @Test
    fun `a slow intro before a fast middle - the busiest 10 s are the middle, and its leaps are the middle's`() {
        val intro = (0 until 20).map { N(if (it % 2 == 0) 48 else 72, it * 1000L, it * 1000L + 900) }   // big leaps, 1 a second
        val middle = (0 until 80).map { N(72 + it % 3, 20_000L + it * 125, 20_000L + it * 125 + 100) }   // 8 a second, steps
        val outro = (0 until 10).map { N(60, 30_000L + it * 1000, 30_000L + it * 1000 + 900) }
        val s = stats(intro + middle + outro)
        assertEquals(8.0, s.busiestPerSecond, 1e-9)
        assertEquals("the intro's leaps are outside the busiest window", 0.0, s.leapsPerSecond, 1e-9)
        assertEquals(74 - 48, s.keyRange)
    }

    @Test
    fun `no notes, or only drums, measure nothing`() {
        assertEquals(PieceStats.None, stats())
        assertEquals(PieceStats.None, stats(N(36, 0, 100, channel = 9)))
        // A piece shorter than the window: its notes a second over its own span (two notes 1 s apart: a second at least).
        assertEquals(2.0, stats(N(60, 0, 100), N(62, 1000, 1100)).busiestPerSecond, 1e-9)
    }

    @Test
    fun `a play counts when the piece ended or sounded 30 s, never for a start or a skip`() {
        assertTrue(Plays.counts(Played(1, ended = true, soundedMs = 2_000)))   // a short piece to its end
        assertTrue(Plays.counts(Played(1, ended = false, soundedMs = 30_000)))
        assertFalse(Plays.counts(Played(1, ended = false, soundedMs = 29_999)))
        assertFalse("stopped at once, or replaced before a note", Plays.counts(Played(1, ended = false)))
    }
}
