// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

/**
 * Rests (DESIGN.md › v1.3 › Score fidelity). On each staff of each bar of a sequenced file, a
 * silence of a sixteenth or more (from the bar's start, between where the written notes so far have
 * all ended and the next onset, and to the bar's end) is written as rests: the largest plain values
 * that tile it on the beat grid. A value shorter than the beat stays inside one beat, at a multiple
 * of itself from the beat's start (in a compound metre, a quarter or an eighth rest may start on any
 * eighth of its dotted-quarter beat); a value of a beat or longer starts on a multiple of itself from
 * the bar's start, and a compound metre uses none. So a dotted quarter's silence on a beat is a
 * quarter rest then an eighth, and one that starts half a beat in is an eighth rest then a quarter
 * on the beat. A whole bar of silence is one whole rest centred in the bar, whatever the metre.
 *
 * Lengths and places are counted in sixteenths from the bar's start. Pure and allocation-free.
 */
object Rests {
    const val WHOLE = 16
    const val HALF = 8
    const val QUARTER = 4
    const val EIGHTH = 2
    const val SIXTEENTH = 1

    /** The plain values a silence is written with, longest first. */
    private val VALUES = intArrayOf(WHOLE, HALF, QUARTER, EIGHTH, SIXTEENTH)

    /**
     * The rests that write the silence from [from] to [to] in a bar [barLength] long whose beat is
     * [beat] sixteenths ([compound]: a dotted-quarter beat): each rest's place into [outStart] and
     * its value into [outLength], at most [max] of them; returns how many. A silence filling the
     * whole bar is one entry from 0 to [barLength]: the whole-bar rest.
     */
    fun tile(
        from: Int,
        to: Int,
        barLength: Int,
        beat: Int,
        compound: Boolean,
        outStart: IntArray,
        outLength: IntArray,
        max: Int = outStart.size,
    ): Int {
        if (to <= from || max <= 0) return 0
        if (from <= 0 && to >= barLength) {
            outStart[0] = 0
            outLength[0] = barLength
            return 1
        }
        var count = 0
        var at = from
        while (at < to && count < max) {
            var chosen = SIXTEENTH
            for (v in VALUES) {
                if (at + v <= to && fits(at, v, beat, compound)) {
                    chosen = v
                    break
                }
            }
            outStart[count] = at
            outLength[count] = chosen
            count++
            at += chosen
        }
        return count
    }

    /** Whether a rest of [value] may start at [at]: inside its beat on its own grid, or on its own multiple when a beat or longer. */
    fun fits(at: Int, value: Int, beat: Int, compound: Boolean): Boolean {
        val b = beat.coerceAtLeast(1)
        if (value < b) {
            val offset = at % b
            if (offset + value > b) return false
            val grid = if (compound) minOf(value, EIGHTH) else value
            return offset % grid == 0
        }
        return !compound && at % value == 0
    }
}
