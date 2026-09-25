// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.score.ScoreFixtures.SPACE
import dev.stevenjin.stevenpiano.score.ScoreFixtures.layout
import dev.stevenjin.stevenpiano.score.ScoreFixtures.piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestsTest {
    /** The rests tiling [from] until [to] as (place, value) pairs, in sixteenths. */
    private fun tile(from: Int, to: Int, barLength: Int = 16, beat: Int = 4, compound: Boolean = false): List<Pair<Int, Int>> {
        val start = IntArray(64)
        val length = IntArray(64)
        val count = Rests.tile(from, to, barLength, beat, compound, start, length)
        return (0 until count).map { start[it] to length[it] }
    }

    /** One staff's rests in [bar], in order: (sixteenths into the bar from their x, value). */
    private fun ScoreLayout.restsOn(treble: Boolean, bar: Int = 0): List<Int> =
        (0 until rests.size).filter { rests.treble[it] == treble && rests.bar[it] == bar }.sortedBy { rests.x[it] }.map { rests.value[it].toInt() }

    // --- Tiling -----------------------------------------------------------------------------

    @Test
    fun `a gap is tiled with the largest values on the beat grid`() {
        assertEquals(listOf(0 to 4, 4 to 2), tile(0, 6))              // a dotted quarter from a beat: quarter, then eighth
        assertEquals(listOf(2 to 2, 4 to 4), tile(2, 8))              // half a beat in: an eighth, then a quarter on the beat
        assertEquals(listOf(4 to 4, 8 to 8), tile(4, 16))             // beats 2 to 4: a quarter, then a half on beat 3
        assertEquals(listOf(0 to 8, 8 to 4), tile(0, 12))
        assertEquals(listOf(1 to 1, 2 to 2), tile(1, 4))              // the rest of a beat after a sixteenth
        assertEquals(listOf(3 to 1, 4 to 4, 8 to 1), tile(3, 9))
        assertEquals(listOf(4 to 4, 8 to 4), tile(4, 12, barLength = 12))   // 3/4: no half rest from beat 2
    }

    @Test
    fun `compound metres rest within their dotted-quarter beats`() {
        assertEquals(listOf(0 to 4, 4 to 2), tile(0, 6, barLength = 12, beat = 6, compound = true))
        assertEquals(listOf(2 to 4), tile(2, 6, barLength = 12, beat = 6, compound = true))   // an eighth note, then a quarter rest
        assertEquals(listOf(0 to 4, 4 to 2, 6 to 4), tile(0, 10, barLength = 12, beat = 6, compound = true))   // never across a beat
        assertEquals(listOf(6 to 4, 10 to 2), tile(6, 12, barLength = 12, beat = 6, compound = true))
    }

    @Test
    fun `a whole bar of silence is one whole-bar rest, in any metre`() {
        assertEquals(listOf(0 to 16), tile(0, 16))
        assertEquals(listOf(0 to 12), tile(0, 12, barLength = 12))
        assertEquals(listOf(0 to 18), tile(0, 18, barLength = 18, beat = 6, compound = true))
        assertEquals(listOf(0 to 8), tile(0, 8, barLength = 8))
        // In 2/2 a half is a beat.
        assertEquals(listOf(4 to 4, 8 to 8), tile(4, 16, beat = 8))
        assertTrue(Rests.fits(8, Rests.HALF, 8, false))
        assertFalse(Rests.fits(4, Rests.HALF, 8, false))
    }

    // --- In the layout ----------------------------------------------------------------------

    @Test
    fun `the silence after a note is written from the beat grid, at the cursor's place`() {
        val score = layout(piece { note(0, 72, 480) })   // one quarter on beat 1 of a 4/4 bar
        assertEquals(listOf(Rests.QUARTER, Rests.HALF), score.restsOn(treble = true))
        val quarter = (0 until score.rests.size).first { score.rests.treble[it] && score.rests.value[it].toInt() == Rests.QUARTER }
        val system = score.systems[0]
        assertEquals(system.xAt(score.bars.tempo.tickToMicros(480)), score.rests.x[quarter], 0.01f)
        assertEquals(system.trebleTop + 2 * SPACE, score.rests.y[quarter], 0.01f)   // on the middle line
        val halfRest = (0 until score.rests.size).first { score.rests.treble[it] && score.rests.value[it].toInt() == Rests.HALF }
        assertEquals(system.xAt(score.bars.tempo.tickToMicros(960)), score.rests.x[halfRest], 0.01f)
        assertEquals(system.trebleTop + 2 * SPACE, score.rests.y[halfRest], 0.01f)   // sitting on the middle line
    }

    @Test
    fun `a rest before a note on the beat is an eighth then a quarter`() {
        val score = layout(piece { note(0, 72, 240); note(960, 72, 960) })   // an eighth, silence to beat 3, a half
        assertEquals(listOf(Rests.EIGHTH, Rests.QUARTER), score.restsOn(treble = true))
    }

    @Test
    fun `an empty staff gets a whole rest centred in the bar, hanging from the fourth line`() {
        val score = layout(piece { note(0, 72, 1920) })
        assertEquals(listOf(Rests.WHOLE), score.restsOn(treble = false))
        val rest = (0 until score.rests.size).first { !score.rests.treble[it] }
        assertTrue(score.rests.wholeBar[rest])
        val system = score.systems[0]
        assertEquals(system.bassTop + SPACE, score.rests.y[rest], 0.01f)
        val centre = (score.bars.contentLeft[0] + score.bars.right[0]) / 2
        assertEquals(centre, score.rests.x[rest] + 1.128f * SPACE / 2, 0.01f)
        assertTrue(score.restsOn(treble = true).isEmpty())   // the whole note fills the treble
    }

    @Test
    fun `when both staves are empty for a bar, both get whole rests`() {
        val score = layout(piece { note(0, 72, 1920); note(3840, 72, 1920) })   // bar 2 is silent
        assertEquals(listOf(Rests.WHOLE), score.restsOn(treble = true, bar = 1))
        assertEquals(listOf(Rests.WHOLE), score.restsOn(treble = false, bar = 1))
        assertTrue((0 until score.rests.size).filter { score.rests.bar[it] == 1 }.all { score.rests.wholeBar[it] })
        // In 3/4 and 6/8 too: a whole rest fills any silent bar.
        val waltz = layout(piece(metas = { timeSignature(0, 3, 4) }) { note(0, 72, 1440) })
        assertEquals(listOf(Rests.WHOLE), waltz.restsOn(treble = false))
    }

    @Test
    fun `a note held on its staff covers the silence of another that ended`() {
        val score = layout(piece { note(0, 64, 1920); note(0, 72, 480) })   // a whole E4 under a quarter C5
        assertTrue(score.restsOn(treble = true).isEmpty())
    }

    @Test
    fun `slightly short notes read at their written value, with no rest after them`() {
        val score = layout(piece { for (beat in 0 until 4) note(beat * 480L, 72, 432) })   // quarters held 90 %
        assertTrue(score.restsOn(treble = true).isEmpty())
    }

    @Test
    fun `a rest inside a beat ends a beam, and rests only come with sequenced files`() {
        val broken = layout(piece { note(0, 72, 120); note(240, 74, 120); note(360, 76, 120) })
        assertEquals(listOf(Rests.SIXTEENTH, Rests.QUARTER, Rests.HALF), broken.restsOn(treble = true))
        assertEquals(1, broken.beams.size / 2)   // one group: its primary and its second beam
        val random = java.util.Random(11)
        val played = piece(ppq = 384) {
            var tick = 0L
            repeat(40) {
                note(tick, 60 + random.nextInt(20), 60L + random.nextInt(40))
                tick += 37 + random.nextInt(900)
            }
        }
        val score = layout(played)
        assertFalse(score.quantized)
        assertEquals(0, score.rests.size)
    }
}
