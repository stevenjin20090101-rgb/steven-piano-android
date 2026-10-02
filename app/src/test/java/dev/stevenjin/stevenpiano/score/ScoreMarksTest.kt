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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * The tempo mark's and chord names' placement moved out of the tablet's painter into [ScoreMarks] (v1.13 — M32): for
 * the same measurements it places them exactly where the painter did. [Before] is the painter's arithmetic as it stood
 * at 0380a10 (`ScorePages.kt`, `ScorePainter.marks()` and `chordLabels()`), word for word but for its `TextLayoutResult`s,
 * which here are whole-pixel widths and heights as a device measures them.
 */
class ScoreMarksTest {
    /** Text measured as a device does: whole pixels wide and high, the baseline a fraction. */
    private class Measured(override val tempoSpace: Float) : ScoreText {
        fun width(text: String, perChar: Int): Int = text.length * perChar + text.count { it.isDigit() }

        override fun numberWidth(text: String): Float = width(text, 9).toFloat()

        override fun numberBaseline(text: String): Float = 16.6875f

        override fun chordWidth(name: String): Float = width(name, 13).toFloat()

        override fun chordHeight(name: String): Float = 27f
    }

    /** The painter's arithmetic at 0380a10, with [number] and [text] widths and baselines given as the measurer gave them. */
    private class Before(private val text: Measured, density: Float) {
        private val space = 6f * density
        private val chordGap = 2f * density
        private val chordSpacing = 6f * density
        private val numberLift = 1.4f * space + 1f * density

        class Marks(val tempo: TempoMark?, val tempoX: Float, val tempoBaseline: Float, val tempoRight: Float = 0f, val tempoTop: Float = 0f)

        fun marks(layout: ScoreLayout, system: ScoreSystem, numberSizeWidth: Int): Marks {
            val s = system.index
            val tempo = layout.tempoMarkIn(s) ?: return Marks(null, 0f, 0f)
            val textSizeWidth = text.width(tempo.text, 9)
            val textFirstBaseline = text.numberBaseline(tempo.text)
            val unit = text.tempoSpace
            val x = max(tempo.x, system.left + numberSizeWidth + space)
            val glyphWidth = 1.328f * unit + if (tempo.dotted) (0.3f + 0.4f) * unit else 0f
            val right = x + glyphWidth + 0.6f * unit + textSizeWidth
            val height = max((0.564f + 3.5f) * unit, textFirstBaseline)
            val onLine = system.trebleTop - numberLift
            val lifted = min(onLine, layout.skyline.top(s, x, right) - 0.5f * space)
            val baseline = max(lifted, min(onLine, system.bandTop + height))   // never above its band
            return Marks(tempo, x, baseline, right, baseline - height)
        }

        fun chordLabels(layout: ScoreLayout, system: ScoreSystem, numberFirstBaseline: Float, marks: Marks, chords: ChordTrack, names: Array<String>): Triple<List<Int>, List<Float>, List<Float>> {
            val bars = layout.bars
            val from = chords.firstAtOrAfter(bars.startMicros[system.firstBar])
            val until = if (system.lastBar + 1 < bars.count) chords.firstAtOrAfter(bars.startMicros[system.lastBar + 1]) else chords.size
            val numberTop = system.trebleTop - numberLift - numberFirstBaseline
            val texts = ArrayList<Int>()
            val xs = ArrayList<Float>()
            val tops = ArrayList<Float>()
            var lastRight = Float.NEGATIVE_INFINITY
            for (i in from until until) {
                val at = system.xAt(chords.startMicros[i])
                if (at < lastRight + chordSpacing) continue
                val textSizeWidth = text.width(names[i], 13)
                val textSizeHeight = 27
                val x = max(system.left, min(at, layout.metrics.pageWidth - textSizeWidth))
                if (x < lastRight + chordSpacing) continue
                val right = x + textSizeWidth
                var bottom = min(numberTop - chordGap, layout.skyline.top(system.index, x, right) - 0.5f * space)
                if (marks.tempo != null && right >= marks.tempoX && x <= marks.tempoRight) bottom = min(bottom, marks.tempoTop - chordGap)
                texts += i
                xs += x
                tops += max(bottom - textSizeHeight, system.bandTop)
                lastRight = right
            }
            return Triple(texts, xs, tops)
        }
    }

    /** A tune whose notes climb far over the treble staff (so the skyline lifts the marks), its tempo changing at bar 5. */
    private fun piece(): MidiPiece = ScoreFixtures.piece(metas = {
        tempo(0, 500_000)
        timeSignature(0, 4, 4)
        tempo(4 * 4 * 480L, 375_000)
    }) {
        for (bar in 0 until 8) {
            for (beat in 0 until 4) {
                val tick = (bar * 4 + beat) * 480L
                note(tick, 72 + (beat * 5 + bar * 3) % 24, 480)
                note(tick, 48 + beat, 480)
            }
        }
    }

    @Test
    fun `the tempo mark and the chord names go exactly where the painter put them`() {
        val piece = piece()
        val chords = ChordTrack(
            LongArray(16) { piece.tempoMap.tickToMicros(it * 2 * 480L) },
            ByteArray(16) { (it * 5 % 12).toByte() },
            ByteArray(16) { (it % 3).toByte() },
            ByteArray(16) { (if (it % 4 == 0) (it + 4) % 12 else -1).toByte() },
            ByteArray(16) { 0 },
        )
        val names = Array(chords.size) { chords.name(it) }
        var checked = 0
        for ((density, width) in listOf(2f to ScoreWidth.COMPACT, 2.625f to ScoreWidth.EXPANDED, 1f to ScoreWidth.MEDIUM)) {
            val metrics = ScoreMetrics.forPanel(width, 600f * density, 700f * density, density, 1.18f * 6 * density, 2.74f * 6 * density, chordHeight = 30f * density)
            val layout = ScoreFixtures.layout(piece, metrics)
            val text = Measured(tempoSpace = 4.125f * density)
            val before = Before(text, density)
            for (system in layout.systems) {
                val number = (system.firstBar + 1).toString()
                val old = before.marks(layout, system, text.width(number, 9))
                val new = ScoreMarks.tempo(layout, system, text.numberWidth(number), text)
                if (old.tempo == null) {
                    assertEquals(null, new)
                } else {
                    checked++
                    assertEquals(old.tempo, new!!.mark)
                    assertEquals("x, system ${system.index}", old.tempoX, new.x)
                    assertEquals("baseline", old.tempoBaseline, new.baseline)
                    assertEquals("right", old.tempoRight, new.right)
                    assertEquals("top", old.tempoTop, new.top)
                }
                val (oldIndex, oldX, oldTop) = before.chordLabels(layout, system, text.numberBaseline(number), old, chords, names)
                val placed = ScoreMarks.chords(layout, system, chords, names, number, new, text)
                assertEquals("chords of system ${system.index}", oldIndex, placed.index.toList())
                assertEquals(oldX, placed.x.toList())
                assertEquals(oldTop, placed.top.toList())
                checked += placed.size
            }
        }
        assertTrue("tempo marks and chord names were compared: $checked", checked > 20)
    }
}
