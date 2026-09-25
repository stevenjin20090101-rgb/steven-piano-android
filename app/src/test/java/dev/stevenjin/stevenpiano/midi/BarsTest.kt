// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class BarsTest {
    private fun sig(tick: Long, numerator: Int, denominator: Int) = TimeSignature(tick, 0L, numerator, denominator)

    private fun bars(durationTicks: Long, vararg signatures: TimeSignature, ppq: Int = 480): List<Long> =
        Bars.startTicks(signatures.toList(), ppq, durationTicks).toList()

    @Test
    fun `4-4 until told otherwise, and bars run until the piece ends`() {
        assertEquals(listOf(0L, 1920L, 3840L, 5760L), bars(7_680))
        assertEquals(listOf(0L, 1920L, 3840L, 5760L, 7680L), bars(7_681))   // one tick into a fifth bar
        assertEquals(listOf(0L), bars(0))                                   // an empty piece still has bar 1
    }

    @Test
    fun `a signature from the start sets every bar's length`() {
        assertEquals(listOf(0L, 1440L, 2880L), bars(4_000, sig(0, 3, 4)))
        assertEquals(listOf(0L, 1440L, 2880L), bars(4_000, sig(0, 6, 8)))
        assertEquals(listOf(0L, 480L), bars(800, sig(0, 2, 8)))                  // two eighths: a quarter a bar
        assertEquals(listOf(0L, 96L, 192L), bars(200, sig(0, 2, 8), ppq = 96))
    }

    @Test
    fun `a change of signature starts a new bar, even mid-bar`() {
        // A pickup of one beat, then 4/4.
        assertEquals(listOf(0L, 480L, 2400L, 4320L), bars(5_000, sig(0, 1, 4), sig(480, 4, 4)))
        // 4/4 cut short at tick 1000 by 3/4.
        assertEquals(listOf(0L, 1000L, 2440L, 3880L), bars(4_000, sig(1000, 3, 4)))
        // A change on a bar line starts no extra bar.
        assertEquals(listOf(0L, 1920L, 3360L), bars(4_000, sig(1920, 3, 4)))
    }

    @Test
    fun `a signature that repeats the metre in force starts nothing`() {
        assertEquals(listOf(0L, 1920L, 3840L), bars(5_000, sig(0, 4, 4), sig(1000, 4, 4)))
        assertEquals(listOf(0L, 1440L, 2880L), bars(4_000, sig(0, 3, 4), sig(700, 3, 4), sig(2000, 3, 4)))
    }

    @Test
    fun `the last of several signatures at one tick wins`() {
        assertEquals(listOf(0L, 1440L, 2880L), bars(4_000, sig(0, 2, 4), sig(0, 3, 4)))
        assertEquals(listOf(0L, 1920L, 3840L), bars(5_000, sig(1000, 3, 4), sig(1000, 4, 4)))   // back to 4/4: no change
    }

    @Test
    fun `unusable signatures are ignored`() {
        val plain = bars(5_000)
        assertEquals(plain, bars(5_000, sig(0, 0, 4)))       // no beats
        assertEquals(plain, bars(5_000, sig(0, 3, 3)))       // not a power of two
        assertEquals(plain, bars(5_000, sig(0, 3, 128)))     // shorter than a 64th
        assertEquals(plain, bars(5_000, sig(1000, 300, 4)))  // more beats than a byte holds
    }

    @Test
    fun `bars never number more than 100,000`() {
        val many = Bars.startTicks(listOf(sig(0, 1, 64)), 480, 10_000_000L)   // 30-tick bars
        assertEquals(Bars.MAX_BARS, many.size)
        assertEquals(30L * (Bars.MAX_BARS - 1), many.last())
    }

    @Test
    fun `bar starts in microseconds follow the tempo map`() {
        val tempo = TempoMap.Builder(480).apply {
            change(0L, 500_000)
            change(1920L, 1_000_000)   // half speed from bar 2
        }.build()
        val starts = Bars.starts(tempo, listOf(TimeSignature.Common), tempo.tickToMicros(5_761L))
        assertEquals(listOf(0L, 2_000_000L, 6_000_000L, 10_000_000L), starts.toList())
    }
}
