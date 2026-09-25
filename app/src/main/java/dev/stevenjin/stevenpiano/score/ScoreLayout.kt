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

/** Note head shapes. */
object Head {
    const val BLACK = 0
    const val HALF = 1
    const val WHOLE = 2
}

/** Glyphs a system draws at its start or at a change: clefs, key-signature accidentals, time-signature digits. */
object Sign {
    const val G_CLEF = 0
    const val F_CLEF = 1
    const val SHARP = 2
    const val FLAT = 3
    const val NATURAL = 4

    /** The digit d is `DIGIT + d`. */
    const val DIGIT = 10
}

/**
 * The bars as laid out: when each starts (microseconds and beats) and where, on its page, it is
 * drawn: [left] to [right] is the bar (its tap target; the first bar of a system reaches back over
 * the clef), and its time runs from [contentLeft] to [contentRight], so notes and the cursor sit at
 * the same x for the same moment. Positions within a bar are in beats, through the tempo map, so a
 * tempo change never bends a bar.
 */
class ScoreBars internal constructor(
    val tempo: TempoMap,
    val startMicros: LongArray,
    private val startBeat: DoubleArray,
    private val beats: DoubleArray,
    val left: FloatArray,
    val right: FloatArray,
    val contentLeft: FloatArray,
    val contentRight: FloatArray,
) {
    val count: Int get() = startMicros.size

    /** The bar sounding at [micros] (bar 0 before the start). */
    fun barAt(micros: Long): Int {
        var lo = 0
        var hi = startMicros.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (startMicros[mid] <= micros) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The page x of [micros] in [bar], held to the bar's own span. */
    fun xAt(bar: Int, micros: Long): Float {
        val fraction = ((tempo.microsToBeats(micros) - startBeat[bar]) / beats[bar]).coerceIn(0.0, 1.0)
        return contentLeft[bar] + (fraction * (contentRight[bar] - contentLeft[bar])).toFloat()
    }
}

/**
 * One system: a grand staff across a page holding [barCount] bars from [firstBar], with its clefs,
 * key signature and (at the start and at changes) time signature as [signKind] glyphs placed at
 * [signX] (left edge) and [signY] (the glyph's SMuFL baseline). Page coordinates, in pixels.
 * Notes [firstNote] until [noteEnd] start in it (skip any whose system is another: unplayable
 * ones), and the tied heads [firstTied] until [tiedEnd] are in it. Drawing keeps to
 * [bandTop]..[bandBottom]: the neighbouring staves or the page's edges.
 */
class ScoreSystem internal constructor(
    val index: Int,
    val page: Int,
    /** Which of the pages side by side this system's page sits in: page modulo pages. */
    val slot: Int,
    /** The y of the treble staff's top line. */
    val trebleTop: Float,
    /** The y of the bass staff's top line. */
    val bassTop: Float,
    val staffHeight: Float,
    /** Where the staff lines start (the system's opening line) and end (its last bar line). */
    val left: Float,
    val right: Float,
    val firstBar: Int,
    val barCount: Int,
    val firstNote: Int,
    val noteEnd: Int,
    /** Head indices of the tied heads drawn in this system (0 until 0 when there are none). */
    val firstTied: Int,
    val tiedEnd: Int,
    val bandTop: Float,
    val bandBottom: Float,
    val signKind: IntArray,
    val signX: FloatArray,
    val signY: FloatArray,
    /** The piece ends in this system: its last line is the final bar line. */
    val final: Boolean,
    private val bars: ScoreBars,
) {
    val trebleBottom: Float get() = trebleTop + staffHeight
    val bassBottom: Float get() = bassTop + staffHeight
    val lastBar: Int get() = firstBar + barCount - 1

    /** The page x of [micros], held to this system's bars: where the cursor is drawn. */
    fun xAt(micros: Long): Float = bars.xAt(bars.barAt(micros).coerceIn(firstBar, lastBar), micros)

    /** The bar a tap at page x [x] means (the nearest at either end). */
    fun barAtX(x: Float): Int {
        for (bar in firstBar until lastBar) if (x < bars.right[bar]) return bar
        return lastBar
    }
}

/**
 * A piece laid out as systems of bars on pages, with every note placed: parallel arrays of heads.
 * Heads 0 until [noteCount] are the notes' own, in the note list's order (sorted by start); a note
 * whose [system] is -1 is not drawn (unplayable with folding off). When [quantized] (a sequenced
 * file) the score is engraved: heads have values, stems, flags or [beams], silences are [rests],
 * and a note that crosses a bar line or lasts a length no one value writes has tied heads after
 * [noteCount] ([tiedHeadCount], [tiedHead]; each [tiedNote]'s, sounding from [tiedStartMicros])
 * joined to it by [ties]. Otherwise (a performance) every head is black and a hairline runs to
 * [durationEnd] for its length. Positions are page coordinates in pixels: [x] is a head's left
 * edge, [y] its centre line.
 */
class ScoreLayout internal constructor(
    val metrics: ScoreMetrics,
    val quantized: Boolean,
    val bars: ScoreBars,
    val systems: List<ScoreSystem>,
    /** The notes: heads 0 until this are each note's own. */
    val noteCount: Int,
    val system: IntArray,
    val x: FloatArray,
    val y: FloatArray,
    /** [Head] kinds. */
    val head: ByteArray,
    val treble: BooleanArray,
    /** Ledger lines: negative below the staff, positive above. */
    val ledgers: ByteArray,
    /** [Accidental] kinds, and where each sign's left edge goes. */
    val accidental: ByteArray,
    val accidentalX: FloatArray,
    /** The stem this note carries for its chord (NaN: none): its left edge and its two ends. */
    val stemX: FloatArray,
    val stemFrom: FloatArray,
    val stemTo: FloatArray,
    val stemUp: BooleanArray,
    /** Flags on the stem this note carries: 0, 1 (eighth) or 2 (sixteenth); 0 when it is beamed. */
    val flags: ByteArray,
    val dotted: BooleanArray,
    val dotX: FloatArray,
    val dotY: FloatArray,
    /** Moved one head to the right of its chord's column, as engraving does for a second. */
    val moved: BooleanArray,
    /** Where a performed note's duration hairline ends (NaN: none). */
    val durationEnd: FloatArray,
    /** The note each tied head (head [noteCount] + r) holds on, and when it is reached: its written onset. */
    val tiedNote: IntArray,
    val tiedStartMicros: LongArray,
    /** Note i's tied heads are [tiedByNote] from tiedFrom[i] until tiedFrom[i + 1], in time order. */
    private val tiedFrom: IntArray,
    private val tiedByNote: IntArray,
    /** Beams joining flagged notes within a beat (sequenced files only; flags are then 0). */
    val beams: ScoreBeams,
    /** Rests on each staff's silences (sequenced files only). */
    val rests: ScoreRests,
    /** Ties from each written piece of a note to the next (sequenced files only). */
    val ties: ScoreTies,
) {
    /** Every head: the notes' own and the tied ones. */
    val headCount: Int get() = x.size

    /** How many tied heads note [note] has. */
    fun tiedHeadCount(note: Int): Int = tiedFrom[note + 1] - tiedFrom[note]

    /** The head index of note [note]'s tied head [k] (in time order). */
    fun tiedHead(note: Int, k: Int): Int = tiedByNote[tiedFrom[note] + k]

    /** Pages at [ScoreMetrics.systemsPerPage] systems each. */
    val pageCount: Int get() = (systems.size + metrics.systemsPerPage - 1) / metrics.systemsPerPage

    /** The system sounding at [micros]: binary search over the systems' first bars. */
    fun systemAt(micros: Long): Int {
        var lo = 0
        var hi = systems.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (bars.startMicros[systems[mid].firstBar] <= micros) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The systems on [page] (empty past the last page). */
    fun systemsOn(page: Int): IntRange {
        if (page < 0) return IntRange.EMPTY
        val first = page * metrics.systemsPerPage
        return first until minOf(systems.size, first + metrics.systemsPerPage)
    }

    /** Width of head [i]: a whole note's is wider. */
    fun headWidth(i: Int): Float = if (head[i].toInt() == Head.WHOLE) metrics.headWidth * WHOLE_TO_BLACK else metrics.headWidth

    internal companion object {
        /** Bravura's whole head is 1.688 spaces wide, the black and half heads 1.18. */
        const val WHOLE_TO_BLACK = 1.688f / 1.18f
    }
}

/**
 * The beams as drawn. Each entry is one beam segment: a band [Beams.THICKNESS] spaces thick whose
 * outer edge (the stem tips' side) runs from ([x1], [y1]) to ([x2], [y2]) in page coordinates, the
 * thickness lying toward the heads: below the edge when the group's stems are [up], above it
 * otherwise. [level] 1 is the primary beam, 2 the sixteenths' secondary beam; a [stub] is a lone
 * sixteenth's partial beam, one head wide. Entries of one [group] lie on one line. Sorted by system:
 * [inSystem] gives a system's entries.
 */
class ScoreBeams internal constructor(
    val system: IntArray,
    val x1: FloatArray,
    val y1: FloatArray,
    val x2: FloatArray,
    val y2: FloatArray,
    val up: BooleanArray,
    val level: ByteArray,
    val stub: BooleanArray,
    val group: IntArray,
    private val systemStart: IntArray,
) {
    val size: Int get() = system.size

    /** The entries drawn in system [s]. */
    fun inSystem(s: Int): IntRange = runOf(systemStart, s)
}

/** A system's run in a collection sorted by system, from its prefix counts. */
internal fun runOf(systemStart: IntArray, s: Int): IntRange =
    if (s < 0 || s + 1 >= systemStart.size) IntRange.EMPTY else systemStart[s] until systemStart[s + 1]

/**
 * A stable counting sort of [size] entries by their [system] (0 until [systemCount]): [order] lists
 * the entries system by system, and system s's run is [start] (s) until [start] (s + 1).
 */
internal class BySystem(system: IntArray, size: Int, systemCount: Int) {
    val start = IntArray(systemCount + 1)
    val order = IntArray(size)

    init {
        for (k in 0 until size) start[system[k] + 1]++
        for (s in 0 until systemCount) start[s + 1] += start[s]
        val next = start.copyOf(systemCount)
        for (k in 0 until size) order[next[system[k]]++] = k
    }
}

/**
 * The rests as drawn: glyph [value] (in sixteenths: [Rests.WHOLE] 16, half 8, quarter 4, eighth 2,
 * sixteenth 1) with its left edge at [x] and its SMuFL origin at [y]: the fourth line for a whole
 * rest (it hangs from it), the middle line for the others. A [wholeBar] rest is centred in its [bar],
 * whatever the metre. Sorted by system: [inSystem] gives a system's rests.
 */
class ScoreRests internal constructor(
    val system: IntArray,
    val x: FloatArray,
    val y: FloatArray,
    val value: ByteArray,
    val wholeBar: BooleanArray,
    val treble: BooleanArray,
    val bar: IntArray,
    private val systemStart: IntArray,
) {
    val size: Int get() = system.size

    /** The rests drawn in system [s]. */
    fun inSystem(s: Int): IntRange = runOf(systemStart, s)
}

/**
 * The ties as drawn: an arc from ([x1], [y1]) to ([x2], [y2]) in page coordinates, bowing up when
 * [above] (away from stems pointing down) and down otherwise, joining heads [from] and [to]. A tie
 * across a system break is two arcs: the first to its system's end ([to] -1), the second in from
 * the next system's start ([from] -1). Sorted by system: [inSystem] gives a system's ties.
 */
class ScoreTies internal constructor(
    val system: IntArray,
    val x1: FloatArray,
    val y1: FloatArray,
    val x2: FloatArray,
    val y2: FloatArray,
    val above: BooleanArray,
    val from: IntArray,
    val to: IntArray,
    private val systemStart: IntArray,
) {
    val size: Int get() = system.size

    /** The ties drawn in system [s]. */
    fun inSystem(s: Int): IntRange = runOf(systemStart, s)
}
