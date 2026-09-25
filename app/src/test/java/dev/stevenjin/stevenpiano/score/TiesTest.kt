// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.score.ScoreFixtures.HEAD
import dev.stevenjin.stevenpiano.score.ScoreFixtures.at
import dev.stevenjin.stevenpiano.score.ScoreFixtures.layout
import dev.stevenjin.stevenpiano.score.ScoreFixtures.piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TiesTest {
    private val bars4 = longArrayOf(0, 1920, 3840, 5760)
    private val ends4 = longArrayOf(1920, 3840, 5760, 7680)
    private val simple = BooleanArray(4)

    /** The pieces of a written note from [start] to [end] in 4/4 at 480 ticks a quarter: (tick, sixteenths). */
    private fun pieces(start: Long, end: Long, max: Int = 16): List<Pair<Long, Int>> {
        val ticks = LongArray(16)
        val lengths = IntArray(16)
        val bar = bars4.indexOfLast { it <= start }
        val count = Ties.segments(start, end, bars4, ends4, simple, bar, 120.0, max, ticks, lengths)
        return (0 until count).map { ticks[it] to lengths[it] }
    }

    private fun split(sixteenths: Int, compound: Boolean = false): List<Int> {
        val out = IntArray(16)
        return out.take(Ties.split(sixteenths, out, compound))
    }

    // --- The rules on their own -------------------------------------------------------------

    @Test
    fun `single values, plain or dotted, and the lengths only ties can write`() {
        for (v in listOf(1, 2, 3, 4, 6, 8, 12, 16, 24)) assertTrue("$v", Ties.representable(v))
        for (v in listOf(5, 7, 9, 10, 11, 13, 14, 15, 17, 20)) assertFalse("$v", Ties.representable(v))
        assertEquals(listOf(4, 1), split(5))     // a quarter tied to a sixteenth
        assertEquals(listOf(6, 1), split(7))    // Bach's tenor in the C major prelude: a dotted quarter and a sixteenth
        assertEquals(listOf(8, 2), split(10))
        assertEquals(listOf(8, 3), split(11))
        assertEquals(listOf(12, 2), split(14))
        assertEquals(listOf(16, 4), split(20))
        assertEquals(listOf(6), split(6))
    }

    @Test
    fun `compound metres keep to their dotted values`() {
        assertTrue(Ties.representable(8))
        assertFalse(Ties.representable(8, compound = true))
        assertFalse(Ties.representable(16, compound = true))
        assertTrue(Ties.representable(12, compound = true))
        assertEquals(listOf(12, 6), split(18, compound = true))   // a full bar of 9/8
        assertEquals(listOf(16, 2), split(18))                   // the same length in a simple metre
        assertEquals(listOf(6, 2), split(8, compound = true))
        assertEquals(listOf(6, 3), split(9, compound = true))
        assertEquals(NoteValue(NoteValue.QUARTER, true), Ties.value(6, 480))
        assertEquals(NoteValue(NoteValue.WHOLE, true), Ties.value(24, 480))
        assertEquals(NoteValue(NoteValue.SIXTEENTH, false), Ties.value(1, 96))
    }

    @Test
    fun `a note crossing a bar line is split there`() {
        assertEquals(listOf(1440L to 4, 1920L to 4), pieces(1440, 2400))        // a half note from beat 4
        assertEquals(listOf(480L to 12, 1920L to 4), pieces(480, 2400))         // a whole note from beat 2: dotted half, quarter
        assertEquals(listOf(0L to 16, 1920L to 16, 3840L to 4), pieces(0, 4320))  // held over two bar lines
        assertEquals(listOf(0L to 4, 480L to 1), pieces(0, 600))               // five sixteenths in one bar
        assertEquals(listOf(1800L to 1, 1920L to 6, 2640L to 1), pieces(1800, 2760))   // a sixteenth, then seven more
    }

    @Test
    fun `pieces stop at the cap, past the last bar, and never leave a note without a head`() {
        assertEquals(listOf(0L to 16, 1920L to 16), pieces(0, 7680, max = 2))
        assertEquals(4, pieces(0, 99_999).size)                                  // four bars and no more
        assertEquals(listOf(7620L to 1), pieces(7620, 7680))
        assertEquals(listOf(0L to 1), pieces(0, 30))                             // shorter than half a sixteenth: one sixteenth
    }

    // --- In the layout ----------------------------------------------------------------------

    @Test
    fun `a half note on beat four is a quarter tied to the next bar's quarter`() {
        val tied = piece { note(0, 72, 1440); note(1440, 74, 960); note(2400, 72, 1440) }
        val score = layout(tied)
        val d = tied.at(1440, 74)
        assertEquals(Head.BLACK, score.head[d].toInt())
        assertFalse(score.dotted[d])
        assertEquals(1, score.tiedHeadCount(d))
        val next = score.tiedHead(d, 0)
        assertTrue(next >= score.noteCount)
        assertEquals(d, score.tiedNote[next - score.noteCount])
        assertEquals(Head.BLACK, score.head[next].toInt())
        assertEquals(score.y[d], score.y[next], 0.01f)
        assertEquals(score.bars.contentLeft[1], score.x[next], 0.01f)            // on bar 2's downbeat
        assertEquals(tied.tempoMap.tickToMicros(1920), score.tiedStartMicros[next - score.noteCount])
        // One arc from the first head's right edge to the second's left edge.
        val arc = (0 until score.ties.size).single { score.ties.from[it] == d }
        assertEquals(next, score.ties.to[arc])
        assertEquals(score.x[d] + HEAD, score.ties.x1[arc], 0.01f)
        assertEquals(score.x[next], score.ties.x2[arc], 0.01f)
        // No rest in bar 2 before the dotted half: the tied quarter fills beat 1.
        assertTrue((0 until score.rests.size).none { score.rests.bar[it] == 1 && score.rests.treble[it] })
        // The system knows its tied heads.
        val system = score.systems[score.system[next]]
        assertTrue(next in system.firstTied until system.tiedEnd)
    }

    @Test
    fun `a length no single value writes is tied inside the bar`() {
        val five = piece { note(0, 72, 600) }   // five sixteenths
        val score = layout(five)
        assertEquals(Head.BLACK, score.head[0].toInt())
        assertEquals(0, score.flags[0].toInt())                                  // a quarter
        assertEquals(1, score.tiedHeadCount(0))
        val sixteenth = score.tiedHead(0, 0)
        assertEquals(2, score.flags[sixteenth].toInt())
        assertEquals(score.systems[0].xAt(five.tempoMap.tickToMicros(480)), score.x[sixteenth], 0.01f)
        assertEquals(1, score.ties.size)
    }

    @Test
    fun `every note of a chord is tied, and the tied chord shares one stem`() {
        val chord = piece { for (key in listOf(72, 76, 79)) note(1440, key, 960) }   // C E G across the bar line
        val score = layout(chord)
        assertEquals(3, score.headCount - score.noteCount)
        assertEquals(3, score.ties.size)
        val tiedHeads = (score.noteCount until score.headCount).toList()
        assertEquals(1, tiedHeads.count { !score.stemX[it].isNaN() })
        assertTrue(tiedHeads.all { score.accidental[it].toInt() == Accidental.NONE })
        for (arc in 0 until score.ties.size) assertEquals(score.y[score.ties.from[arc]], score.y[score.ties.to[arc]], 0.01f)
    }

    @Test
    fun `the tied head carries no accidental, and the next struck one in the bar shows it again`() {
        val sharp = piece { note(1440, 73, 960); note(2400, 73, 480) }   // C sharp across the bar line, then struck again
        val score = layout(sharp)
        val first = sharp.at(1440, 73)
        val again = sharp.at(2400, 73)
        assertEquals(Accidental.SHARP, score.accidental[first].toInt())
        assertEquals(Accidental.NONE, score.accidental[score.tiedHead(first, 0)].toInt())
        assertEquals(Accidental.SHARP, score.accidental[again].toInt())
    }

    @Test
    fun `a tie bows away from the stem`() {
        val low = layout(piece { note(1440, 64, 960) })    // E4: stems up, the tie below
        val lowArc = 0
        assertTrue(low.stemUp[0])
        assertFalse(low.ties.above[lowArc])
        assertTrue(low.ties.y1[lowArc] > low.y[0])
        val high = layout(piece { note(1440, 79, 960) })   // G5: stems down, the tie above
        assertFalse(high.stemUp[0])
        assertTrue(high.ties.above[0])
        assertTrue(high.ties.y1[0] < high.y[0])
    }

    @Test
    fun `a tie across a system break is drawn in two halves`() {
        // D5 on beat 4 of bar 2, held into bar 3: the next system on a phone (two bars a system).
        val score = layout(piece { note(0, 72, 1920); note(1920, 72, 1440); note(3360, 74, 960) })
        val note = 2
        assertEquals(1, score.tiedHeadCount(note))
        val next = score.tiedHead(note, 0)
        assertEquals(score.system[note] + 1, score.system[next])
        val halves = (0 until score.ties.size).filter { score.ties.from[it] == note || score.ties.to[it] == next }
        assertEquals(2, halves.size)
        val (out, into) = halves.sortedBy { score.ties.system[it] }
        assertEquals(-1, score.ties.to[out])
        assertEquals(-1, score.ties.from[into])
        assertEquals(score.system[note], score.ties.system[out])
        assertEquals(score.system[next], score.ties.system[into])
        assertTrue(score.ties.x2[out] <= score.systems[score.system[note]].right)
        assertEquals(score.x[next], score.ties.x2[into], 0.01f)
    }

    @Test
    fun `a whole bar held in 9-8 is a dotted half tied to a dotted quarter`() {
        val nine = piece(metas = { timeSignature(0, 9, 8) }) { note(0, 72, 2160); note(2160, 72, 2160) }
        val score = layout(nine)
        assertEquals(Head.HALF, score.head[0].toInt())
        assertTrue(score.dotted[0])
        val quarter = score.tiedHead(0, 0)
        assertEquals(Head.BLACK, score.head[quarter].toInt())
        assertTrue(score.dotted[quarter])
    }

    @Test
    fun `performances are never tied`() {
        val random = java.util.Random(13)
        val played = piece(ppq = 384) {
            var tick = 0L
            repeat(40) {
                note(tick, 60 + random.nextInt(20), 500L + random.nextInt(900))
                tick += 37 + random.nextInt(300)
            }
        }
        val score = layout(played)
        assertFalse(score.quantized)
        assertEquals(score.noteCount, score.headCount)
        assertEquals(0, score.ties.size)
    }
}
