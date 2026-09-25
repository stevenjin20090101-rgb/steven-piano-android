// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.MidiPiece
import dev.stevenjin.stevenpiano.score.ScoreFixtures.piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

/**
 * The skyline the engine makes for the painter's tempo mark and chord names (the v1.3 delta audit,
 * L2): never below anything that reaches up where a label goes, and never above what lies within a
 * column of it; the page's build then reads a few columns instead of scanning the system.
 */
class ScoreSkylineTest {
    private val metrics = ScoreFixtures.metrics()
    private val space = metrics.space

    /**
     * Twelve bars in both hands: a right-hand line of eighths and sixteenths (beamed, high and low,
     * sharps, a dotted note, notes tied over bar lines) over a left-hand bass, fingered 1 to 5.
     */
    private val piece: MidiPiece = piece(metas = { keySignature(0, 1) }) {
        val line = listOf(79, 81, 83, 84, 86, 88, 90, 91, 67, 69, 71, 72, 74, 76, 78, 79)
        var tick = 0L
        for (bar in 0 until 12) {
            for (k in 0 until 8) note(bar * 1_920L + k * 240L, line[(bar + k) % line.size], if (k == 7 && bar % 3 == 0) 720 else 240)
            note(bar * 1_920L, 43 + bar % 5, 960)
            note(bar * 1_920L + 960, 50 + bar % 3, 1_440)
            tick += 1_920
        }
        note(tick, 96, 480)                       // ledger lines far above
        note(tick + 480, 61, 360)                 // a dotted eighth C♯4 with its stem up
    }

    private val hands = ByteArray(piece.notes.size) { if (piece.notes.note(it) >= 60) Hands.RIGHT else Hands.LEFT }
    private val fingers = ByteArray(piece.notes.size) { (1 + it % 5).toByte() }
    private val score = ScoreLayoutEngine.layout(
        piece.notes, IntArray(piece.notes.size) { KeyMap.map(piece.notes.note(it), 0, true) }, piece.tempoMap,
        piece.barStartsMicros, piece.keySignatures, metrics, piece.timeSignatures, hands, fingers,
    )

    /** Everything in system [s] that reaches up, as (left, right, top), with its true extent. */
    private fun items(s: Int): List<Triple<Float, Float, Float>> {
        val out = ArrayList<Triple<Float, Float, Float>>()
        val system = score.systems[s]
        fun head(h: Int) {
            out += Triple(score.x[h], score.x[h] + score.headWidth(h), score.y[h] - space / 2)
            if (score.accidental[h].toInt() != Accidental.NONE) out += Triple(score.accidentalX[h], score.x[h], score.y[h] - 1.5f * space)
            if (!score.stemX[h].isNaN()) {
                val reach = if (score.flags[h] > 0) 1.1f * space else metrics.density
                out += Triple(score.stemX[h], score.stemX[h] + reach, min(score.stemFrom[h], score.stemTo[h]))
            }
            if (score.dotted[h]) out += Triple(score.dotX[h], score.dotX[h] + 0.4f * space, score.dotY[h] - space / 4)
        }
        for (i in system.firstNote until system.noteEnd) if (score.system[i] == s) head(i)
        for (h in system.firstTied until system.tiedEnd) head(h)
        val b = score.beams
        for (k in b.inSystem(s)) out += Triple(b.x1[k], b.x2[k], min(b.y1[k], b.y2[k]) - if (b.up[k]) 0f else Beams.THICKNESS * space)
        val t = score.ties
        for (k in t.inSystem(s)) out += Triple(t.x1[k], t.x2[k], min(t.y1[k], t.y2[k]) - if (t.above[k]) 0.9f * space else 0f)
        val f = score.fingers
        for (k in f.inSystem(s)) if (f.above[k]) out += Triple(f.x[k] - metrics.numeralWidth / 2, f.x[k] + metrics.numeralWidth / 2, f.baseline[k] - metrics.numeralHeight)
        return out
    }

    private fun highest(items: List<Triple<Float, Float, Float>>, from: Float, to: Float, line: Float): Float {
        var top = line
        for ((left, right, y) in items) if (right >= from && left <= to) top = min(top, y)
        return top
    }

    @Test
    fun `the skyline is never below what reaches up under a label, nor above what lies within a column of it`() {
        assertTrue(score.quantized)
        assertTrue(score.beams.size > 0 && score.ties.size > 0 && score.fingers.size > 0)
        val column = space / 2
        val random = java.util.Random(7)
        var checked = 0
        var above = 0
        for (s in score.systems.indices) {
            val line = score.systems[s].trebleTop
            val items = items(s)
            repeat(400) {
                val from = random.nextFloat() * metrics.pageWidth
                val to = from + random.nextFloat() * 12 * space
                val sky = score.skyline.top(s, from, to)
                assertTrue("system $s, $from..$to: $sky under ${highest(items, from, to, line)}", sky <= highest(items, from, to, line) + 0.001f)
                assertTrue("system $s, $from..$to: $sky over a column's slack", sky >= highest(items, from - column, to + column, line) - 1f)
                if (sky < line) above++
                checked++
            }
        }
        assertTrue("$above of $checked ranges reach above the staff", above > checked / 4)
    }

    @Test
    fun `a system with nothing in it keeps nothing, and its skyline is its top line`() {
        val sparse = piece { note(0, 84, 480); note(12 * 1_920L, 60, 480) }   // bars 2 to 12 empty
        val layout = ScoreFixtures.layout(sparse)
        val empty = 3
        assertEquals(0, layout.systems[empty].noteEnd)
        assertEquals(layout.systems[empty].trebleTop, layout.skyline.top(empty, 0f, metrics.pageWidth), 0f)
        // C6's two ledger lines reach above the first system's staff where it stands, and only there.
        val c6 = 0
        assertTrue(layout.skyline.top(0, layout.x[c6], layout.x[c6] + 1f) < layout.systems[0].trebleTop - space)
        assertEquals(layout.systems[0].trebleTop, layout.skyline.top(0, layout.x[c6] + 4 * space, layout.x[c6] + 6 * space), 0f)
        assertEquals(Float.POSITIVE_INFINITY, ScoreSkyline.None.top(0, 0f, 100f), 0f)
        assertEquals(Float.POSITIVE_INFINITY, layout.skyline.top(layout.systems.size, 0f, 100f), 0f)
    }
}
