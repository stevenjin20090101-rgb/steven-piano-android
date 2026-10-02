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
import dev.stevenjin.stevenpiano.midi.NoteList
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The web panel's notes and score as binary display lists (BUILD_SPEC.md › v1.13 — M32): pure Kotlin, so the browser
 * is a plain interpreter of what the tablet laid out and the tests read every byte. Three formats, little-endian,
 * each opening with a u32 magic and a u16 version, every section 4-byte aligned so the page maps typed arrays on it:
 *
 * - **Notes, [NOTES_MAGIC] "SPNT"**: each note's start and end (ms) and its key as the piano plays it (transposed and
 *   folded, 255 when unplayable), the hands and fingers when shown, the chords' starts and names (as transposed).
 * - **Score index, [INDEX_MAGIC] "SPSI"**: one layout's geometry (CSS pixels) and each system's first moment.
 * - **Score page, [PAGE_MAGIC] "SPSP"**: one page's systems, its bars (each with nine (ms, x) points the cursor runs
 *   through), its heads (each head's drawing contiguous in the op stream, so the overlay replays it in the sounding
 *   colour), its ops in the order the tablet's painter draws them (`ScorePages`), and its strings. A page past
 *   [MAX_PAGE_BYTES] stops adding notes and says so ([PAGE_TRUNCATED]).
 *
 * The browser draws Bravura at four [ScoreStyle.LINE_GAP_DP] to the em and its text in the panel's own fonts, so the
 * widths that place text are [WebText]'s estimates (wide), and each text is drawn within the width kept for it.
 */
object ScoreDisplayList {
    const val NOTES_MAGIC = 0x544E5053   // "SPNT" read little-endian
    const val INDEX_MAGIC = 0x49535053   // "SPSI"
    const val PAGE_MAGIC = 0x50535053    // "SPSP"
    const val VERSION = 1

    /** Notes' flags. */
    const val NOTES_HANDS = 1
    const val NOTES_FINGERS = 2
    const val NOTES_CHORDS_CUT = 4

    /** The index's flags. */
    const val INDEX_ENGRAVED = 1
    const val INDEX_TWO_PAGES = 2

    /** A page's flag: notes were left out to keep it under [MAX_PAGE_BYTES]. */
    const val PAGE_TRUNCATED = 1

    /** A key the piano doesn't play (folding off). */
    const val NO_KEY = 255

    /** Chords in one notes answer, at most (more are cut, [NOTES_CHORDS_CUT]). */
    const val MAX_CHORDS = 20_000

    /** A page's body, at most. */
    const val MAX_PAGE_BYTES = 256 * 1024

    /** Header sizes in bytes. */
    const val NOTES_HEADER = 32
    const val INDEX_HEADER = 96
    const val PAGE_HEADER = 40

    /** Words per system, per bar, per head in a page; points a bar's cursor runs through. */
    const val SYSTEM_WORDS = 10
    const val BAR_POINTS = 9
    const val BAR_WORDS = 2 + 2 * BAR_POINTS
    const val HEAD_WORDS = 5

    /**
     * The ops: word 0 is `op | role << 8 | style << 16 | align << 24`, then its arguments (f32 unless said):
     * RECT x y w h; GLYPH codePoint(u32) x baseline; TEXT string(u32) x baseline maxWidth; QUAD four corners;
     * CURVE x1 y1 cx cy x2 y2 (a hairline's quadratic); CLIP top bottom (a system's band, until END); END.
     */
    const val OP_RECT = 1
    const val OP_GLYPH = 2
    const val OP_TEXT = 3
    const val OP_QUAD = 4
    const val OP_CURVE = 5
    const val OP_CLIP = 6
    const val OP_END = 7

    /** Colours by role: staff and bar lines, signs and marks, bar numbers, notes (and their rests, beams, ties). */
    const val ROLE_LINE = 0
    const val ROLE_GLYPH = 1
    const val ROLE_NUMBER = 2
    const val ROLE_NOTE = 3

