// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================


package dev.stevenjin.stevenpiano.score

import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.KeySignature
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.midi.TempoMap
import dev.stevenjin.stevenpiano.midi.TimeSignature
import dev.stevenjin.stevenpiano.ui.components.StaffPitch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lays a piece out as a paged score (DESIGN.md › v1.2 › Score): systems of [ScoreMetrics.barsPerSystem]
 * bars stacked [ScoreMetrics.systemsPerPage] to a page, every bar of a system as wide as the others,
 * each system opening with its clefs and the key signature in force, the time signature at the
 * first system and wherever the metre changes (a key change mid-system draws the new key there).
 * Within a bar a note sits at its place in beats, which is where the cursor passes as it sounds.
 *
 * Notes are spelled in the key ([Spelling]) with one accidental per pitch per bar, and placed on the
 * treble staff from middle C up, on the bass staff below. A file on a sixteenth grid ([Quantize])
 * gets note values: hollow whole and half heads, black heads with stems 3.5 spaces long (up below
 * the middle line; a chord's farthest head decides, and two values struck together on one staff
 * stem apart, the higher up), eighth and sixteenth flags, and dots. A performed file keeps black
 * heads with a hairline for each note's length. Heads a second apart move aside, accidentals in a
 * chord stack leftwards so they don't collide. No beams, rests, ties, tuplets or grace notes.
 *
 * Pure (no Android, no Compose): it runs off the main thread and in the JVM tests.
 */
object ScoreLayoutEngine {
    /** In a performance, notes starting within this of a chord's first note are the chord (performed chords are not exact). */
    const val CHORD_MICROS = 30_000L

    /** Stem length from the head nearest its end, in staff spaces. */
    const val STEM_SPACES = 3.5f

    /**
     * [notes] (sorted by start) are drawn at [keys], the keys they sound after transposing and
     * folding (`KeyMap.UNPLAYABLE` ones are left out); [bars] are the bar starts in microseconds;
     * [keySignatures] and [timeSignatures] as the file gives them (key signatures already moved by
     * any transpose).
     */
    fun layout(
        notes: NoteList,
        keys: IntArray,
        tempo: TempoMap,
        bars: LongArray,
        keySignatures: List<KeySignature>,
        metrics: ScoreMetrics,
        timeSignatures: List<TimeSignature> = listOf(TimeSignature.Common),
    ): ScoreLayout {
        require(keys.size == notes.size) { "One key per note" }
        return Build(notes, keys, tempo, bars, keySignatures, metrics, timeSignatures).run()
    }
}

