// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.score.ScoreFixtures.layout
import dev.stevenjin.stevenpiano.score.ScoreFixtures.piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TempoMarksTest {
    /** Eight bars of whole notes in 4/4: four systems on a phone. */
    private fun eightBars(metas: dev.stevenjin.stevenpiano.midi.SmfBuilder.Track.() -> Unit = {}) =
        piece(metas = metas) { for (bar in 0 until 8) note(bar * 1920L, 72, 1920) }

    @Test
    fun `quarters a minute, or dotted quarters in a compound metre`() {
        assertEquals(120, TempoMarks.bpm(500_000, false))
        assertEquals(80, TempoMarks.bpm(500_000, true))
        assertEquals(100, TempoMarks.bpm(600_000, false))
        assertEquals(67, TempoMarks.bpm(600_000, true))
        assertEquals(TempoMarks.MAX_BPM, TempoMarks.bpm(1, false))
        assertEquals("= 80", TempoMark(0, 0f, 80, false).text)
    }

    @Test
    fun `the first system is marked with the tempo at the start`() {
        val score = layout(eightBars { tempo(0, 750_000) })   // 80 a minute
        assertEquals(1, score.tempoMarks.size)
        val mark = score.tempoMarks[0]
        assertEquals(0, mark.system)
        assertEquals(80, mark.bpm)
        assertFalse(mark.dotted)
        assertEquals("= 80", mark.text)
        // On the bar-number line at the first bar's left: over the clef, where no note reaches.
        assertEquals(score.systems[0].left, mark.x, 0.01f)
        assertEquals(score.bars.left[0], mark.x, 0.01f)
        // A file with no tempo at all reads at the standard 120.
        assertEquals(120, layout(eightBars()).tempoMarks.single().bpm)
    }

    @Test
    fun `the tempo at tick 0 makes the first mark, even when it changes at once`() {
        val score = layout(eightBars { tempo(0, 600_000); tempo(240, 1_875_000) })   // as Clair de lune opens
        assertEquals(100, score.tempoMarks[0].bpm)
    }

    @Test
    fun `a new mark only where a system starts more than 10 percent away from the last mark`() {
        val score = layout(
            eightBars {
                tempo(0, 500_000)        // 120
                tempo(3_840, 480_000)    // system 1: 125, 4 % faster: no mark
                tempo(5_000, 300_000)    // inside system 1: never marked by itself
                tempo(7_680, 428_571)    // system 2: 140, 17 % faster than 120: marked
                tempo(11_520, 461_538)   // system 3: 130, 7 % from 140: no mark
            },
        )
        assertEquals(listOf(0, 2), score.tempoMarks.map { it.system })
        assertEquals(listOf(120, 140), score.tempoMarks.map { it.bpm })
        assertEquals(score.systems[2].left, score.tempoMarks[1].x, 0.01f)
    }

    @Test
    fun `a compound metre shows a dotted quarter`() {
        val nineEight = layout(piece(metas = { timeSignature(0, 9, 8); tempo(0, 500_000) }) { note(0, 72, 2160) })
        val mark = nineEight.tempoMarks.single()
        assertTrue(mark.dotted)
        assertEquals(80, mark.bpm)
        val sixEight = layout(piece(metas = { timeSignature(0, 6, 8); tempo(0, 1_411_765) }) { note(0, 72, 1440) })
        assertEquals(28, sixEight.tempoMarks.single().bpm)   // Chopin's op. 27 no. 2 as piano-midi.de starts it
        val threeEight = layout(piece(metas = { timeSignature(0, 3, 8) }) { note(0, 72, 720) })
        assertFalse(threeEight.tempoMarks.single().dotted)
    }

    @Test
    fun `performances are marked too`() {
        val random = java.util.Random(17)
        val played = piece(ppq = 384) {
            var tick = 0L
            repeat(60) {
                note(tick, 60 + random.nextInt(20), 60L + random.nextInt(40))
                tick += 37 + random.nextInt(90)
            }
        }
        val score = layout(played)
        assertFalse(score.quantized)
        assertEquals(listOf(120), score.tempoMarks.map { it.bpm })
    }
}
