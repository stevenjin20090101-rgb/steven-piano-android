// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

import kotlin.math.roundToLong

/**
 * Ties (DESIGN.md › v1.3 › Score fidelity). A written note (a sequenced note on the sixteenth grid,
 * from its onset to its end) that crosses a bar line is split there into two written notes joined by
 * a tie; a length no single value writes, plain or dotted (five sixteenths, seven, ten…), is split
 * into the largest value that fits and the remainder, tied (a quarter tied to a sixteenth). Each
 * piece's value is [Quantize.value] of its length. The engine keeps the first piece as the note's
 * own head (with its accidental) and draws the others as tied heads without one.
 *
 * In a compound metre (6/8, 9/8, 12/8) the plain half and whole are not among the values: four or
 * eight eighths cut across the dotted-quarter beat, so a full bar of 9/8 is a dotted half tied to a
 * dotted quarter (not a whole note tied to an eighth), and four eighths a dotted quarter tied to an
 * eighth.
 *
 * Lengths are counted in sixteenths. Pure and allocation-free.
 */
object Ties {
    /** Sixteenths one written value lasts, longest first: dotted whole, whole, dotted half … eighth, sixteenth. */
    private val VALUES = intArrayOf(24, 16, 12, 8, 6, 4, 3, 2, 1)

    /** The values of a compound metre: no plain half or whole. */
    private val COMPOUND_VALUES = intArrayOf(24, 12, 6, 4, 3, 2, 1)

    private fun values(compound: Boolean) = if (compound) COMPOUND_VALUES else VALUES

    /** Whether one written value, plain or dotted, lasts [sixteenths] (in a [compound] metre, one of its values). */
    fun representable(sixteenths: Int, compound: Boolean = false): Boolean {
        for (v in values(compound)) if (v == sixteenths) return true
        return false
    }

    /** The longest single value that fits in [sixteenths] (a sixteenth for anything shorter). */
    fun largest(sixteenths: Int, compound: Boolean = false): Int {
        for (v in values(compound)) if (v <= sixteenths) return v
        return 1
    }

    /**
     * [sixteenths] as tied values, largest first (5 is 4 + 1, 7 is 6 + 1, 10 is 8 + 2; in a
     * [compound] metre 18 is 12 + 6), into [out] from [at], at most [max] of them; returns how many.
     */
    fun split(sixteenths: Int, out: IntArray, compound: Boolean = false, at: Int = 0, max: Int = out.size - at): Int {
        var left = sixteenths
        var count = 0
        while (left > 0 && count < max) {
            val v = largest(left, compound)
            out[at + count++] = v
            left -= v
        }
        return count
    }

    /** The written value of [sixteenths] at [ppq]: [Quantize.value] of that many ticks. */
    fun value(sixteenths: Int, ppq: Int): NoteValue = Quantize.value((sixteenths * ppq / 4.0).roundToLong(), ppq)

    /**
     * The written pieces of a note from [start] to [end] (ticks, on the grid; [step] ticks a sixteenth)
     * that starts in bar [bar] of bars from [barStart] to [barEnd] ([compound] for each bar in a
     * compound metre): split at every bar line it crosses, each bar's piece split into values
     * ([split]). Writes each piece's onset to [outTick] and its length in sixteenths to [outLength], at
     * most [max] pieces (the note is cut there), and returns how many. A piece shorter than half a
     * sixteenth (an end a hair past a bar line) is dropped, and nothing is written past the last bar;
     * a note always gets at least one piece.
     */
    fun segments(
        start: Long,
        end: Long,
        barStart: LongArray,
        barEnd: LongArray,
        compound: BooleanArray,
        bar: Int,
        step: Double,
        max: Int,
        outTick: LongArray,
        outLength: IntArray,
    ): Int {
        var count = 0
        var b = bar
        var from = start
        while (from < end && b < barStart.size && count < max) {
            val until = minOf(end, barEnd[b])
            val sixteenths = Math.round((until - from) / step).toInt()
            var done = 0
            var left = sixteenths
            while (left > 0 && count < max) {
                val v = largest(left, compound[b])
                outTick[count] = from + Math.round(done * step)
                outLength[count] = v
                count++
                done += v
                left -= v
            }
            from = barEnd[b]
            b++
        }
        if (count == 0 && max > 0) {
            outTick[0] = start
            outLength[0] = 1
            count = 1
        }
        return count
    }
}