/** One layout's working state. */
private class Build(
    private val notes: NoteList,
    private val keys: IntArray,
    private val tempo: TempoMap,
    barStarts: LongArray,
    keySignatures: List<KeySignature>,
    private val m: ScoreMetrics,
    timeSignatures: List<TimeSignature>,
) {
    private val space = m.space
    private val half = m.space / 2
    private val stemWidth = m.density   // a 1 dp hairline
    private val ppq = tempo.ppq
    private val barMicros = if (barStarts.isEmpty()) longArrayOf(0L) else barStarts
    private val barCount = barMicros.size
    private val barTick = LongArray(barCount) { tempo.microsToTicks(barMicros[it]) }
    private val times = timeSignatures.filter { it.valid }.sortedBy { it.tick }
    private val keyList = keySignatures.sortedBy { it.tick }
    private val keyTicks = LongArray(keyList.size) { keyList[it].tick }
    private val keySharps = IntArray(keyList.size) { keyList[it].sharps.coerceIn(-7, 7) }

    private val barsPerSystem = m.barsPerSystem.coerceAtLeast(1)
    private val systemsPerPage = m.systemsPerPage.coerceAtLeast(1)
    private val systemCount = (barCount + barsPerSystem - 1) / barsPerSystem

    // Bars.
    private val barEndTick = LongArray(barCount) { b ->
        if (b + 1 < barCount) barTick[b + 1] else barTick[b] + max(1L, timeAt(barTick[b]).ticksIn(1, ppq))
    }
    private val metre = Array(barCount) { timeAt(barTick[it]) }
    private val barKey = IntArray(barCount) { sharpsAt(barTick[it]) }
    private val barLeft = FloatArray(barCount)
    private val barRight = FloatArray(barCount)
    private val contentLeft = FloatArray(barCount)
    private val contentRight = FloatArray(barCount)
    private val scoreBars = ScoreBars(
        tempo = tempo,
        startMicros = barMicros,
        startBeat = DoubleArray(barCount) { barTick[it].toDouble() / ppq },
        beats = DoubleArray(barCount) { max(1L, barEndTick[it] - barTick[it]).toDouble() / ppq },
        left = barLeft,
        right = barRight,
        contentLeft = contentLeft,
        contentRight = contentRight,
    )

    // Systems.
    private val trebleTop = FloatArray(systemCount)
    private val signs = Array(systemCount) { Signs() }
    private val noteFrom = IntArray(systemCount)
    private val noteEnd = IntArray(systemCount)

    // Notes.
    private val n = notes.size
    private val system = IntArray(n) { -1 }
    private val x = FloatArray(n)
    private val y = FloatArray(n)
    private val head = ByteArray(n)
    private val treble = BooleanArray(n)
    private val ledgers = ByteArray(n)
    private val accidental = ByteArray(n)
    private val accidentalX = FloatArray(n) { Float.NaN }
    private val stemX = FloatArray(n) { Float.NaN }
    private val stemFrom = FloatArray(n)
    private val stemTo = FloatArray(n)
    private val stemUp = BooleanArray(n)
    private val flags = ByteArray(n)
    private val dotted = BooleanArray(n)
    private val dotX = FloatArray(n) { Float.NaN }
    private val dotY = FloatArray(n)
    private val moved = BooleanArray(n)
    private val durationEnd = FloatArray(n) { Float.NaN }
    private val position = IntArray(n)
    private val startTick = LongArray(n)
    private val bar = IntArray(n)
    private val valueFlags = ByteArray(n)
    private val quantized = Quantize.onGrid(notes.startMicros, tempo)

    fun run(): ScoreLayout {
        for (s in 0 until systemCount) layoutSystem(s)
        placeNotes()
        chords()
        noteRanges()
        return ScoreLayout(
            metrics = m,
            quantized = quantized,
            bars = scoreBars,
            systems = List(systemCount) { makeSystem(it) },
            system = system,
            x = x,
            y = y,
            head = head,
            treble = treble,
            ledgers = ledgers,
            accidental = accidental,
            accidentalX = accidentalX,
            stemX = stemX,
            stemFrom = stemFrom,
            stemTo = stemTo,
            stemUp = stemUp,
            flags = flags,
            dotted = dotted,
            dotX = dotX,
            dotY = dotY,
            moved = moved,
            durationEnd = durationEnd,
        )
    }

    // --- Systems and bars --------------------------------------------------------------------

    /** The system's clefs, key and time signatures, and its bars' places across the page. */
    private fun layoutSystem(s: Int) {
        val row = s % systemsPerPage
        val top = m.staffTop(row)
        trebleTop[s] = top
        val trebleBottom = top + m.staffHeight
        val bassBottom = trebleBottom + m.staveGap + m.staffHeight
        val first = s * barsPerSystem
        val count = min(barsPerSystem, barCount - first)
        val left = m.marginX
        val sign = signs[s]
        var at = left + CLEF_INSET * space
        sign.add(Sign.G_CLEF, at, trebleBottom - space)           // on the G line
        sign.add(Sign.F_CLEF, at, bassBottom - 3 * space)         // on the F line
        at += m.clefWidth + AFTER_CLEF * space
        at = keySignature(sign, barKey[first], 0, at, trebleBottom, bassBottom)
        if (showsTime(first)) at = timeSignature(sign, metre[first], at, trebleBottom, bassBottom)
        val barWidth = max(m.headWidth, (m.pageWidth - m.marginX - at) / barsPerSystem)
        for (k in 0 until count) {
            val b = first + k
            val start = at + k * barWidth
            barLeft[b] = if (k == 0) left else start
            barRight[b] = start + barWidth
            var content = start
            if (k > 0) {
                if (barKey[b] != barKey[b - 1]) {
                    content = keySignature(sign, barKey[b], barKey[b - 1], content + BEFORE_CHANGE * space, trebleBottom, bassBottom)
                }
                if (showsTime(b)) content = timeSignature(sign, metre[b], content + BEFORE_CHANGE * space, trebleBottom, bassBottom)
            }
            contentLeft[b] = content + BAR_LEFT_PAD * space
            contentRight[b] = max(contentLeft[b], barRight[b] - m.headWidth)
        }
    }

    private fun showsTime(b: Int): Boolean = b == 0 || !metre[b].sameAs(metre[b - 1])

    /**
     * Draws the key of [sharps] from [x0] on both staves; at a change to no accidentals, naturals
     * cancel the key before ([previous]). Returns where what follows starts.
     */
    private fun keySignature(sign: Signs, sharps: Int, previous: Int, x0: Float, trebleBottom: Float, bassBottom: Float): Float {
        val shown = if (sharps == 0) previous else sharps
        val count = abs(shown)
        if (count == 0) return x0
        val kind = when {
            sharps == 0 -> Sign.NATURAL
            sharps > 0 -> Sign.SHARP
            else -> Sign.FLAT
        }
        val positions = if (shown > 0) TREBLE_SHARPS else TREBLE_FLATS
        val width = signWidth(kind) * space
        var at = x0
        for (i in 0 until count) {
            sign.add(kind, at, trebleBottom - positions[i] * half)
            sign.add(kind, at, bassBottom - (positions[i] - 2) * half)   // the bass staff sits a third lower
            at += width + KEY_STEP_GAP * space
        }
        return at + AFTER_KEY * space
    }

    /** Draws [time]'s digits from [x0] on both staves, each number centred; returns where what follows starts. */
    private fun timeSignature(sign: Signs, time: TimeSignature, x0: Float, trebleBottom: Float, bassBottom: Float): Float {
        val top = time.numerator.toString()
        val bottom = time.denominator.toString()
        val digit = DIGIT_WIDTH * space
        val width = max(top.length, bottom.length) * digit
        for (staffBottom in floatArrayOf(trebleBottom, bassBottom)) {
            digits(sign, top, x0 + (width - top.length * digit) / 2, staffBottom - 3 * space)   // the upper half of the staff
            digits(sign, bottom, x0 + (width - bottom.length * digit) / 2, staffBottom - space) // the lower half
        }
        return x0 + width + AFTER_TIME * space
    }

    private fun digits(sign: Signs, number: String, x0: Float, baseline: Float) {
        number.forEachIndexed { i, c -> sign.add(Sign.DIGIT + (c - '0'), x0 + i * DIGIT_WIDTH * space, baseline) }
    }

    private fun makeSystem(s: Int): ScoreSystem {
        val first = s * barsPerSystem
        val count = min(barsPerSystem, barCount - first)
        val row = s % systemsPerPage
        val top = trebleTop[s]
        val bassTop = top + m.staffHeight + m.staveGap
        val lastOnPage = row == systemsPerPage - 1 || s == systemCount - 1
        val sign = signs[s]
        return ScoreSystem(
            index = s,
            page = s / systemsPerPage,
            slot = (s / systemsPerPage) % m.pages.coerceAtLeast(1),
            trebleTop = top,
            bassTop = bassTop,
            staffHeight = m.staffHeight,
            left = m.marginX,
            right = barRight[first + count - 1],
            firstBar = first,
            barCount = count,
            firstNote = if (noteEnd[s] > 0) noteFrom[s] else 0,
            noteEnd = noteEnd[s],
            bandTop = if (row == 0) 0f else trebleTop[s - 1] + 2 * m.staffHeight + m.staveGap,
            bandBottom = if (lastOnPage) m.pageHeight else trebleTop[s + 1],
            signKind = sign.kinds(),
            signX = sign.xs(),
            signY = sign.ys(),
            final = first + count == barCount,
            bars = scoreBars,
        )
    }

    /**
     * Each system's notes: from its first to one past its last (0 until 0 when it has none).
     * Systems follow each other in time, so their notes are runs of the start-sorted list.
     */
    private fun noteRanges() {
        for (i in 0 until n) {
            val s = system[i]
            if (s < 0) continue
            if (noteEnd[s] == 0) noteFrom[s] = i
            noteEnd[s] = i + 1
        }
    }

    // --- Notes -------------------------------------------------------------------------------

    /** Each note's bar, place in it, spelling, staff position and value. */
    private fun placeNotes() {
        val accidentals = BarAccidentals()
        var stateBar = -1
        var stateKey = Int.MIN_VALUE
        val starts = notes.startMicros
        val ends = notes.endMicros
        for (i in 0 until n) {
            val key = keys[i]
            if (key == KeyMap.UNPLAYABLE) continue
            val tick = tempo.microsToTicks(starts[i])
            val b = barIndex(tick)
            val s = b / barsPerSystem
            val sharps = sharpsAt(tick)
            if (b != stateBar || sharps != stateKey) {
                accidentals.reset(sharps)
                stateBar = b
                stateKey = sharps
            }
            val onTreble = key >= StaffPitch.MIDDLE_C
            val step = Spelling.step(key, sharps)
            val pos = StaffPitch.position(step, onTreble)
            val bottom = trebleTop[s] + m.staffHeight + if (onTreble) 0f else m.staveGap + m.staffHeight
            system[i] = s
            bar[i] = b
            startTick[i] = tick
            treble[i] = onTreble
            position[i] = pos
            accidental[i] = accidentals.accidental(onTreble, step, Spelling.alteration(key, sharps)).toByte()
            y[i] = bottom - pos * half
            ledgers[i] = StaffPitch.ledgerLines(pos).toByte()
            val fraction = ((tick - barTick[b]).toDouble() / max(1L, barEndTick[b] - barTick[b])).coerceIn(0.0, 1.0)
            x[i] = contentLeft[b] + (fraction * (contentRight[b] - contentLeft[b])).toFloat()
            if (quantized) {
                val value = Quantize.value(tempo.microsToTicks(ends[i]) - tick, ppq)
                head[i] = (if (value.whole) Head.WHOLE else if (value.hollow) Head.HALF else Head.BLACK).toByte()
                valueFlags[i] = value.flags.toByte()
                dotted[i] = value.dotted
            } else {
                head[i] = Head.BLACK.toByte()
            }
        }
    }

    /** Chords: seconds moved aside, accidentals stacked, stems shared, dots and duration lines placed. */
    private fun chords() {
        val chord = IntArray(CHORD_LIMIT)
        var i = 0
        while (i < n) {
            if (system[i] < 0) {
                i++
                continue
            }
            var end = i + 1
            while (end < n && sameChord(i, end)) end++
            for (onTreble in booleanArrayOf(true, false)) {
                var count = 0
                for (j in i until end) if (system[j] >= 0 && treble[j] == onTreble && count < CHORD_LIMIT) chord[count++] = j
                if (count > 0) staffChord(chord, count)
            }
            i = end
        }
        for (j in 0 until n) {
            if (system[j] < 0) continue
            if (dotted[j]) {
                dotX[j] = x[j] + headWidth(j) + DOT_GAP * space
                dotY[j] = if (Math.floorMod(position[j], 2) == 0) y[j] - half else y[j]   // a dot on a line moves up into the space
            }
            if (!quantized) {
                val last = system[j] * barsPerSystem + min(barsPerSystem, barCount - system[j] * barsPerSystem) - 1
                val endBar = scoreBars.barAt(notes.endMicros[j]).coerceIn(bar[j], last)
                val endX = scoreBars.xAt(endBar, notes.endMicros[j])
                if (endX > x[j] + headWidth(j)) durationEnd[j] = endX
            }
        }
    }

    /** Whether note [j] belongs to the chord note [i] starts (same bar; same tick, or within [ScoreLayoutEngine.CHORD_MICROS] when performed). */
    private fun sameChord(i: Int, j: Int): Boolean {
        if (system[j] < 0) return notes.startMicros[j] - notes.startMicros[i] <= ScoreLayoutEngine.CHORD_MICROS
        if (bar[j] != bar[i]) return false
        return if (quantized) startTick[j] == startTick[i] else notes.startMicros[j] - notes.startMicros[i] <= ScoreLayoutEngine.CHORD_MICROS
    }

    /** One chord on one staff: [chord]'s first [count] entries. */
    private fun staffChord(chord: IntArray, count: Int) {
        sortByPosition(chord, count)
        // Seconds: walking up, a head a step (or less) above an unmoved one moves right; the next one stays.
        val base = FloatArray(count) { x[chord[it]] }
        for (k in 1 until count) {
            val below = chord[k - 1]
            val here = chord[k]
            if (!moved[below] && keys[here] != keys[below] && position[here] - position[below] <= 1) {
                moved[here] = true
                x[here] += headWidth(below)
            }
        }
        accidentals(chord, count, base)
        if (quantized) stems(chord, count, base)
    }

    /** Accidentals stacked to the left of the chord, top down, each in the first column where it clears the others. */
    private fun accidentals(chord: IntArray, count: Int, base: FloatArray) {
        var left = Float.MAX_VALUE
        for (k in 0 until count) left = min(left, base[k])
        val column = IntArray(count) { -1 }
        val gap = ACCIDENTAL_GAP * space
        val columnWidth = SHARP_WIDTH * space + gap
        for (k in count - 1 downTo 0) {
            val i = chord[k]
            val kind = accidental[i].toInt()
            if (kind == Accidental.NONE) continue
            var c = 0
            while ((k + 1 until count).any { column[it] == c && position[chord[it]] - position[i] < ACCIDENTAL_CLEARANCE }) c++
            column[k] = c
            accidentalX[i] = left - gap - c * columnWidth - accidentalWidth(kind) * space
        }
    }

    /**
     * Stems for the chord's value groups: one stem per value (hollow or black, flags, dot). A lone
     * group points away from its farthest head (up when that is below the middle line); with two
     * or more, the group holding the highest head points up and the others down.
     */
    private fun stems(chord: IntArray, count: Int, base: FloatArray) {
        val groupOf = IntArray(count) { -1 }
        val groupKeys = IntArray(count)
        var groups = 0
        for (k in 0 until count) {
            val i = chord[k]
            if (head[i].toInt() == Head.WHOLE) continue
            val value = head[i] * 16 + valueFlags[i] * 2 + if (dotted[i]) 1 else 0
            var g = (0 until groups).firstOrNull { groupKeys[it] == value } ?: -1
            if (g < 0) {
                g = groups++
                groupKeys[g] = value
            }
            groupOf[k] = g
        }
        if (groups == 0) return
        val highestGroup = (count - 1 downTo 0).first { groupOf[it] >= 0 }.let { groupOf[it] }
        for (g in 0 until groups) {
            var low = -1
            var high = -1
            var anyMoved = false
            var left = Float.MAX_VALUE
            for (k in 0 until count) {
                if (groupOf[k] != g) continue
                if (low < 0) low = k
                high = k
                anyMoved = anyMoved || moved[chord[k]]
                left = min(left, base[k])
            }
            val lowNote = chord[low]
            val highNote = chord[high]
            val up = if (groups == 1) 4 - position[lowNote] > position[highNote] - 4 else g == highestGroup
            val staffBottom = y[lowNote] + position[lowNote] * half
            val middle = staffBottom - 2 * space
            val owner = if (up) lowNote else highNote
            val width = headWidth(owner)
            stemX[owner] = if (up || anyMoved) left + width - stemWidth else left
            stemUp[owner] = up
            if (up) {
                stemFrom[owner] = y[lowNote] - STEM_ATTACH * space
                stemTo[owner] = min(y[highNote] - ScoreLayoutEngine.STEM_SPACES * space, middle)
            } else {
                stemFrom[owner] = y[highNote] + STEM_ATTACH * space
                stemTo[owner] = max(y[lowNote] + ScoreLayoutEngine.STEM_SPACES * space, middle)
            }
            flags[owner] = valueFlags[owner]
        }
    }

    private fun sortByPosition(chord: IntArray, count: Int) {
        for (k in 1 until count) {
            val index = chord[k]
            var j = k - 1
            while (j >= 0 && (position[chord[j]] > position[index] || (position[chord[j]] == position[index] && keys[chord[j]] > keys[index]))) {
                chord[j + 1] = chord[j]
                j--
            }
            chord[j + 1] = index
        }
    }

    private fun headWidth(i: Int): Float =
        if (head[i].toInt() == Head.WHOLE) m.headWidth * ScoreLayout.WHOLE_TO_BLACK else m.headWidth

    // --- Lookups ------------------------------------------------------------------------------

    /** The bar holding [tick]: the last starting at or before it. */
    private fun barIndex(tick: Long): Int {
        var lo = 0
        var hi = barCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (barTick[mid] <= tick) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The metre in force at [tick]: 4/4 before the first signature. */
    private fun timeAt(tick: Long): TimeSignature = times.lastOrNull { it.tick <= tick } ?: TimeSignature.Common

    /** The key in force at [tick], in sharps (negative: flats); 0 before any key signature. */
    private fun sharpsAt(tick: Long): Int {
        var lo = 0
        var hi = keyTicks.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keyTicks[mid] <= tick) lo = mid + 1 else hi = mid
        }
        return if (lo == 0) 0 else keySharps[lo - 1]
    }

    /** A system's signs as they are added. */
    private class Signs {
        private var kinds = IntArray(32)
        private var xs = FloatArray(32)
        private var ys = FloatArray(32)
        private var size = 0

        fun add(kind: Int, x: Float, y: Float) {
            if (size == kinds.size) {
                kinds = kinds.copyOf(size * 2)
                xs = xs.copyOf(size * 2)
                ys = ys.copyOf(size * 2)
            }
            kinds[size] = kind
            xs[size] = x
            ys[size] = y
            size++
        }

        fun kinds(): IntArray = kinds.copyOf(size)
        fun xs(): FloatArray = xs.copyOf(size)
        fun ys(): FloatArray = ys.copyOf(size)
    }

    private companion object {
        /** More heads than this in one chord on one staff are left as they are. */
        const val CHORD_LIMIT = 48

        // Horizontal spacing, in staff spaces.
        const val CLEF_INSET = 0.5f
        const val AFTER_CLEF = 0.8f
        const val KEY_STEP_GAP = 0.12f
        const val AFTER_KEY = 0.6f
        const val AFTER_TIME = 0.8f
        const val BEFORE_CHANGE = 0.6f

        /** Room at a bar's start for its first note's accidental. */
        const val BAR_LEFT_PAD = 1.6f
        const val ACCIDENTAL_GAP = 0.2f
        const val DOT_GAP = 0.35f

        /** Where a stem meets its head: this far from the head's centre line (Bravura's anchor). */
        const val STEM_ATTACH = 0.168f

        /** Accidentals this many steps apart or more don't collide (they are about three spaces tall). */
        const val ACCIDENTAL_CLEARANCE = 6

        // Bravura's advance widths, in spaces.
        const val SHARP_WIDTH = 0.996f
        const val FLAT_WIDTH = 0.904f
        const val NATURAL_WIDTH = 0.672f
        const val DIGIT_WIDTH = 1.8f

        /** Key-signature positions on the treble staff (steps above E4), in the order they are added; the bass is a third lower. */
        val TREBLE_SHARPS = intArrayOf(8, 5, 9, 6, 3, 7, 4)
        val TREBLE_FLATS = intArrayOf(4, 7, 3, 6, 2, 5, 1)

        fun signWidth(kind: Int): Float = when (kind) {
            Sign.SHARP -> SHARP_WIDTH
            Sign.FLAT -> FLAT_WIDTH
            else -> NATURAL_WIDTH
        }

        fun accidentalWidth(kind: Int): Float = when (kind) {
            Accidental.SHARP -> SHARP_WIDTH
            Accidental.FLAT -> FLAT_WIDTH
            else -> NATURAL_WIDTH
        }
    }
}