    /** Text styles: Bravura at the staff's size, Bravura at the tempo mark's, bar numbers, chord names, fingering. */
    const val STYLE_MUSIC = 0
    const val STYLE_TEMPO_MUSIC = 1
    const val STYLE_NUMBER = 2
    const val STYLE_CHORD = 3
    const val STYLE_NUMERAL = 4

    /** A text's x is its left edge, or its centre. */
    const val ALIGN_LEFT = 0
    const val ALIGN_CENTRE = 1

    /**
     * The browser's text as the panel sets it (style.css's eyebrow and body sizes), estimated: per character a share
     * of the em that errs wide, so a name laid out here never runs into the next in any browser font.
     */
    object WebText : ScoreText {
        const val NUMBER_FONT = 11f
        const val NUMBER_LINE = 16f
        const val NUMBER_BASELINE = 12f
        const val CHORD_FONT = 15f
        const val CHORD_LINE = 20f
        const val CHORD_BASELINE = 15f
        const val NUMERAL_FONT = 11f

        override val tempoSpace: Float = NUMBER_FONT * ScoreStyle.TEMPO_NOTE_SCALE / 4

        override fun numberWidth(text: String): Float = estimate(text, NUMBER_FONT)

        override fun numberBaseline(text: String): Float = NUMBER_BASELINE

        override fun chordWidth(name: String): Float = estimate(name, CHORD_FONT)

        override fun chordHeight(name: String): Float = CHORD_LINE

        /** [text]'s width at [size] px, erring wide. */
        fun estimate(text: String, size: Float): Float {
            var ems = 0f
            for (c in text) {
                ems += when {
                    c.isDigit() -> 0.64f
                    c == ' ' -> 0.32f
                    c == '/' || c == '(' || c == ')' -> 0.42f
                    c.isUpperCase() -> 0.74f
                    c.isLowerCase() -> 0.62f
                    else -> 0.74f
                }
            }
            return ems * size
        }
    }

    /**
     * The metrics a browser's score panel [width] × [height] CSS pixels gets: density 1, Bravura's own widths (a
     * black head 1.18 spaces, the widest clef 2.74), the panel's line heights, a chord line when [chords] are shown.
     */
    fun metrics(width: Int, height: Int, chords: Boolean): ScoreMetrics {
        val space = ScoreStyle.LINE_GAP_DP
        val numberHeight = WebText.NUMBER_LINE
        val chordHeight = if (!chords) {
            0f
        } else {
            val numberTop = ScoreStyle.numberLift(space, 1f) + WebText.NUMBER_BASELINE
            WebText.CHORD_LINE + max(0f, numberTop - numberHeight) + ScoreStyle.CHORD_GAP_DP
        }
        return ScoreMetrics.fitting(
            width.toFloat(), height.toFloat(), 1f, HEAD_SPACES * space, CLEF_SPACES * space, numberHeight,
            numeralHeight = NUMERAL_CAP * WebText.NUMERAL_FONT, numeralWidth = NUMERAL_ADVANCE * WebText.NUMERAL_FONT,
            chordHeight = chordHeight,
        )
    }

    // ---- Notes ----------------------------------------------------------------------------------------------------

