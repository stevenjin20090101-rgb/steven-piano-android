// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.score.ScoreStyle.CHORD_GAP_DP
import dev.stevenjin.stevenpiano.score.ScoreStyle.CHORD_SPACING_DP
import dev.stevenjin.stevenpiano.score.ScoreStyle.TEMPO_CLEARANCE
import dev.stevenjin.stevenpiano.score.ScoreStyle.TEMPO_DOT_GAP
import dev.stevenjin.stevenpiano.score.ScoreStyle.TEMPO_DOT_WIDTH
import dev.stevenjin.stevenpiano.score.ScoreStyle.TEMPO_HEAD_BELOW
import dev.stevenjin.stevenpiano.score.ScoreStyle.TEMPO_NOTE_RISE
import dev.stevenjin.stevenpiano.score.ScoreStyle.TEMPO_NOTE_WIDTH
import dev.stevenjin.stevenpiano.score.ScoreStyle.TEMPO_TEXT_GAP
import kotlin.math.max
import kotlin.math.min

/**
 * What the score's marks need to know of the text they set (v1.13 — M32): measured on the tablet (its painter's
 * `TextMeasurer`), estimated for a browser ([ScoreDisplayList.WebText], erring wide; the browser draws each text
 * within the width reserved for it). Pixels.
 */
interface ScoreText {
    /** The tempo mark's note glyphs' staff space: a quarter of their em. */
    val tempoSpace: Float

    /** [text]'s width in the bar numbers' style: a bar number, or a tempo mark's "= 80". */
    fun numberWidth(text: String): Float

    /** [text]'s first baseline in the bar numbers' style, from the top of its line. */
    fun numberBaseline(text: String): Float

    /** A chord name's width, and its line's height, in the chord names' style. */
    fun chordWidth(name: String): Float

    fun chordHeight(name: String): Float
}

/** A system's tempo mark as placed: from [x] on [baseline] (its text's), reaching [right] and up to [top]. */
class TempoPlacement(val mark: TempoMark, val x: Float, val baseline: Float, val right: Float, val top: Float)

/** A system's chord names as placed: the track's chord [index] for each, its box's left [x] and [top]. */
class ChordPlacement(val index: IntArray, val x: FloatArray, val top: FloatArray) {
    val size: Int get() = index.size
}

/**
 * Where a system's tempo mark and chord names go (v1.13 — M32; until then inside the tablet's painter, which now
 * calls this): pure arithmetic over the layout, its skyline and the widths [ScoreText] gives, so the tablet and
 * the web panel place them alike.
 */
object ScoreMarks {
    /**
     * System [system]'s tempo mark, if it has one: after the bar number ([numberWidth] wide) on the numbers' line,
     * lifted clear of any note that reaches up under it (the layout's skyline), never above its band.
     */
    fun tempo(layout: ScoreLayout, system: ScoreSystem, numberWidth: Float, text: ScoreText): TempoPlacement? {
        val tempo = layout.tempoMarkIn(system.index) ?: return null
        val space = layout.metrics.space
        val unit = text.tempoSpace
        val x = max(tempo.x, system.left + numberWidth + space)
        val glyphWidth = TEMPO_NOTE_WIDTH * unit + if (tempo.dotted) (TEMPO_DOT_GAP + TEMPO_DOT_WIDTH) * unit else 0f
        val right = x + glyphWidth + TEMPO_TEXT_GAP * unit + text.numberWidth(tempo.text)
        val height = max((TEMPO_HEAD_BELOW + TEMPO_NOTE_RISE) * unit, text.numberBaseline(tempo.text))
        val onLine = system.trebleTop - ScoreStyle.numberLift(space, layout.metrics.density)
        val lifted = min(onLine, layout.skyline.top(system.index, x, right) - TEMPO_CLEARANCE * space)
        val baseline = max(lifted, min(onLine, system.bandTop + height))   // never above its band
        return TempoPlacement(tempo, x, baseline, right, baseline - height)
    }

    /**
     * System [system]'s chord names ([names] of [chords], as shown): each where its chord begins in the system, its
     * box on the chord line just above the bar number ([numberText]), lifted clear of notes, numerals and the
     * [tempo] mark that reach up into it, never above its band, moved left to stay on the page; a name that would
     * run into the one before it is left out (the waterfall still shows it). A name is measured only once it may
     * fit: the cheap check comes first.
     */
    fun chords(
        layout: ScoreLayout,
        system: ScoreSystem,
        chords: ChordTrack,
        names: Array<String>,
        numberText: String,
        tempo: TempoPlacement?,
        text: ScoreText,
    ): ChordPlacement {
        val metrics = layout.metrics
        val space = metrics.space
        val chordGap = CHORD_GAP_DP * metrics.density
        val chordSpacing = CHORD_SPACING_DP * metrics.density
        val bars = layout.bars
        val from = chords.firstAtOrAfter(bars.startMicros[system.firstBar])
        val until = if (system.lastBar + 1 < bars.count) chords.firstAtOrAfter(bars.startMicros[system.lastBar + 1]) else chords.size
        val numberTop = system.trebleTop - ScoreStyle.numberLift(space, metrics.density) - text.numberBaseline(numberText)
        val index = ArrayList<Int>()
        val xs = ArrayList<Float>()
        val tops = ArrayList<Float>()
        var lastRight = Float.NEGATIVE_INFINITY
        for (i in from until until) {
            val at = system.xAt(chords.startMicros[i])
            if (at < lastRight + chordSpacing) continue
            val width = text.chordWidth(names[i])
            // A name near the system's end is moved left to stay on the page, not cut at its edge.
            val x = max(system.left, min(at, metrics.pageWidth - width))
            if (x < lastRight + chordSpacing) continue
            val right = x + width
            var bottom = min(numberTop - chordGap, layout.skyline.top(system.index, x, right) - TEMPO_CLEARANCE * space)
            if (tempo != null && right >= tempo.x && x <= tempo.right) bottom = min(bottom, tempo.top - chordGap)
            index += i
            xs += x
            tops += max(bottom - text.chordHeight(names[i]), system.bandTop)
            lastRight = right
        }
        return ChordPlacement(index.toIntArray(), xs.toFloatArray(), tops.toFloatArray())
    }
}
