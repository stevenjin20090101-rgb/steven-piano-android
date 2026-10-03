// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import kotlin.math.roundToInt

/**
 * Dynamic range and the quietest note (DESIGN.md › v1.16 — M44), part (b) of [Performance]: with m the piece's mean
 * velocity (after [Expression]) and r the range's spread (Narrow 0.7, Natural 1, Wide 1.3), each note's velocity becomes
 * m + (v − m) × r, then at least the floor (Quietest note, 20 at first, 1–60: a softer note may not strike at all),
 * within 1–127. Pure. (Not `score.Dynamics`, which marks the score's p and f.)
 */
internal object Dynamics {
    /** Shapes [t]'s velocities in place. */
    fun shape(t: NoteTable, range: DynamicRange, floor: Int) {
        var sum = 0.0
        var count = 0
        for (i in 0 until t.size) {
            if (!t.shapes(i)) continue
            sum += t.velocity[i]
            count++
        }
        if (count == 0) return
        val mean = sum / count
        for (i in 0 until t.size) if (t.shapes(i)) t.velocity[i] = velocity(t.velocity[i], mean, range, floor)
    }

    /** [v] spread from [mean] as [range] says, raised to [floor], within 1–127. */
    fun velocity(v: Int, mean: Double, range: DynamicRange, floor: Int): Int =
        maxOf((mean + (v - mean) * range.spread).roundToInt(), floor).coerceIn(1, 127)
}