    /**
     * The notes of the piece playing as [rev] shows them: [notes] at [transpose] and [fold], the [hands] and [fingers]
     * when given (one per note), the [chords]' names as transposed (the first [MAX_CHORDS]).
     */
    fun notes(
        notes: NoteList,
        durationMicros: Long,
        rev: Int,
        transpose: Int,
        fold: Boolean,
        hands: ByteArray?,
        fingers: ByteArray?,
        chords: ChordTrack?,
    ): ByteArray {
        val n = notes.size
        val handsHere = hands?.takeIf { it.size == n }
        val fingersHere = fingers?.takeIf { it.size == n }
        val chordCount = min(chords?.size ?: 0, MAX_CHORDS)
        val names = Array(chordCount) { chords!!.name(it, transpose).toByteArray(Charsets.UTF_8) }
        val nameBytes = names.sumOf { it.size }
        var flags = 0
        if (handsHere != null) flags = flags or NOTES_HANDS
        if (fingersHere != null) flags = flags or NOTES_FINGERS
        if ((chords?.size ?: 0) > chordCount) flags = flags or NOTES_CHORDS_CUT
        val out = WireWriter(NOTES_HEADER + n * 11 + 12 + chordCount * 8 + nameBytes)
        out.u32(NOTES_MAGIC).u16(VERSION).u16(flags).u32(rev).u32(n).u32(chordCount).u32(ms(durationMicros)).u32(nameBytes).u32(0)
        for (i in 0 until n) out.u32(ms(notes.startMicros[i]))
        for (i in 0 until n) out.u32(ms(notes.endMicros[i]))
        for (i in 0 until n) {
            val key = KeyMap.map(notes.note(i), transpose, fold)
            out.u8(if (key == KeyMap.UNPLAYABLE) NO_KEY else key)
        }
        out.align()
        if (handsHere != null) {
            for (i in 0 until n) out.u8(handsHere[i].toInt())
            out.align()
        }
        if (fingersHere != null) {
            for (i in 0 until n) out.u8(fingersHere[i].toInt().coerceIn(0, 5))
            out.align()
        }
        for (k in 0 until chordCount) out.u32(ms(chords!!.startMicros[k]))
        var end = 0
        for (k in 0 until chordCount) {
            end += names[k].size
            out.u32(end)
        }
        for (name in names) out.bytes(name)
        return out.toByteArray()
    }

    // ---- The score's index --------------------------------------------------------------------------------------

    /** Layout [layoutId] of the piece [rev] shows: its geometry, and when each system starts (ms). */
    fun index(layout: ScoreLayout, rev: Int, layoutId: Int): ByteArray {
        val m = layout.metrics
        val systems = layout.systems
        var flags = 0
        if (layout.quantized) flags = flags or INDEX_ENGRAVED
        if (m.pages == 2) flags = flags or INDEX_TWO_PAGES
        val out = WireWriter(INDEX_HEADER + systems.size * 4)
        out.u32(INDEX_MAGIC).u16(VERSION).u16(flags).u32(rev).u32(layoutId)
            .u32(m.pages).u32(m.systemsPerPage).u32(systems.size).u32(layout.pageCount).u32(m.barsPerSystem).u32(0)
        out.f32(m.pageWidth).f32(m.pageHeight).f32(m.pageGap).f32(m.slotLeft(1)).f32(m.firstSystemTop).f32(m.space)
            .f32(ScoreStyle.HAIRLINE_DP * m.density).f32(ScoreStyle.CURSOR_WIDTH_DP * m.density).f32(m.numberHeight)
            .f32(WebText.tempoSpace).f32(WebText.NUMBER_FONT).f32(WebText.CHORD_FONT).f32(WebText.NUMERAL_FONT).u32(0)
        for (s in systems) out.u32(ms(layout.bars.startMicros[s.firstBar]))
        return out.toByteArray()
    }

    // ---- A page of the score ------------------------------------------------------------------------------------

