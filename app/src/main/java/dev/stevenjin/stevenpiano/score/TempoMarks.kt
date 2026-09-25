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
import kotlin.math.abs

/**
 * A tempo mark over [system]: a note glyph (a quarter, or a dotted quarter when [dotted]) and
 * [text], "= 80", on the system's bar-number line at its first bar's left, [x] (page coordinates;
 * drawn after the bar number, over the clef and key signature, and lifted clear of any stem that
 * reaches that high).
 */
data class TempoMark(val system: Int, val x: Float, val bpm: Int, val dotted: Boolean) {
    /** What follows the note glyph. */
    val text: String get() = "= $bpm"
}

/**
 * Tempo marks (DESIGN.md › v1.3 › Score fidelity): at the first system, the tempo the file starts
 * at ([TempoMap.tempoAt] tick 0) as quarter notes a minute, or dotted quarters in a compound metre
 * (6/8, 9/8, 12/8), and again at any system whose starting tempo is more than 10 % away from the
 * last mark's. Every file gets them, performed or sequenced. Pure.
 */
object TempoMarks {
    /** A system starting more than this share away from the last mark's tempo gets a mark of its own. */
    const val CHANGE = 0.10

    /** The largest number a mark shows (a crafted file can ask for a tempo of a microsecond a beat). */
    const val MAX_BPM = 9_999

    /** The number a mark shows for [microsPerQuarter]: quarters a minute, or dotted quarters when [dotted]. */
    fun bpm(microsPerQuarter: Int, dotted: Boolean): Int {
        val quarters = 60_000_000.0 / microsPerQuarter.coerceAtLeast(1)
        return Math.round(if (dotted) quarters / 1.5 else quarters).toInt().coerceIn(1, MAX_BPM)
    }

    /**
     * The marks for systems whose first bars start at [startTicks] ([compound]: in a compound metre)
     * and whose marks would sit at [x]: the first system's, from the tempo at tick 0, then every
     * system whose starting tempo differs from the last mark's by more than [CHANGE].
     */
    fun marks(tempo: TempoMap, startTicks: LongArray, compound: BooleanArray, x: FloatArray): List<TempoMark> {
        val marks = ArrayList<TempoMark>()
        var last = 0.0
        for (s in startTicks.indices) {
            val micros = tempo.tempoAt(if (s == 0) 0L else startTicks[s])
            val quarters = 60_000_000.0 / micros
            if (s == 0 || abs(quarters - last) > CHANGE * last) {
                marks += TempoMark(s, x[s], bpm(micros, compound[s]), compound[s])
                last = quarters
            }
        }
        return marks
    }
}
