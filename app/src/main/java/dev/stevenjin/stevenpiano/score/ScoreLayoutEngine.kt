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
 * Lays a piece out as a paged score (DESIGN.md › v1.2 › Score, › v1.3 › Score fidelity): systems of
 * [ScoreMetrics.barsPerSystem] bars stacked [ScoreMetrics.systemsPerPage] to a page, every bar of a
 * system as wide as the others, each system opening with its clefs and the key signature in force,
 * the time signature at the first system and wherever the metre changes (a key change mid-system
 * draws the new key there). Within a bar a note sits at its place in beats, which is where the
 * cursor passes as it sounds.
 *
 * Notes are spelled in the key ([Spelling]) with one accidental per pitch per bar, and placed on the
 * treble staff from middle C up, on the bass staff below. A file on a sixteenth grid ([Quantize]) is
 * engraved: each note is written from its onset to its end on the grid, split at bar lines and into
 * values one note can write, the pieces joined by ties ([Ties]; the tied heads carry no accidental);
 * hollow whole and half heads, black heads with stems 3.5 spaces long (up below the middle line; a
 * chord's farthest head decides, and two values struck together on one staff stem apart, the higher
 * up), flags, dots; flagged notes within a beat beamed ([Beams]); and each staff's silences of a
 * sixteenth or more written as rests ([Rests]). A performed file keeps black heads with a hairline for
 * each note's length. Heads a second apart move aside, accidentals in a chord stack leftwards so they
 * don't collide. No tuplets, grace notes or voices within a hand.
 *
 * Pure (no Android, no Compose): it runs off the main thread and in the JVM tests.
 */
object ScoreLayoutEngine {
    /** In a performance, notes starting within this of a chord's first note are the chord (performed chords are not exact). */
    const val CHORD_MICROS = 30_000L

    /** Stem length from the head nearest its end, in staff spaces. */
    const val STEM_SPACES = 3.5f

    /** A written note is cut after this many heads, its own and the tied ones (a note held for bars on end). */
    const val MAX_HEADS_PER_NOTE = 64

    /** Tied heads in one piece at most: twice its notes, and never fewer than this. */
    const val MIN_TIED_BUDGET = 4_096

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

/**
 * One layout's working state. Heads are indexed like the notes for each note's own (first) head;
 * a sequenced note's tied heads follow them, from index [n], in onset order.
 */
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

    /** A sixteenth in ticks: the grid written notes and rests are counted on. */
    private val step = ppq / 4.0

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
    private val barCompound = BooleanArray(barCount) { Beams.compound(metre[it]) }
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
    private val tiedFrom = IntArray(systemCount)
    private val tiedEnd = IntArray(systemCount)

    // Notes.
    private val n = notes.size
    private val quantized = Quantize.onGrid(notes.startMicros, tempo)
    private val written = WrittenNotes()

    // Heads: each note's own, then the tied ones.
    private val heads = n + written.tied
    private val system = IntArray(heads) { -1 }
    private val x = FloatArray(heads)
    private val y = FloatArray(heads)
    private val head = ByteArray(heads)
    private val treble = BooleanArray(heads)
    private val ledgers = ByteArray(heads)
    private val accidental = ByteArray(heads)
    private val accidentalX = FloatArray(heads) { Float.NaN }
    private val stemX = FloatArray(heads) { Float.NaN }
    private val stemFrom = FloatArray(heads)
    private val stemTo = FloatArray(heads)
    private val stemUp = BooleanArray(heads)
    private val flags = ByteArray(heads)
    private val dotted = BooleanArray(heads)
    private val dotX = FloatArray(heads) { Float.NaN }
    private val dotY = FloatArray(heads)
    private val moved = BooleanArray(heads)
    private val durationEnd = FloatArray(heads) { Float.NaN }
    private val position = IntArray(heads)
    private val headKey = IntArray(heads)

    /** The tick a head is struck at, as chords are grouped: a note's own (exact), a tied head's (on the grid). */
    private val startTick = LongArray(heads)
    private val bar = IntArray(heads)
    private val valueFlags = ByteArray(heads)

    // Written values (sequenced files): where each head's written value starts and ends, on the grid.
    private val writtenStart = LongArray(heads)
    private val writtenEnd = LongArray(heads)

    /** Heads in onset order (playable notes' and tied ones merged). */
    private val order = IntArray(heads)
    private var orderSize = 0