    /**
     * Page [page] of layout [layoutId]: its systems, bars, heads, ops and strings, with the [chords]' [names] (as
     * shown) placed by [ScoreMarks] from [text]'s widths; notes past [cap] bytes are left out ([PAGE_TRUNCATED]).
     * Null for a page the layout doesn't have.
     */
    fun page(
        layout: ScoreLayout,
        layoutId: Int,
        page: Int,
        chords: ChordTrack? = null,
        names: Array<String>? = null,
        text: ScoreText = WebText,
        cap: Int = MAX_PAGE_BYTES,
    ): ByteArray? {
        if (page < 0 || page >= layout.pageCount) return null
        val range = layout.systemsOn(page)
        val painter = PageOps(layout, text, cap)
        for (s in range) painter.system(layout.systems[s], chords?.takeIf { names != null && names.size == it.size }, names)
        val bars = layout.bars
        val barCount = range.sumOf { layout.systems[it].barCount }
        val heads = painter.heads.sortedHeads()
        val strings = painter.strings.map { it.toByteArray(Charsets.UTF_8) }
        val stringBytes = strings.sumOf { it.size }
        val out = WireWriter(PAGE_HEADER + range.count() * SYSTEM_WORDS * 4 + barCount * BAR_WORDS * 4 + heads.size * 4 + painter.ops.size * 4 + strings.size * 4 + stringBytes)
        out.u32(PAGE_MAGIC).u16(VERSION).u16(if (painter.truncated) PAGE_TRUNCATED else 0).u32(layoutId).u32(page)
            .u32(range.count()).u32(barCount).u32(heads.size / HEAD_WORDS).u32(painter.ops.size).u32(strings.size).u32(stringBytes)
        for (s in range) {
            val sys = layout.systems[s]
            out.u32(sys.index).u32(sys.firstBar).u32(sys.barCount).u32(if (sys.final) 1 else 0)
                .f32(sys.left).f32(sys.right).f32(sys.trebleTop).f32(sys.bassBottom).f32(sys.bandTop).f32(sys.bandBottom)
        }
        for (s in range) {
            val sys = layout.systems[s]
            for (bar in sys.firstBar..sys.lastBar) {
                out.u32(ms(bars.startMicros[bar])).f32(bars.right[bar])
                val left = bars.contentLeft[bar]
                val right = bars.contentRight[bar]
                for (k in 0 until BAR_POINTS) {
                    val fraction = k.toDouble() / (BAR_POINTS - 1)
                    out.u32(ms(bars.microsAt(bar, fraction))).f32(left + (fraction * (right - left)).toFloat())
                }
            }
        }
        for (word in heads) out.u32(word)
        for (k in 0 until painter.ops.size) out.u32(painter.ops[k])
        var end = 0
        for (s in strings) {
            end += s.size
            out.u32(end)
        }
        for (s in strings) out.bytes(s)
        return out.toByteArray()
    }

    /** Microseconds as whole milliseconds on the wire, held to a u32 (and never below zero). */
    private fun ms(micros: Long): Int = (micros / 1_000L).coerceIn(0L, 0xFFFF_FFFFL).toInt()

    private const val HEAD_SPACES = 1.18f
    private const val CLEF_SPACES = 2.74f

    /** A figure's cap height and advance, as shares of the em. */
    private const val NUMERAL_CAP = 0.71f
    private const val NUMERAL_ADVANCE = 0.65f
}

/**
 * One page's ops, emitted in `ScorePainter.system()`'s and `note()`'s order (the tablet's painter) at density 1, with
 * the same caps: [ScoreStyle.MAX_NOTE_DRAWS] heads, rests, numerals, beams and ties a system.
 */
private class PageOps(private val layout: ScoreLayout, private val text: ScoreText, private val cap: Int) {
    val ops = IntList()
    val heads = IntList()
    val strings = ArrayList<String>()
    private val stringIndex = HashMap<String, Int>()
    var truncated = false
        private set
    private var stringBytes = 0
    private var row = 0

    private val m = layout.metrics
    private val space = m.space
    private val hair = ScoreStyle.HAIRLINE_DP * m.density
    private val overhang = ScoreStyle.LEDGER_OVERHANG_DP * m.density
    private val finalStroke = ScoreStyle.FINAL_STROKE_DP * m.density
    private val finalGap = ScoreStyle.FINAL_GAP_DP * m.density
    private val numberLift = ScoreStyle.numberLift(space, m.density)

    /** Bytes so far: once past the cap less room for what closes the page, notes are left out. */
    private fun full(): Boolean {
        val bytes = ScoreDisplayList.PAGE_HEADER + ops.size * 4 + heads.size * 4 + strings.size * 4 + stringBytes + RESERVE
        if (bytes > cap) truncated = true
        return truncated
    }

