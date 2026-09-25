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

class TempoMapTest {
    private fun map(ppq: Int, vararg changes: Pair<Long, Int>): TempoMap =
        TempoMap.Builder(ppq).apply { changes.forEach { (tick, tempo) -> change(tick, tempo) } }.build()

    @Test
    fun `a file that never sets a tempo runs at 120 BPM`() {
        val tempo = TempoMap.constant(480)
        assertEquals(1, tempo.size)
        assertEquals(500_000, tempo.tempoAt(0))
        assertEquals(500_000L, tempo.tickToMicros(480))
        assertEquals(480L, tempo.microsToTicks(500_000))
        assertEquals(0.5, tempo.microsToBeats(250_000), 1e-9)
    }

    @Test
    fun `ticks become microseconds exactly as the parser times events`() {
        val tempo = map(96, 0L to 333_333, 7L to 250_000)
        assertEquals(3_472L, tempo.tickToMicros(1))        // 1 * 333333 / 96 = 3472.2
        assertEquals(32_117L, tempo.tickToMicros(10))      // 24305 at tick 7, then 3 * 250000 / 96 = 7812.5
        val piece = SmfParser.parse(
            SmfBuilder(format = 1, division = 96).track {
                tempo(0, 333_333)
                tempo(7, 250_000)
            }.track {
                noteOn(1, 60)
                noteOff(10, 60)
            }.build(),
        )
        assertEquals(listOf(3_472L, 32_117L), piece.events.map { it.atMicros })
        assertEquals(listOf(1L, 10L).map(piece.tempoMap::tickToMicros), piece.events.map { it.atMicros })
    }

    @Test
    fun `every tick comes back from its microseconds, across tempo changes`() {
        val tempo = map(96, 0L to 333_333, 7L to 250_000, 100L to 1_000_000, 101L to 437_500, 900L to 2_000_001)
        for (tick in 0L..3_000L) assertEquals("tick $tick", tick, tempo.microsToTicks(tempo.tickToMicros(tick)))
        val fine = map(960, 0L to 600_000, 1_234L to 461_538)
        for (tick in 0L..20_000L) assertEquals("tick $tick", tick, fine.microsToTicks(fine.tickToMicros(tick)))
    }

    @Test
    fun `beats count quarter notes with the fraction, through a slower tempo`() {
        val tempo = map(480, 0L to 500_000, 960L to 1_000_000)
        assertEquals(1.0, tempo.microsToBeats(500_000), 1e-9)
        assertEquals(2.0, tempo.microsToBeats(1_000_000), 1e-9)   // the change, two beats in
        assertEquals(2.5, tempo.microsToBeats(1_500_000), 1e-9)   // half a beat a half second now
        assertEquals(3.0, tempo.microsToBeats(2_000_000), 1e-9)
        assertEquals(1_000_000, tempo.tempoAt(960))
        assertEquals(500_000, tempo.tempoAt(959))
    }

    @Test
    fun `two changes at one tick, the later wins and time before them is unchanged`() {
        val tempo = map(480, 0L to 400_000, 480L to 600_000, 480L to 300_000)
        assertEquals(2, tempo.size)
        assertEquals(300_000, tempo.tempoAt(480))
        assertEquals(400_000L, tempo.tickToMicros(480))
        assertEquals(700_000L, tempo.tickToMicros(960))
    }

    @Test
    fun `before the start is the start, and after the last change the last tempo carries on`() {
        val tempo = map(480, 0L to 500_000, 480L to 250_000)
        assertEquals(0L, tempo.tickToMicros(-10))
        assertEquals(0L, tempo.microsToTicks(-5))
        assertEquals(500_000L + 250_000L * 100, tempo.tickToMicros(480L + 480L * 100))
        assertEquals(480L + 480L * 100, tempo.microsToTicks(500_000L + 250_000L * 100))
    }
}
