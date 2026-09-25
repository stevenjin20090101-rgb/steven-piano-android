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
 * ones). Drawing keeps to [bandTop]..[bandBottom]: the neighbouring staves or the page's edges.
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
 * A piece laid out as systems of bars on pages, with every note placed: parallel arrays in the
 * note list's order (sorted by start). A note whose [system] is -1 is not drawn (unplayable with
 * folding off). Positions are page coordinates in pixels: [x] is the head's left edge, [y] its
 * centre line. When [quantized] (a sequenced file), heads have values, stems and flags; otherwise
 * (a performance) every head is black and a hairline runs to [durationEnd] for its length.
 */
class ScoreLayout internal constructor(
    val metrics: ScoreMetrics,
    val quantized: Boolean,
    val bars: ScoreBars,
    val systems: List<ScoreSystem>,
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
    /** Flags on the stem this note carries: 0, 1 (eighth) or 2 (sixteenth). */
    val flags: ByteArray,
    val dotted: BooleanArray,
    val dotX: FloatArray,
    val dotY: FloatArray,
    /** Moved one head to the right of its chord's column, as engraving does for a second. */
    val moved: BooleanArray,
    /** Where a performed note's duration hairline ends (NaN: none). */
    val durationEnd: FloatArray,
) {
    val noteCount: Int get() = x.size

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

    /** Width of note [i]'s head: a whole note's is wider. */
    fun headWidth(i: Int): Float = if (head[i].toInt() == Head.WHOLE) metrics.headWidth * WHOLE_TO_BLACK else metrics.headWidth

    internal companion object {
        /** Bravura's whole head is 1.688 spaces wide, the black and half heads 1.18. */
        const val WHOLE_TO_BLACK = 1.688f / 1.18f
    }
}