    fun system(s: ScoreSystem, chords: ChordTrack?, names: Array<String>?) {
        op(ScoreDisplayList.OP_CLIP, 0, 0).f(s.bandTop).f(s.bandBottom)
        val length = s.right - s.left
        for (n in 0..4) {
            rect(ScoreDisplayList.ROLE_LINE, s.left, s.trebleTop + n * space - hair / 2, length, hair)
            rect(ScoreDisplayList.ROLE_LINE, s.left, s.bassTop + n * space - hair / 2, length, hair)
        }
        val span = s.bassBottom - s.trebleTop
        rect(ScoreDisplayList.ROLE_LINE, s.left - hair / 2, s.trebleTop, hair, span)   // the system's opening line
        for (bar in s.firstBar..s.lastBar) {
            val x = layout.bars.right[bar]
            if (s.final && bar == s.lastBar) {
                rect(ScoreDisplayList.ROLE_LINE, x - finalStroke - finalGap - hair, s.trebleTop, hair, span)
                rect(ScoreDisplayList.ROLE_LINE, x - finalStroke, s.trebleTop, finalStroke, span)
            } else {
                rect(ScoreDisplayList.ROLE_LINE, x - hair / 2, s.trebleTop, hair, span)
            }
        }
        for (k in s.signKind.indices) glyph(ScoreDisplayList.ROLE_GLYPH, ScoreDisplayList.STYLE_MUSIC, ScoreStyle.sign(s.signKind[k]), s.signX[k], s.signY[k])
        val number = (s.firstBar + 1).toString()
        val numberWidth = text.numberWidth(number)
        text(ScoreDisplayList.ROLE_NUMBER, ScoreDisplayList.STYLE_NUMBER, number, s.left, s.trebleTop - numberLift, numberWidth)
        val tempo = ScoreMarks.tempo(layout, s, numberWidth, text)
        if (tempo != null) tempoMark(tempo)
        if (chords != null && names != null) {
            val placed = ScoreMarks.chords(layout, s, chords, names, number, tempo, text)
            for (k in 0 until placed.size) {
                val name = names[placed.index[k]]
                // The box's top is its line's top: the browser draws on that line's baseline.
                text(ScoreDisplayList.ROLE_GLYPH, ScoreDisplayList.STYLE_CHORD, name, placed.x[k], placed.top[k] + ScoreDisplayList.WebText.CHORD_BASELINE, text.chordWidth(name))
            }
        }
        val rests = layout.rests
        for (k in ScoreStyle.capped(rests.inSystem(s.index))) {
            if (full()) break
            glyph(ScoreDisplayList.ROLE_NOTE, ScoreDisplayList.STYLE_MUSIC, ScoreStyle.rest(rests.value[k].toInt()), rests.x[k], rests.y[k])
        }
        val t = layout.ties
        for (k in ScoreStyle.capped(t.inSystem(s.index))) {
            val length = t.x2[k] - t.x1[k]
            if (length <= 0f) continue
            if (full()) break
            val bow = ScoreStyle.tieBow(length, space, t.above[k])
            op(ScoreDisplayList.OP_CURVE, ScoreDisplayList.ROLE_NOTE, 0).f(t.x1[k]).f(t.y1[k])
                .f((t.x1[k] + t.x2[k]) / 2).f((t.y1[k] + t.y2[k]) / 2 + bow).f(t.x2[k]).f(t.y2[k])
        }
        val b = layout.beams
        val thickness = Beams.THICKNESS * space
        for (k in ScoreStyle.capped(b.inSystem(s.index))) {
            if (full()) break
            val inward = if (b.up[k]) thickness else -thickness
            op(ScoreDisplayList.OP_QUAD, ScoreDisplayList.ROLE_NOTE, 0).f(b.x1[k]).f(b.y1[k]).f(b.x2[k]).f(b.y2[k])
                .f(b.x2[k]).f(b.y2[k] + inward).f(b.x1[k]).f(b.y1[k] + inward)
        }
        var drawn = 0
        for (i in s.firstNote until s.noteEnd) {
            if (drawn >= ScoreStyle.MAX_NOTE_DRAWS || full()) break
            if (layout.system[i] == s.index) {
                head(s, i, i, -1)
                drawn++
            }
        }
        for (h in s.firstTied until s.tiedEnd) {
            if (drawn >= ScoreStyle.MAX_NOTE_DRAWS || full()) break
            head(s, h, layout.tiedNote[h - layout.noteCount], ms(layout.tiedStartMicros[h - layout.noteCount]))
            drawn++
        }
        for (k in layout.dynamicsIn(s.index)) {
            val mark = layout.dynamics[k]
            text(ScoreDisplayList.ROLE_GLYPH, ScoreDisplayList.STYLE_MUSIC, mark.glyphs, mark.x, mark.y, 0f)
        }
        val f = layout.fingers
        for (k in ScoreStyle.capped(f.inSystem(s.index))) {
            if (full()) break
            val digit = f.finger[k].toInt().coerceIn(0, 5).toString()
            text(ScoreDisplayList.ROLE_GLYPH, ScoreDisplayList.STYLE_NUMERAL, digit, f.x[k], f.baseline[k], 0f, ScoreDisplayList.ALIGN_CENTRE)
        }
        op(ScoreDisplayList.OP_END, 0, 0)
        row++
    }

