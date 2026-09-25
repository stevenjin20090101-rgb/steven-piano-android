// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.midi.TimeSignature
import kotlin.math.max
import kotlin.math.min

/**
 * Beams (DESIGN.md › v1.3 › Score fidelity). Within one bar on one staff, consecutive flagged notes
 * (eighths and shorter) whose onsets fall in the same beat group are joined by a beam instead of
 * their flags. A beat group is a quarter in x/4, a dotted quarter (three eighths) in 6/8, 9/8 and
 * 12/8, a half in 2/2, and a quarter in any other metre. A rest inside the beat ends a group, and so
 * does a note that is not one flagged value (a quarter, or two values struck together, which keep
 * their own stems); a flagged note alone keeps its flag.
 *
 * A group shares one stem direction: up when its heads' average staff position is below the middle
 * line. Its stems end on one straight beam whose slope follows the first and last heads but rises or
 * falls at most one staff space over the group, placed so the stem nearest to it is 3.5 spaces long
 * and every stem reaches the middle line. The primary beam is half a space thick; sixteenths add a
 * second beam 0.75 space further in (centre to centre, so 0.25 space apart), and a lone sixteenth
 * among eighths a stub one head wide pointing into the group.
 *
 * Pure: the layout engine applies it, and the tests call it directly.
 */
object Beams {
    /** The primary beam's thickness, in staff spaces (a secondary beam is as thick). */
    const val THICKNESS = 0.5f

    /** From a beam's outer edge to the next beam's, in staff spaces: 0.25 space between them. */
    const val SECONDARY_OFFSET = 0.75f

    /** A beam rises or falls at most this far over its whole group, in staff spaces. */
    const val MAX_RISE = 1f

    /** The middle line's staff position (lines are 0, 2, 4, 6, 8 from the bottom). */
    private const val MIDDLE_LINE = 4

    /** A compound metre, counted in dotted quarters: 6/8, 9/8, 12/8 (and 15/8 and on). */
    fun compound(time: TimeSignature): Boolean =
        time.denominator == 8 && time.numerator >= 6 && time.numerator % 3 == 0

    /** The beat group [time] beams within, in sixteenths: 6 in compound metres, 8 in 2/2, else 4. */
    fun beatSixteenths(time: TimeSignature): Int = when {
        compound(time) -> 6
        time.numerator == 2 && time.denominator == 2 -> 8
        else -> 4
    }

    /** Stems go up when the heads' average staff position ([positionSum] over [heads]) is below the middle line. */
    fun stemsUp(positionSum: Int, heads: Int): Boolean = positionSum < MIDDLE_LINE * heads

    /**
     * The beamed groups among [count] notes of one staff in time order: note k sits in [bar] and in
     * beat [beat] of it, is [beamable] when it is one flagged value (a chord counts as one note), and
     * has a rest before it when [restBefore]. Consecutive beamable notes of one bar and beat with no
     * rest between them share a group; [groupOf] gets each note's group, or -1 for a note that is not
     * beamed (a group of one keeps its flag). Returns the number of groups.
     */
    fun group(
        bar: IntArray,
        beat: IntArray,
        beamable: BooleanArray,
        restBefore: BooleanArray,
        count: Int,
        groupOf: IntArray,
    ): Int {
        var groups = 0
        var k = 0
        while (k < count) {
            if (!beamable[k]) {
                groupOf[k++] = -1
                continue
            }
            var end = k + 1
            while (end < count && beamable[end] && !restBefore[end] && bar[end] == bar[k] && beat[end] == beat[k]) end++
            val id = if (end - k >= 2) groups++ else -1
            for (j in k until end) groupOf[j] = id
            k = end
        }
        return groups
    }

    /**
     * One group's beam: the tips of [count] stems at [x] (in order, left to right) on one straight
     * line. [far] is the y of each stem's far head (the highest with stems [up], the lowest with stems
     * down) and [middle] the y of its staff's middle line (page coordinates, y down). The line's slope
     * follows the first and last far heads, its rise held to [MAX_RISE] spaces; it sits [stem] clear of
     * the nearest far head and reaches the middle line at every stem. Writes each stem's tip to [tip].
     */
    fun line(
        x: FloatArray,
        far: FloatArray,
        middle: FloatArray,
        count: Int,
        up: Boolean,
        space: Float,
        stem: Float,
        tip: FloatArray,
    ) {
        if (count <= 0) return
        val x0 = x[0]
        val run = x[count - 1] - x0
        val rise = (far[count - 1] - far[0]).coerceIn(-MAX_RISE * space, MAX_RISE * space)
        val slope = if (run > 0f) rise / run else 0f
        var y0 = if (up) Float.MAX_VALUE else -Float.MAX_VALUE
        for (k in 0 until count) {
            val along = slope * (x[k] - x0)
            y0 = if (up) min(y0, min(far[k] - stem, middle[k]) - along) else max(y0, max(far[k] + stem, middle[k]) - along)
        }
        for (k in 0 until count) tip[k] = y0 + slope * (x[k] - x0)
    }

    /**
     * Which way the stub of a lone sixteenth points, for note [index] of a group of [count] whose onset
     * is [offset] sixteenths into its beat: into the group from either end; inside the group, back
     * toward the note it completes an eighth with (an onset off the eighth grid), else forward.
     */
    fun stubPointsRight(index: Int, count: Int, offset: Int): Boolean = when {
        index == 0 -> true
        index == count - 1 -> false
        else -> offset % 2 == 0
    }
}