    // Stems (sequenced files): each head's stem owner, and for each owner its value group's lowest and
    // highest heads, the chord's column, whether a second moved the stem between two columns, and the
    // sum of its heads' staff positions.
    private val ownerOf = IntArray(heads) { -1 }
    private val stemLow = IntArray(heads)
    private val stemHigh = IntArray(heads)
    private val stemLeft = FloatArray(heads)
    private val stemShifted = BooleanArray(heads)
    private val positionSum = IntArray(heads)
    private val groupHeads = IntArray(heads)

    // Onsets: one chord on one staff, in time order, with the stem owner of its one flagged value (-1: none).
    private var onsets = 0
    private val onsetHead = IntArray(heads)
    private val onsetOwner = IntArray(heads)

    /** A rest comes just before this head's onset on its staff, in its bar. */
    private val restBefore = BooleanArray(heads)

    private val beams = BeamSink()
    private val rests = RestSink()
    private val ties = TieSink()

    fun run(): ScoreLayout {
        for (s in 0 until systemCount) layoutSystem(s)
        placeNotes()
        placeTied()
        onsetOrder()
        chords()
        rests()
        beam()
        tieArcs()
        headRanges()
        return ScoreLayout(
            metrics = m,
            quantized = quantized,
            bars = scoreBars,
            systems = List(systemCount) { makeSystem(it) },
            noteCount = n,
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
            tiedNote = written.tiedNote,
            tiedStartMicros = LongArray(written.tied) { tempo.tickToMicros(written.tiedTick[it]) },
            tiedFrom = written.tiedFrom,
            tiedByNote = written.tiedByNote,
            beams = beams.build(systemCount),
            rests = rests.build(systemCount),
            ties = ties.build(systemCount),
        )
    }

