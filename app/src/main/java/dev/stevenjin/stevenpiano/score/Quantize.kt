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
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * A written note value: [power] is log2 of its length in quarter notes, from -2 (a sixteenth) to 2
 * (a whole note), and [dotted] adds half again.
 */
data class NoteValue(val power: Int, val dotted: Boolean) {
    /** Hollow whole head, no stem. */
    val whole: Boolean get() = power >= 2

    /** Hollow head (half and whole notes). */
    val hollow: Boolean get() = power >= 1

    /** Eighth and sixteenth flags: 1 or 2; 0 for a quarter and longer. */
    val flags: Int get() = if (power < 0) -power else 0

    companion object {
        const val SIXTEENTH = -2
        const val EIGHTH = -1
        const val QUARTER = 0
        const val HALF = 1
        const val WHOLE = 2
    }
}

/**
 * Whether a file was written on a grid (sequenced) or played (performed), and the written value of
 * a note's length. Onsets are compared with a sixteenth grid (ppq / 4 ticks) in ticks, through the
 * tempo map, never in microseconds, so tempo changes and rubato written as tempo never pull a
 * sequenced note off the grid.
 */
object Quantize {
    /** An onset counts as on the grid within this share of a sixteenth. */
    const val TOLERANCE = 0.12

    /** A file is sequenced when at least this share of its onsets is on the grid. */
    const val SHARE = 0.80

    /** A length within this share of 1.5 times a value is that value, dotted. */
    const val DOT_TOLERANCE = 0.12

    /** Whether at least [share] of [starts] (microseconds) fall within [tolerance] of a sixteenth, in ticks. */
    fun onGrid(starts: LongArray, tempo: TempoMap, tolerance: Double = TOLERANCE, share: Double = SHARE): Boolean {
        if (starts.isEmpty()) return false
        val step = tempo.ppq / 4.0
        var on = 0
        for (start in starts) {
            val sixteenths = tempo.ticksAt(start) / step
            if (abs(sixteenths - Math.rint(sixteenths)) <= tolerance) on++
        }
        return on >= share * starts.size
    }

    /**
     * The written value nearest a length of [durationTicks]: dotted when within 12 % of 1.5 times a
     * value, otherwise the nearest of sixteenth … whole on a log2 scale (a 32nd reads as a
     * sixteenth, anything past a whole note as a whole note).
     */
    fun value(durationTicks: Long, ppq: Int): NoteValue {
        if (durationTicks <= 0L || ppq <= 0) return NoteValue(NoteValue.SIXTEENTH, false)
        val quarters = durationTicks.toDouble() / ppq
        for (power in NoteValue.WHOLE downTo NoteValue.SIXTEENTH) {
            val dottedLength = 1.5 * Math.scalb(1.0, power)
            if (abs(quarters / dottedLength - 1.0) <= DOT_TOLERANCE) return NoteValue(power, true)
        }
        return NoteValue(log2(quarters).roundToInt().coerceIn(NoteValue.SIXTEENTH, NoteValue.WHOLE), false)
    }
}