    /** The tempo mark: its note (dotted in a compound metre), then "= N" in the numbers' style (`ScorePainter.tempoMark`). */
    private fun tempoMark(tempo: TempoPlacement) {
        val unit = text.tempoSpace
        var x = tempo.x
        val noteBaseline = tempo.baseline - ScoreStyle.TEMPO_HEAD_BELOW * unit
        glyph(ScoreDisplayList.ROLE_GLYPH, ScoreDisplayList.STYLE_TEMPO_MUSIC, ScoreStyle.TEMPO_NOTE, x, noteBaseline)
        x += ScoreStyle.TEMPO_NOTE_WIDTH * unit
        if (tempo.mark.dotted) {
            glyph(ScoreDisplayList.ROLE_GLYPH, ScoreDisplayList.STYLE_TEMPO_MUSIC, ScoreStyle.DOT, x + ScoreStyle.TEMPO_DOT_GAP * unit, noteBaseline)
            x += (ScoreStyle.TEMPO_DOT_GAP + ScoreStyle.TEMPO_DOT_WIDTH) * unit
        }
        val words = tempo.mark.text
        text(ScoreDisplayList.ROLE_GLYPH, ScoreDisplayList.STYLE_NUMBER, words, x + ScoreStyle.TEMPO_TEXT_GAP * unit, tempo.baseline, text.numberWidth(words))
    }

    /** Head [i] of [s] (note [note]'s; [tiedStartMs] -1 for a note's own head), its ops contiguous (`ScorePainter.note`). */
    private fun head(s: ScoreSystem, i: Int, note: Int, tiedStartMs: Int) {
        val from = ops.size
        val role = ScoreDisplayList.ROLE_NOTE
        val x = layout.x[i]
        val y = layout.y[i]
        val headWidth = layout.headWidth(i)
        val top = if (layout.treble[i]) s.trebleTop else s.bassTop
        val ledgers = layout.ledgers[i].toInt()
        for (k in 1..abs(ledgers)) {
            val lineY = if (ledgers < 0) top + s.staffHeight + k * space else top - k * space
            rect(role, x - overhang, lineY - hair / 2, headWidth + 2 * overhang, hair)
        }
        val end = layout.durationEnd[i]
        if (!end.isNaN()) rect(role, x + headWidth, y - hair / 2, end - x - headWidth, hair)
        val stemX = layout.stemX[i]
        if (!stemX.isNaN()) {
            val from0 = layout.stemFrom[i]
            val to = layout.stemTo[i]
            rect(role, stemX, min(from0, to), hair, abs(to - from0))
            val flags = layout.flags[i].toInt()
            if (flags > 0) glyph(role, ScoreDisplayList.STYLE_MUSIC, ScoreStyle.flag(flags, layout.stemUp[i]), stemX, to)
        }
        val accidental = layout.accidental[i].toInt()
        if (accidental != Accidental.NONE) glyph(role, ScoreDisplayList.STYLE_MUSIC, ScoreStyle.accidental(accidental), layout.accidentalX[i], y)
        glyph(role, ScoreDisplayList.STYLE_MUSIC, ScoreStyle.head(layout.head[i].toInt()), x, y)
        if (layout.dotted[i]) glyph(role, ScoreDisplayList.STYLE_MUSIC, ScoreStyle.DOT, layout.dotX[i], layout.dotY[i])
        heads.add(note).add(tiedStartMs).add(from).add(ops.size).add(row)
    }

