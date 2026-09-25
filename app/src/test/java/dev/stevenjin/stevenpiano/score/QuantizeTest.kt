// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.midi.TempoMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantizeTest {
    private val tempo = TempoMap.Builder(480).apply {
        change(0L, 500_000)
        change(1_000L, 733_333)   // rubato written as tempo changes, off any grid
        change(2_345L, 401_234)
    }.build()

    private fun startsAtTicks(vararg ticks: Long) = LongArray(ticks.size) { tempo.tickToMicros(ticks[it]) }

    @Test
    fun `sixteenths are on the grid in ticks, whatever the tempo does`() {
        assertTrue(Quantize.onGrid(startsAtTicks(0, 120, 240, 1_080, 2_400, 2_520, 4_800), tempo))
    }

    @Test
    fun `a performance is not`() {
        val random = java.util.Random(7)
        val starts = LongArray(200) { it * 250_000L + random.nextInt(60_000) }
        assertFalse(Quantize.onGrid(starts, TempoMap.constant(384)))
    }

    @Test
    fun `an onset counts within 12 percent of a sixteenth`() {
        assertTrue(Quantize.onGrid(startsAtTicks(12), tempo))     // 10 % late
        assertTrue(Quantize.onGrid(startsAtTicks(108), tempo))    // 10 % early
        assertFalse(Quantize.onGrid(startsAtTicks(18), tempo))    // 15 %
        assertFalse(Quantize.onGrid(startsAtTicks(160), tempo))   // a triplet eighth
    }

    @Test
    fun `a file is sequenced when 80 percent of its onsets are on the grid`() {
        val on = LongArray(8) { it * 120L }
        assertTrue(Quantize.onGrid(startsAtTicks(*on, 160, 400), tempo))        // 8 of 10
        assertFalse(Quantize.onGrid(startsAtTicks(*on, 160, 400, 1_000), tempo)) // 8 of 11
        assertFalse(Quantize.onGrid(LongArray(0), tempo))
    }

    @Test
    fun `lengths read as the nearest value`() {
        assertEquals(NoteValue(NoteValue.WHOLE, false), Quantize.value(1_920, 480))
        assertEquals(NoteValue(NoteValue.HALF, false), Quantize.value(960, 480))
        assertEquals(NoteValue(NoteValue.QUARTER, false), Quantize.value(480, 480))
        assertEquals(NoteValue(NoteValue.EIGHTH, false), Quantize.value(240, 480))
        assertEquals(NoteValue(NoteValue.SIXTEENTH, false), Quantize.value(120, 480))
        assertEquals(NoteValue(NoteValue.QUARTER, false), Quantize.value(96, 96))
    }

    @Test
    fun `a slightly short note keeps its value, a much shorter one reads shorter`() {
        assertEquals(NoteValue(NoteValue.QUARTER, false), Quantize.value(432, 480))   // 90 %
        assertEquals(NoteValue(NoteValue.QUARTER, false), Quantize.value(420, 480))   // 87.5 %
        assertEquals(NoteValue(NoteValue.EIGHTH, true), Quantize.value(350, 480))     // 73 %: as long as a dotted eighth
        assertEquals(NoteValue(NoteValue.EIGHTH, false), Quantize.value(300, 480))    // 62.5 %: nearer an eighth on a log scale
    }

    @Test
    fun `dotted values within 12 percent of one and a half`() {
        assertEquals(NoteValue(NoteValue.QUARTER, true), Quantize.value(720, 480))
        assertEquals(NoteValue(NoteValue.HALF, true), Quantize.value(1_440, 480))
        assertEquals(NoteValue(NoteValue.EIGHTH, true), Quantize.value(360, 480))
        assertEquals(NoteValue(NoteValue.QUARTER, true), Quantize.value(660, 480))    // 8 % short of a dotted quarter
        assertEquals(NoteValue(NoteValue.WHOLE, true), Quantize.value(2_880, 480))
    }

    @Test
    fun `beyond the written values the nearest end`() {
        assertEquals(NoteValue(NoteValue.SIXTEENTH, false), Quantize.value(60, 480))      // a 32nd
        assertEquals(NoteValue(NoteValue.SIXTEENTH, false), Quantize.value(0, 480))
        assertEquals(NoteValue(NoteValue.WHOLE, false), Quantize.value(7_680, 480))       // four bars held
    }

    @Test
    fun `values know their heads and flags`() {
        assertTrue(NoteValue(NoteValue.WHOLE, false).whole)
        assertTrue(NoteValue(NoteValue.HALF, false).hollow)
        assertFalse(NoteValue(NoteValue.QUARTER, false).hollow)
        assertEquals(0, NoteValue(NoteValue.QUARTER, false).flags)
        assertEquals(1, NoteValue(NoteValue.EIGHTH, true).flags)
        assertEquals(2, NoteValue(NoteValue.SIXTEENTH, false).flags)
    }
}