    /**
     * Sequenced files: each note written on the sixteenth grid from its onset to its end, in the bar
     * its onset is in, and split into pieces ([Ties.segments]): the first piece is the note's own
     * head, the others its tied heads, numbered from [n] in onset order. A performance has none, and
     * its notes keep their exact ticks and bars.
     */
    private inner class WrittenNotes {
        /** Each note's exact tick, written onset (on the grid) and bar, and its first piece's length in sixteenths. */
        val tick = LongArray(n)
        val start = LongArray(n)
        val noteBar = IntArray(n)
        val firstLength = IntArray(n)

        /** Note i's tied heads are [tiedByNote] from tiedFrom[i] until tiedFrom[i + 1], in time order. */
        val tiedFrom = IntArray(n + 1)
        val tiedByNote: IntArray
        val tied: Int

        /** By tied head (head n + r, in onset order): its onset, its length in sixteenths, its note. */
        val tiedTick: LongArray
        val tiedLength: IntArray
        val tiedNote: IntArray

        init {
            var count = 0
            var ticks = LongArray(0)
            var lengths = IntArray(0)
            var owners = IntArray(0)
            val pieceTick = LongArray(ScoreLayoutEngine.MAX_HEADS_PER_NOTE)
            val pieceLength = IntArray(ScoreLayoutEngine.MAX_HEADS_PER_NOTE)
            val budget = max(ScoreLayoutEngine.MIN_TIED_BUDGET, 2 * n)
            for (i in 0 until n) {
                tiedFrom[i] = count
                if (keys[i] == KeyMap.UNPLAYABLE) continue
                val exact = tempo.microsToTicks(notes.startMicros[i])
                tick[i] = exact
                if (!quantized) {
                    start[i] = exact
                    noteBar[i] = barIndex(exact)
                    continue
                }
                // On the grid: a note a hair early is the next bar's downbeat, and every note lasts a sixteenth at least.
                val first = Math.round(exact / step)
                val last = max(first + 1, Math.round(tempo.microsToTicks(notes.endMicros[i]) / step))
                val from = Math.round(first * step)
                val b = barIndex(from)
                start[i] = from
                noteBar[i] = b
                val room = min(ScoreLayoutEngine.MAX_HEADS_PER_NOTE, 1 + budget - count)
                val pieces = Ties.segments(from, Math.round(last * step), barTick, barEndTick, barCompound, b, step, room, pieceTick, pieceLength)
                firstLength[i] = pieceLength[0]
                if (pieces > 1) {
                    if (count + pieces > ticks.size) {
                        val grown = max(64, 2 * (count + pieces))
                        ticks = ticks.copyOf(grown)
                        lengths = lengths.copyOf(grown)
                        owners = owners.copyOf(grown)
                    }
                    for (k in 1 until pieces) {
                        ticks[count] = pieceTick[k]
                        lengths[count] = pieceLength[k]
                        owners[count] = i
                        count++
                    }
                }
            }
            tiedFrom[n] = count
            tied = count
            val byTick = sortedByKey(ticks, count)
            tiedTick = LongArray(count) { ticks[byTick[it]] }
            tiedLength = IntArray(count) { lengths[byTick[it]] }
            tiedNote = IntArray(count) { owners[byTick[it]] }
            tiedByNote = IntArray(count)
            for (r in 0 until count) tiedByNote[byTick[r]] = n + r
        }
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
            firstTied = if (tiedEnd[s] > 0) tiedFrom[s] else 0,
            tiedEnd = tiedEnd[s],
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
     * Each system's heads: its notes' own heads from the first to one past the last (0 until 0 when it
     * has none), and its tied heads likewise. Systems follow each other in time, so both are runs.
     */
    private fun headRanges() {
        for (i in 0 until n) {
            val s = system[i]
            if (s < 0) continue
            if (noteEnd[s] == 0) noteFrom[s] = i
            noteEnd[s] = i + 1
        }
        for (h in n until heads) {
            val s = system[h]
            if (tiedEnd[s] == 0) tiedFrom[s] = h
            tiedEnd[s] = h + 1
        }
    }

    // --- Notes -------------------------------------------------------------------------------

    /** Each note's own head: its bar, place in it, spelling, staff position and (sequenced) written value. */
    private fun placeNotes() {
        val accidentals = BarAccidentals()
        var stateBar = -1
        var stateKey = Int.MIN_VALUE
        for (i in 0 until n) {
            val key = keys[i]
            if (key == KeyMap.UNPLAYABLE) continue
            val tick = written.tick[i]
            val b = written.noteBar[i]
            val s = b / barsPerSystem
            val sharps = sharpsAt(tick)
            if (b != stateBar || sharps != stateKey) {
                accidentals.reset(sharps)
                stateBar = b
                stateKey = sharps
            }
            val onTreble = key >= StaffPitch.MIDDLE_C
            val letter = Spelling.step(key, sharps)
            val pos = StaffPitch.position(letter, onTreble)
            system[i] = s
            bar[i] = b
            startTick[i] = tick
            treble[i] = onTreble
            position[i] = pos
            headKey[i] = key
            accidental[i] = accidentals.accidental(onTreble, letter, Spelling.alteration(key, sharps)).toByte()
            y[i] = staffBottom(s, onTreble) - pos * half
            ledgers[i] = StaffPitch.ledgerLines(pos).toByte()
            x[i] = xIn(b, tick)
            if (quantized) {
                writeValue(i, written.firstLength[i])
                writtenStart[i] = written.start[i]
                writtenEnd[i] = written.start[i] + Math.round(written.firstLength[i] * step)
            } else {
                head[i] = Head.BLACK.toByte()
            }
        }
    }

    /**
     * The tied heads: each at its onset in its bar, on its note's line or space (spelled as the note
     * was, even past a change of key), with its piece's value and no accidental: the note is held, not
     * struck, and the bar's accidentals are left as they were.
     */
    private fun placeTied() {
        for (r in 0 until written.tied) {
            val h = n + r
            val i = written.tiedNote[r]
            val tick = written.tiedTick[r]
            val b = barIndex(tick)
            val s = b / barsPerSystem
            system[h] = s
            bar[h] = b
            startTick[h] = tick
            treble[h] = treble[i]
            position[h] = position[i]
            headKey[h] = headKey[i]
            y[h] = staffBottom(s, treble[i]) - position[i] * half
            ledgers[h] = ledgers[i]
            x[h] = xIn(b, tick)
            writeValue(h, written.tiedLength[r])
            writtenStart[h] = tick
            writtenEnd[h] = tick + Math.round(written.tiedLength[r] * step)
        }
    }

    /** Head [h]'s written value, [sixteenths] long: its head, flags (before beaming) and dot. */
    private fun writeValue(h: Int, sixteenths: Int) {
        val value = Ties.value(sixteenths, ppq)
        head[h] = (if (value.whole) Head.WHOLE else if (value.hollow) Head.HALF else Head.BLACK).toByte()
        valueFlags[h] = value.flags.toByte()
        dotted[h] = value.dotted
    }

    /** The y of the bottom line of system [s]'s treble or bass staff. */
    private fun staffBottom(s: Int, onTreble: Boolean): Float =
        trebleTop[s] + m.staffHeight + if (onTreble) 0f else m.staveGap + m.staffHeight

    /** The page x of [tick] in bar [b], held to the bar: where the cursor is at that moment. */
    private fun xIn(b: Int, tick: Long): Float {
        val fraction = ((tick - barTick[b]).toDouble() / max(1L, barEndTick[b] - barTick[b])).coerceIn(0.0, 1.0)
        return contentLeft[b] + (fraction * (contentRight[b] - contentLeft[b])).toFloat()
    }

    /** Playable notes' heads and tied heads, merged by the tick they are struck at (a note before a tied head at one tick). */
    private fun onsetOrder() {
        var r = 0
        for (i in 0 until n) {
            if (system[i] < 0) continue
            while (r < written.tied && startTick[n + r] < startTick[i]) order[orderSize++] = n + r++
            order[orderSize++] = i
        }
        while (r < written.tied) order[orderSize++] = n + r++
    }

    /** Chords: seconds moved aside, accidentals stacked, stems shared, dots and duration lines placed. */
    private fun chords() {
        val chord = IntArray(CHORD_LIMIT)
        var a = 0
        while (a < orderSize) {
            val i = order[a]
            var end = a + 1
            while (end < orderSize && sameChord(i, order[end])) end++
            for (onTreble in TREBLE_THEN_BASS) {
                var count = 0
                for (k in a until end) {
                    val j = order[k]
                    if (treble[j] == onTreble && count < CHORD_LIMIT) chord[count++] = j
                }
                if (count > 0) staffChord(chord, count)
            }
            a = end
        }
        for (j in 0 until heads) {
            if (system[j] < 0) continue
            if (dotted[j]) {
                dotX[j] = x[j] + headWidth(j) + DOT_GAP * space
                dotY[j] = if (Math.floorMod(position[j], 2) == 0) y[j] - half else y[j]   // a dot on a line moves up into the space
            }
            if (!quantized && j < n) {
                val last = system[j] * barsPerSystem + min(barsPerSystem, barCount - system[j] * barsPerSystem) - 1
                val endBar = scoreBars.barAt(notes.endMicros[j]).coerceIn(bar[j], last)
                val endX = scoreBars.xAt(endBar, notes.endMicros[j])
                if (endX > x[j] + headWidth(j)) durationEnd[j] = endX
            }
        }
    }

    /** Whether head [j] (playable, after [i] in onset order) belongs to the chord [i] starts (same bar; same tick, or within [ScoreLayoutEngine.CHORD_MICROS] when performed). */
    private fun sameChord(i: Int, j: Int): Boolean {
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
            if (!moved[below] && headKey[here] != headKey[below] && position[here] - position[below] <= 1) {
                moved[here] = true
                x[here] += headWidth(below)
            }
        }
        accidentals(chord, count, base)
        if (quantized) {
            onsetOwner[onsets] = stems(chord, count, base)
            onsetHead[onsets] = chord[0]
            onsets++
        }
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
     * or more, the group holding the highest head points up and the others down. Returns the stem's
     * owner when the chord is one flagged value (it may be beamed), else -1.
     */
    private fun stems(chord: IntArray, count: Int, base: FloatArray): Int {
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
        if (groups == 0) return -1
        val highestGroup = (count - 1 downTo 0).first { groupOf[it] >= 0 }.let { groupOf[it] }
        for (g in 0 until groups) {
            var low = -1
            var high = -1
            var anyMoved = false
            var left = Float.MAX_VALUE
            var sum = 0
            var heads = 0
            for (k in 0 until count) {
                if (groupOf[k] != g) continue
                if (low < 0) low = k
                high = k
                anyMoved = anyMoved || moved[chord[k]]
                left = min(left, base[k])
                sum += position[chord[k]]
                heads++
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
            stemLow[owner] = lowNote
            stemHigh[owner] = highNote
            stemLeft[owner] = left
            stemShifted[owner] = anyMoved
            positionSum[owner] = sum
            groupHeads[owner] = heads
            for (k in 0 until count) if (groupOf[k] == g) ownerOf[chord[k]] = owner
        }
        if (groups != 1) return -1
        val owner = ownerOf[chord[(0 until count).first { groupOf[it] == 0 }]]
        return if (valueFlags[owner] > 0) owner else -1
    }

    // --- Rests, beams and ties (sequenced files) ---------------------------------------------

    /**
     * Rests ([Rests]): on each staff of each bar, every silence of a sixteenth or more, from the bar's
     * start, between where all the written values so far have ended and the next onset, and to the
     * bar's end. A staff with nothing in a bar gets a whole rest centred in it. The onset after a rest
     * is marked: a rest ends a beam.
     */
    private fun rests() {
        if (!quantized) return
        val covered = LongArray(2)
        val silentAt = LongArray(2)
        val restStart = IntArray(MAX_RESTS_PER_SILENCE)
        val restLength = IntArray(MAX_RESTS_PER_SILENCE)
        var a = 0
        for (b in 0 until barCount) {
            covered[0] = barTick[b]
            covered[1] = barTick[b]
            silentAt[0] = -1L
            silentAt[1] = -1L
            while (a < orderSize && bar[order[a]] <= b) {
                val h = order[a++]
                val staff = if (treble[h]) 0 else 1
                if (writtenStart[h] - covered[staff] >= step / 2) {
                    restsIn(b, treble[h], covered[staff], writtenStart[h], restStart, restLength)
                    restBefore[h] = true
                    silentAt[staff] = writtenStart[h]
                } else if (writtenStart[h] == silentAt[staff]) {
                    restBefore[h] = true   // the rest of the chord after the rest
                }
                covered[staff] = max(covered[staff], writtenEnd[h])
            }
            for (staff in 0..1) {
                if (barEndTick[b] - covered[staff] >= step / 2) restsIn(b, staff == 0, covered[staff], barEndTick[b], restStart, restLength)
            }
        }
    }

    /** The rests writing the silence from [from] to [to] (ticks) on one staff of bar [b]. */
    private fun restsIn(b: Int, onTreble: Boolean, from: Long, to: Long, restStart: IntArray, restLength: IntArray) {
        val length = Math.round((barEndTick[b] - barTick[b]) / step).toInt()
        if (length <= 0) return
        val time = metre[b]
        val count = Rests.tile(
            Math.round((from - barTick[b]) / step).toInt(),
            Math.round((to - barTick[b]) / step).toInt(),
            length,
            Beams.beatSixteenths(time),
            Beams.compound(time),
            restStart,
            restLength,
        )
        val s = b / barsPerSystem
        val top = staffBottom(s, onTreble) - m.staffHeight
        for (k in 0 until count) {
            val wholeBar = restStart[k] == 0 && restLength[k] == length
            val value = if (wholeBar) Rests.WHOLE else restLength[k]
            val x = if (wholeBar) {
                (contentLeft[b] + barRight[b]) / 2 - WHOLE_REST_WIDTH * space / 2
            } else {
                xIn(b, barTick[b] + Math.round(restStart[k] * step))
            }
            // A whole rest hangs from the fourth line; the others sit on or centre on the middle line.
            val y = if (value == Rests.WHOLE) top + space else top + 2 * space
            rests.add(s, x, y, value, wholeBar, onTreble, b)
        }
    }

    /** Beams each staff's flagged notes within a beat ([Beams]): shared stem direction, one straight beam, no flags. */
    private fun beam() {
        if (!quantized || onsets == 0) return
        val index = IntArray(onsets)
        val onsetBar = IntArray(onsets)
        val beat = IntArray(onsets)
        val offset = IntArray(onsets)
        val beamable = BooleanArray(onsets)
        val rest = BooleanArray(onsets)
        val groupOf = IntArray(onsets)
        for (onTreble in TREBLE_THEN_BASS) {
            var count = 0
            for (u in 0 until onsets) {
                val h = onsetHead[u]
                if (treble[h] != onTreble) continue
                val b = bar[h]
                val sixteenths = Math.round((writtenStart[h] - barTick[b]) / step).toInt().coerceAtLeast(0)
                val beatLength = Beams.beatSixteenths(metre[b])
                index[count] = u
                onsetBar[count] = b
                beat[count] = sixteenths / beatLength
                offset[count] = sixteenths % beatLength
                beamable[count] = onsetOwner[u] >= 0
                rest[count] = restBefore[h]
                count++
            }
            Beams.group(onsetBar, beat, beamable, rest, count, groupOf)
            var k = 0
            while (k < count) {
                if (groupOf[k] < 0) {
                    k++
                    continue
                }
                var end = k + 1
                while (end < count && groupOf[end] == groupOf[k]) end++
                beamGroup(index, offset, k, end)
                k = end
            }
        }
    }

    // One group's working arrays, grown as needed.
    private var groupX = FloatArray(16)
    private var groupFar = FloatArray(16)
    private var groupMiddle = FloatArray(16)
    private var groupTip = FloatArray(16)
    private var beamGroups = 0

    /** Beams the onsets [index] from..until (one group, in order): stems, the beam line, secondary beams and stubs. */
    private fun beamGroup(index: IntArray, offset: IntArray, from: Int, until: Int) {
        val count = until - from
        if (groupX.size < count) {
            groupX = FloatArray(count)
            groupFar = FloatArray(count)
            groupMiddle = FloatArray(count)
            groupTip = FloatArray(count)
        }
        var sum = 0
        var heads = 0
        for (k in from until until) {
            val o = onsetOwner[index[k]]
            sum += positionSum[o]
            heads += groupHeads[o]
        }
        val up = Beams.stemsUp(sum, heads)
        for (j in 0 until count) {
            val o = onsetOwner[index[from + j]]
            groupX[j] = if (up || stemShifted[o]) stemLeft[o] + headWidth(o) - stemWidth else stemLeft[o]
            groupFar[j] = if (up) y[stemHigh[o]] else y[stemLow[o]]
            groupMiddle[j] = y[o] + position[o] * half - 2 * space
        }
        Beams.line(groupX, groupFar, groupMiddle, count, up, space, ScoreLayoutEngine.STEM_SPACES * space, groupTip)
        for (j in 0 until count) {
            val o = onsetOwner[index[from + j]]
            stemX[o] = groupX[j]
            stemUp[o] = up
            stemFrom[o] = if (up) y[stemLow[o]] - STEM_ATTACH * space else y[stemHigh[o]] + STEM_ATTACH * space
            stemTo[o] = groupTip[j]
            flags[o] = 0
        }
        val s = system[onsetOwner[index[from]]]
        val last = count - 1
        val slope = if (groupX[last] > groupX[0]) (groupTip[last] - groupTip[0]) / (groupX[last] - groupX[0]) else 0f
        fun lineAt(x: Float) = groupTip[0] + slope * (x - groupX[0])
        val id = beamGroups++
        beams.add(s, groupX[0], groupTip[0], groupX[last] + stemWidth, lineAt(groupX[last] + stemWidth), up, 1, false, id)
        // Sixteenths: a second beam over each run of them; a lone one gets a stub into the group.
        val inward = if (up) Beams.SECONDARY_OFFSET * space else -Beams.SECONDARY_OFFSET * space
        var j = 0
        while (j < count) {
            if (valueFlags[onsetOwner[index[from + j]]] < 2) {
                j++
                continue
            }
            var end = j + 1
            while (end < count && valueFlags[onsetOwner[index[from + end]]] >= 2) end++
            val partial = end - j < 2
            val right = Beams.stubPointsRight(j, count, offset[from + j])
            val x1 = if (partial && !right) groupX[j] + stemWidth - m.headWidth else groupX[j]
            val x2 = when {
                !partial -> groupX[end - 1] + stemWidth
                right -> groupX[j] + m.headWidth
                else -> groupX[j] + stemWidth
            }
            beams.add(s, x1, lineAt(x1) + inward, x2, lineAt(x2) + inward, up, 2, partial, id)
            j = end
        }
    }

    /** Ties: an arc from each written piece of a note to the next, away from the stem; split in two across systems. */
    private fun tieArcs() {
        if (written.tied == 0) return
        for (i in 0 until n) {
            var from = i
            for (k in written.tiedFrom[i] until written.tiedFrom[i + 1]) {
                val to = written.tiedByNote[k]
                arc(from, to)
                from = to
            }
        }
    }

    /**
     * The tie from head [a] to head [b]: from the right edge of the first (its centre plus half a head)
     * to the left edge of the second, a little off the heads on the side away from the stem. Across a
     * system break, the first half runs to the end of [a]'s system (at least a space, past the bar line
     * when the head is the system's last) and the second comes in to [b].
     */
    private fun arc(a: Int, b: Int) {
        val above = bowsUp(a)
        val off = if (above) -TIE_OFFSET * space else TIE_OFFSET * space
        val from = x[a] + headWidth(a)
        if (system[a] == system[b]) {
            ties.add(system[a], from, y[a] + off, max(from, x[b]), y[b] + off, above, a, b)
        } else {
            val lastBar = min(barCount, (system[a] + 1) * barsPerSystem) - 1
            val end = max(barRight[lastBar] - TIE_CLEAR * space, from + TIE_MIN * space)
            ties.add(system[a], from, y[a] + off, end, y[a] + off, above, a, -1)
            ties.add(system[b], x[b] - TIE_LEAD * space, y[b] + off, x[b], y[b] + off, above, -1, b)
        }
    }

    /** A tie bows away from its head's stem; with no stem (a whole note), up from the middle line and above, else down. */
    private fun bowsUp(h: Int): Boolean {
        val owner = ownerOf[h]
        return if (owner >= 0) !stemUp[owner] else position[h] >= 4
    }

    private fun sortByPosition(chord: IntArray, count: Int) {
        for (k in 1 until count) {
            val index = chord[k]
            var j = k - 1
            while (j >= 0 && (position[chord[j]] > position[index] || (position[chord[j]] == position[index] && headKey[chord[j]] > headKey[index]))) {
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

        val TREBLE_THEN_BASS = booleanArrayOf(true, false)

        /** One silence is written with at most this many rests (a metre of hundreds of beats is not music). */
        const val MAX_RESTS_PER_SILENCE = 64

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

        // Ties, in staff spaces: their ends sit this far off the heads' centre lines, a half tie stops
        // this short of its system's last bar line (and is at least TIE_MIN long), and the other half
        // comes in from this far before its head.
        const val TIE_OFFSET = 0.35f
        const val TIE_CLEAR = 0.25f
        const val TIE_LEAD = 1.5f
        const val TIE_MIN = 1f

        /** Accidentals this many steps apart or more don't collide (they are about three spaces tall). */
        const val ACCIDENTAL_CLEARANCE = 6

        // Bravura's advance widths, in spaces.
        const val SHARP_WIDTH = 0.996f
        const val FLAT_WIDTH = 0.904f
        const val NATURAL_WIDTH = 0.672f
        const val DIGIT_WIDTH = 1.8f
        const val WHOLE_REST_WIDTH = 1.128f

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

/** Beam segments as the engine finds them, sorted by system when built. */
private class BeamSink {
    private var system = IntArray(64)
    private var x1 = FloatArray(64)
    private var y1 = FloatArray(64)
    private var x2 = FloatArray(64)
    private var y2 = FloatArray(64)
    private var up = BooleanArray(64)
    private var level = ByteArray(64)
    private var stub = BooleanArray(64)
    private var group = IntArray(64)
    private var size = 0

    fun add(s: Int, ax: Float, ay: Float, bx: Float, by: Float, stemsUp: Boolean, beamLevel: Int, partial: Boolean, id: Int) {
        if (size == system.size) {
            val grown = size * 2
            system = system.copyOf(grown)
            x1 = x1.copyOf(grown)
            y1 = y1.copyOf(grown)
            x2 = x2.copyOf(grown)
            y2 = y2.copyOf(grown)
            up = up.copyOf(grown)
            level = level.copyOf(grown)
            stub = stub.copyOf(grown)
            group = group.copyOf(grown)
        }
        system[size] = s
        x1[size] = ax
        y1[size] = ay
        x2[size] = bx
        y2[size] = by
        up[size] = stemsUp
        level[size] = beamLevel.toByte()
        stub[size] = partial
        group[size] = id
        size++
    }

    fun build(systemCount: Int): ScoreBeams {
        val sorted = BySystem(system, size, systemCount)
        val o = sorted.order
        return ScoreBeams(
            system = IntArray(size) { system[o[it]] },
            x1 = FloatArray(size) { x1[o[it]] },
            y1 = FloatArray(size) { y1[o[it]] },
            x2 = FloatArray(size) { x2[o[it]] },
            y2 = FloatArray(size) { y2[o[it]] },
            up = BooleanArray(size) { up[o[it]] },
            level = ByteArray(size) { level[o[it]] },
            stub = BooleanArray(size) { stub[o[it]] },
            group = IntArray(size) { group[o[it]] },
            systemStart = sorted.start,
        )
    }
}

/** Rests as the engine finds them, sorted by system when built. */
private class RestSink {
    private var system = IntArray(64)
    private var x = FloatArray(64)
    private var y = FloatArray(64)
    private var value = ByteArray(64)
    private var wholeBar = BooleanArray(64)
    private var treble = BooleanArray(64)
    private var bar = IntArray(64)
    private var size = 0

    fun add(s: Int, atX: Float, atY: Float, sixteenths: Int, whole: Boolean, onTreble: Boolean, b: Int) {
        if (size == system.size) {
            val grown = size * 2
            system = system.copyOf(grown)
            x = x.copyOf(grown)
            y = y.copyOf(grown)
            value = value.copyOf(grown)
            wholeBar = wholeBar.copyOf(grown)
            treble = treble.copyOf(grown)
            bar = bar.copyOf(grown)
        }
        system[size] = s
        x[size] = atX
        y[size] = atY
        value[size] = sixteenths.toByte()
        wholeBar[size] = whole
        treble[size] = onTreble
        bar[size] = b
        size++
    }

    fun build(systemCount: Int): ScoreRests {
        val sorted = BySystem(system, size, systemCount)
        val o = sorted.order
        return ScoreRests(
            system = IntArray(size) { system[o[it]] },
            x = FloatArray(size) { x[o[it]] },
            y = FloatArray(size) { y[o[it]] },
            value = ByteArray(size) { value[o[it]] },
            wholeBar = BooleanArray(size) { wholeBar[o[it]] },
            treble = BooleanArray(size) { treble[o[it]] },
            bar = IntArray(size) { bar[o[it]] },
            systemStart = sorted.start,
        )
    }
}

/** Tie arcs as the engine finds them, sorted by system when built. */
private class TieSink {
    private var system = IntArray(64)
    private var x1 = FloatArray(64)
    private var y1 = FloatArray(64)
    private var x2 = FloatArray(64)
    private var y2 = FloatArray(64)
    private var above = BooleanArray(64)
    private var from = IntArray(64)
    private var to = IntArray(64)
    private var size = 0

    fun add(s: Int, ax: Float, ay: Float, bx: Float, by: Float, bowsUp: Boolean, a: Int, b: Int) {
        if (size == system.size) {
            val grown = size * 2
            system = system.copyOf(grown)
            x1 = x1.copyOf(grown)
            y1 = y1.copyOf(grown)
            x2 = x2.copyOf(grown)
            y2 = y2.copyOf(grown)
            above = above.copyOf(grown)
            from = from.copyOf(grown)
            to = to.copyOf(grown)
        }
        system[size] = s
        x1[size] = ax
        y1[size] = ay
        x2[size] = bx
        y2[size] = by
        above[size] = bowsUp
        from[size] = a
        to[size] = b
        size++
    }

    fun build(systemCount: Int): ScoreTies {
        val sorted = BySystem(system, size, systemCount)
        val o = sorted.order
        return ScoreTies(
            system = IntArray(size) { system[o[it]] },
            x1 = FloatArray(size) { x1[o[it]] },
            y1 = FloatArray(size) { y1[o[it]] },
            x2 = FloatArray(size) { x2[o[it]] },
            y2 = FloatArray(size) { y2[o[it]] },
            above = BooleanArray(size) { above[o[it]] },
            from = IntArray(size) { from[o[it]] },
            to = IntArray(size) { to[o[it]] },
            systemStart = sorted.start,
        )
    }
}

/** The indices 0 until [size], ordered by [key] (stable: equal keys keep their order). A bottom-up merge sort. */
private fun sortedByKey(key: LongArray, size: Int): IntArray {
    var a = IntArray(size) { it }
    if (size < 2) return a
    var b = IntArray(size)
    var width = 1
    while (width < size) {
        var lo = 0
        while (lo < size) {
            val mid = min(lo + width, size)
            val hi = min(lo + 2 * width, size)
            var i = lo
            var j = mid
            var k = lo
            while (i < mid && j < hi) b[k++] = if (key[a[j]] < key[a[i]]) a[j++] else a[i++]
            while (i < mid) b[k++] = a[i++]
            while (j < hi) b[k++] = a[j++]
            lo = hi
        }
        val t = a
        a = b
        b = t
        width *= 2
    }
    return a
}