    private fun rect(role: Int, x: Float, y: Float, w: Float, h: Float) {
        op(ScoreDisplayList.OP_RECT, role, 0).f(x).f(y).f(w).f(h)
    }

    private fun glyph(role: Int, style: Int, codePoint: Int, x: Float, baseline: Float) {
        op(ScoreDisplayList.OP_GLYPH, role, style).add(codePoint).f(x).f(baseline)
    }

    private fun text(role: Int, style: Int, words: String, x: Float, baseline: Float, maxWidth: Float, align: Int = ScoreDisplayList.ALIGN_LEFT) {
        val index = stringIndex.getOrPut(words) {
            strings += words
            stringBytes += words.toByteArray(Charsets.UTF_8).size
            strings.size - 1
        }
        op(ScoreDisplayList.OP_TEXT, role, style, align).add(index).f(x).f(baseline).f(maxWidth)
    }

    private fun op(op: Int, role: Int, style: Int, align: Int = 0): IntList = ops.add(op or (role shl 8) or (style shl 16) or (align shl 24))

    private fun IntList.f(value: Float): IntList = add(java.lang.Float.floatToRawIntBits(value))

    private fun ms(micros: Long): Int = (micros / 1_000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        /** Room kept under the cap for a system's closing ops and the bars' table. */
        const val RESERVE = 16 * 1024
    }
}

/** A growable list of ints, without boxing. */
internal class IntList(capacity: Int = 1024) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(value: Int): IntList {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
        return this
    }

    operator fun get(i: Int): Int = data[i]

    /** The heads' table (five words a head) sorted by note, then by when each sounds (a note's own head first). */
    fun sortedHeads(): IntArray {
        val count = size / ScoreDisplayList.HEAD_WORDS
        val order = (0 until count).sortedWith(compareBy({ data[it * ScoreDisplayList.HEAD_WORDS] }, { data[it * ScoreDisplayList.HEAD_WORDS + 1] }))
        val out = IntArray(count * ScoreDisplayList.HEAD_WORDS)
        for ((k, h) in order.withIndex()) System.arraycopy(data, h * ScoreDisplayList.HEAD_WORDS, out, k * ScoreDisplayList.HEAD_WORDS, ScoreDisplayList.HEAD_WORDS)
        return out
    }
}

/** Little-endian bytes, growing as needed; [align] pads to a 4-byte boundary. */
internal class WireWriter(capacity: Int) {
    private var data = ByteArray(capacity.coerceAtLeast(64))
    private var size = 0

    private fun room(n: Int) {
        if (size + n > data.size) data = data.copyOf(max(data.size * 2, size + n))
    }

    fun u8(value: Int): WireWriter {
        room(1)
        data[size++] = value.toByte()
        return this
    }

    fun u16(value: Int): WireWriter {
        room(2)
        data[size++] = value.toByte()
        data[size++] = (value ushr 8).toByte()
        return this
    }

    fun u32(value: Int): WireWriter {
        room(4)
        data[size++] = value.toByte()
        data[size++] = (value ushr 8).toByte()
        data[size++] = (value ushr 16).toByte()
        data[size++] = (value ushr 24).toByte()
        return this
    }

    fun f32(value: Float): WireWriter = u32(java.lang.Float.floatToRawIntBits(value))

    fun bytes(value: ByteArray): WireWriter {
        room(value.size)
        System.arraycopy(value, 0, data, size, value.size)
        size += value.size
        return this
    }

    fun align(): WireWriter {
        while (size % 4 != 0) u8(0)
        return this
    }

    fun toByteArray(): ByteArray = data.copyOf(size)
}
