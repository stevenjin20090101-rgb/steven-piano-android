// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.midi.TimeSignature
import dev.stevenjin.stevenpiano.score.ScoreFixtures.DENSITY
import dev.stevenjin.stevenpiano.score.ScoreFixtures.HEAD
import dev.stevenjin.stevenpiano.score.ScoreFixtures.SPACE
import dev.stevenjin.stevenpiano.score.ScoreFixtures.at
import dev.stevenjin.stevenpiano.score.ScoreFixtures.layout
import dev.stevenjin.stevenpiano.score.ScoreFixtures.piece
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BeamsTest {
    private fun metre(numerator: Int, denominator: Int) = TimeSignature(0L, 0L, numerator, denominator)

    /** The primary beams (one per group), in order. */
    private fun ScoreLayout.primaries(): List<Int> = (0 until beams.size).filter { beams.level[it].toInt() == 1 }.sortedBy { beams.x1[it] }

    private fun ScoreLayout.secondaries(): List<Int> = (0 until beams.size).filter { beams.level[it].toInt() == 2 }.sortedBy { beams.x1[it] }

    /** The notes carrying a stem between the primary beam [b]'s ends: its group's stems. */
    private fun ScoreLayout.stemsUnder(b: Int): List<Int> =
        (0 until noteCount).filter { !stemX[it].isNaN() && stemX[it] >= beams.x1[b] - 0.01f && stemX[it] <= beams.x2[b] + 0.01f }.sortedBy { stemX[it] }

    /** Where beam [b]'s outer edge is at [x]. */
    private fun ScoreLayout.beamAt(b: Int, x: Float): Float =
        beams.y1[b] + (beams.y2[b] - beams.y1[b]) * (x - beams.x1[b]) / (beams.x2[b] - beams.x1[b])

    /** A bar of [count] notes of [length] ticks climbing from C5. */
    private fun run(count: Int, length: Long, metas: dev.stevenjin.stevenpiano.midi.SmfBuilder.Track.() -> Unit = {}): MidiPiece =
        piece(metas = metas) { for (k in 0 until count) note(k * length, SCALE[k % SCALE.size], length) }

    // --- The rules on their own -------------------------------------------------------------

    @Test
    fun `beat groups follow the metre`() {
        assertEquals(4, Beams.beatSixteenths(metre(4, 4)))
        assertEquals(4, Beams.beatSixteenths(metre(3, 4)))
        assertEquals(6, Beams.beatSixteenths(metre(6, 8)))
        assertEquals(6, Beams.beatSixteenths(metre(9, 8)))
        assertEquals(6, Beams.beatSixteenths(metre(12, 8)))
        assertEquals(8, Beams.beatSixteenths(metre(2, 2)))
        assertEquals(4, Beams.beatSixteenths(metre(3, 8)))   // other metres: a quarter
        assertEquals(4, Beams.beatSixteenths(metre(5, 8)))
        assertEquals(4, Beams.beatSixteenths(metre(3, 2)))
        assertTrue(Beams.compound(metre(9, 8)))
        assertFalse(Beams.compound(metre(3, 8)))
    }

    @Test
    fun `consecutive beamable notes of one beat group, a rest or an unbeamable note between them breaks it`() {
        val bar = IntArray(8)
        val beat = intArrayOf(0, 0, 0, 1, 1, 1, 2, 2)
        val beamable = booleanArrayOf(true, true, true, true, false, true, true, true)
        val rest = booleanArrayOf(false, false, true, false, false, false, false, false)
        val groupOf = IntArray(8)
        assertEquals(2, Beams.group(bar, beat, beamable, rest, 8, groupOf))
        // 0-1 beamed; 2 alone after its rest; 3 alone before the quarter; 5 alone in its beat; 6-7 beamed.
        assertArrayEquals(intArrayOf(0, 0, -1, -1, -1, -1, 1, 1), groupOf)
    }

    @Test
    fun `the line follows the first and last heads within one space, clear of every head`() {
        val x = floatArrayOf(0f, 50f, 100f)
        val tip = FloatArray(3)
        // Stems up over heads falling half a space each: the line falls with them.
        Beams.line(x, floatArrayOf(100f, 106f, 112f), floatArrayOf(1_000f, 1_000f, 1_000f), 3, true, SPACE, 3.5f * SPACE, tip)
        assertEquals(100f - 3.5f * SPACE, tip[0], 0.01f)
        assertEquals(12f, tip[2] - tip[0], 0.01f)
        // A leap of four spaces: the rise is held to one space, and the highest head keeps its 3.5 spaces.
        Beams.line(x, floatArrayOf(100f, 90f, 52f), floatArrayOf(1_000f, 1_000f, 1_000f), 3, true, SPACE, 3.5f * SPACE, tip)
        assertEquals(-SPACE, tip[2] - tip[0], 0.01f)
        assertEquals(52f - 3.5f * SPACE, tip[2], 0.01f)
        assertTrue((0..2).all { tip[it] <= floatArrayOf(100f, 90f, 52f)[it] - 3.5f * SPACE + 0.01f })
    }

    @Test
    fun `a lone sixteenth's stub points into its group`() {
        assertTrue(Beams.stubPointsRight(0, 3, 0))
        assertFalse(Beams.stubPointsRight(2, 3, 3))
        assertFalse(Beams.stubPointsRight(1, 3, 3))   // after a dotted eighth: back toward it
        assertTrue(Beams.stubPointsRight(1, 3, 2))    // on an eighth: forward
    }

    // --- In the layout ----------------------------------------------------------------------

    @Test
    fun `eighths in 4-4 beam in pairs, one per beat, and lose their flags`() {
        val score = layout(run(8, 240))
        val primaries = score.primaries()
        assertEquals(4, primaries.size)
        assertEquals(4, primaries.map { score.beams.group[it] }.toSet().size)
        assertTrue(score.secondaries().isEmpty())
        assertTrue((0 until score.noteCount).all { score.flags[it].toInt() == 0 })
        for (b in primaries) {
            val stems = score.stemsUnder(b)
            assertEquals(2, stems.size)
            assertEquals(score.stemX[stems[0]], score.beams.x1[b], 0.01f)
            assertEquals(score.stemX[stems[1]] + DENSITY, score.beams.x2[b], 0.01f)
            // Both stems point one way and end on the beam.
            assertEquals(score.stemUp[stems[0]], score.stemUp[stems[1]])
            assertEquals(score.beams.up[b], score.stemUp[stems[0]])
            for (s in stems) assertEquals(score.beamAt(b, score.stemX[s]), score.stemTo[s], 0.01f)
        }
    }

    @Test
    fun `sixteenths beam in fours with a second beam a quarter space below the first`() {
        val score = layout(run(16, 120))
        val primaries = score.primaries()
        val secondaries = score.secondaries()
        assertEquals(4, primaries.size)
        assertEquals(4, secondaries.size)
        for ((b, second) in primaries.zip(secondaries)) {
            assertEquals(4, score.stemsUnder(b).size)
            assertFalse(score.beams.stub[second])
            assertEquals(score.beams.x1[b], score.beams.x1[second], 0.01f)
            assertEquals(score.beams.x2[b], score.beams.x2[second], 0.01f)
            // 0.75 space centre to centre: 0.25 space between two half-space beams, toward the heads.
            val inward = if (score.beams.up[b]) 1 else -1
            assertEquals(inward * 0.75f * SPACE, score.beams.y1[second] - score.beams.y1[b], 0.01f)
        }
    }

    @Test
    fun `in 6-8 and 9-8 eighths beam in threes`() {
        val sixEight = layout(run(6, 240) { timeSignature(0, 6, 8) })
        assertEquals(2, sixEight.primaries().size)
        assertTrue(sixEight.primaries().all { sixEight.stemsUnder(it).size == 3 })
        val nineEight = layout(run(9, 240) { timeSignature(0, 9, 8) })
        assertEquals(3, nineEight.primaries().size)
        assertTrue(nineEight.primaries().all { nineEight.stemsUnder(it).size == 3 })
    }

    @Test
    fun `mixed eighths and sixteenths get partial beams pointing into the group`() {
        // Beat 1: a dotted eighth and a sixteenth. Beat 2: a sixteenth and a dotted eighth. Beat 3: an eighth and two sixteenths.
        val mixed = piece {
            note(0, 72, 360); note(360, 74, 120)
            note(480, 76, 120); note(600, 77, 360)
            note(960, 79, 240); note(1200, 77, 120); note(1320, 76, 120)
        }
        val score = layout(mixed)
        assertEquals(3, score.primaries().size)
        val seconds = score.secondaries()
        assertEquals(3, seconds.size)
        val (back, forward, pair) = seconds
        // Beat 1: the sixteenth is last, its stub points left from its stem.
        val d = mixed.at(360, 74)
        assertTrue(score.beams.stub[back])
        assertEquals(score.stemX[d] + DENSITY, score.beams.x2[back], 0.01f)
        assertEquals(HEAD, score.beams.x2[back] - score.beams.x1[back], 0.01f)
        // Beat 2: the sixteenth is first, its stub points right.
        val e = mixed.at(480, 76)
        assertTrue(score.beams.stub[forward])
        assertEquals(score.stemX[e], score.beams.x1[forward], 0.01f)
        assertEquals(HEAD, score.beams.x2[forward] - score.beams.x1[forward], 0.01f)
        // Beat 3: the two sixteenths share a full second beam.
        assertFalse(score.beams.stub[pair])
        assertEquals(score.stemX[mixed.at(1200, 77)], score.beams.x1[pair], 0.01f)
        assertEquals(score.stemX[mixed.at(1320, 76)] + DENSITY, score.beams.x2[pair], 0.01f)
        // Dotted eighths keep their dots.
        assertTrue(score.dotted[mixed.at(0, 72)])
        assertTrue(score.dotted[mixed.at(600, 77)])
    }

    @Test
    fun `a chord in a group beams as one stem`() {
        val chord = piece {
            note(0, 72, 240); note(0, 76, 240)   // C5 and E5 together
            note(240, 74, 240)                   // D5
        }
        val score = layout(chord)
        val primaries = score.primaries()
        assertEquals(1, primaries.size)
        val stems = score.stemsUnder(primaries[0])
        assertEquals(2, stems.size)                                   // one for the chord, one for D5
        assertEquals(2, (0 until score.noteCount).count { !score.stemX[it].isNaN() })
        assertTrue(stems[0] == chord.at(0, 72) || stems[0] == chord.at(0, 76))
        // Stems down (the heads average above the middle line): the chord's stem reaches from its top head.
        assertFalse(score.stemUp[stems[0]])
        assertEquals(score.y[chord.at(0, 76)] + 0.168f * SPACE, score.stemFrom[stems[0]], 0.01f)
    }

    @Test
    fun `a rest inside the beat breaks the group`() {
        val broken = piece {
            note(0, 72, 120)      // a sixteenth, then a sixteenth rest
            note(240, 74, 120)
            note(360, 76, 120)
        }
        val score = layout(broken)
        val primaries = score.primaries()
        assertEquals(1, primaries.size)
        assertEquals(listOf(broken.at(240, 74), broken.at(360, 76)).sorted(), score.stemsUnder(primaries[0]).sorted())
        assertEquals(2, score.flags[broken.at(0, 72)].toInt())   // alone: it keeps its flags
    }

    @Test
    fun `a leap tilts the beam at most one space, and a step follows the heads`() {
        val leap = piece { note(0, 72, 240); note(240, 88, 240) }   // C5 up to E6: four and a half spaces
        val score = layout(leap)
        val c = leap.at(0, 72)
        val e = leap.at(240, 88)
        assertEquals(1, score.primaries().size)
        assertEquals(SPACE, abs(score.stemTo[e] - score.stemTo[c]), 0.01f)
        assertTrue(score.stemTo[e] < score.stemTo[c])   // it still rises with the music
        val step = layout(piece { note(0, 72, 240); note(240, 74, 240) })   // C5 to D5: half a space
        val ends = (0 until step.noteCount).map { step.stemTo[it] }
        assertEquals(SPACE / 2, abs(ends[1] - ends[0]), 0.01f)
    }

    @Test
    fun `a group's stems share the side most of its heads ask for and reach the middle line`() {
        // B4 alone would point down, C4 and D4 up: the average is below the middle line, so all go up.
        val mixed = piece { note(0, 71, 120); note(120, 60, 120); note(240, 62, 120); note(360, 64, 120) }
        val score = layout(mixed)
        assertTrue((0 until score.noteCount).filter { !score.stemX[it].isNaN() }.all { score.stemUp[it] })
        // Far below the bass staff (G1, A1) the beam still reaches the staff's middle line.
        val low = layout(piece { note(0, 31, 240); note(240, 33, 240) })
        val middle = low.systems[0].bassBottom - 2 * SPACE
        val tips = (0 until low.noteCount).map { low.stemTo[it] }
        assertTrue(tips.all { it <= middle + 0.01f })
        assertEquals(middle, tips.max(), 0.01f)   // the stem nearest the staff ends on the middle line
    }

    @Test
    fun `quarters, lone flagged notes and performances get no beams`() {
        assertEquals(0, layout(run(4, 480)).beams.size)
        assertEquals(0, layout(piece { note(0, 72, 240); note(480, 74, 240) }).beams.size)   // one eighth per beat
        val random = java.util.Random(5)
        val played = piece(ppq = 384) {
            var tick = 0L
            repeat(40) {
                note(tick, 60 + random.nextInt(20), 60L + random.nextInt(40))
                tick += 37 + random.nextInt(90)
            }
        }
        val score = layout(played)
        assertFalse(score.quantized)
        assertEquals(0, score.beams.size)
    }

    private companion object {
        /** C5 D5 E5 F5 G5 A5 B5 C6. */
        val SCALE = intArrayOf(72, 74, 76, 77, 79, 81, 83, 84)
    }
}
