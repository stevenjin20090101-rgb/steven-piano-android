// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlin.math.max
import kotlin.math.min

/** Staff line spacing and the gap between the treble's bottom line and the bass's top line. */
private val LineGap = 6.dp
private val StaveGap = 40.dp
private val ClefInset = 8.dp
private val ClefToNotes = 8.dp

/** A ledger line runs this far past each side of its head (head width + 4 dp in all). */
private val LedgerOverhang = 2.dp
private val SharpGap = 1.dp
private val PlayheadWidth = 2.dp

/** The playhead sits this share of the width in from the left. */
private const val PLAYHEAD_AT = 1f / 3f

/** Notes starting within this of a chord's first note belong to the chord (performed chords are not exact). */
private const val CHORD_MICROS = 30_000L

/** Bravura (SMuFL) glyphs: G clef, F clef, sharp, black note head. */
private const val G_CLEF = ""
private const val F_CLEF = ""
private const val SHARP = ""
private const val BLACK_HEAD = ""

/** Bravura, the SMuFL reference music font (SIL Open Font Licence; see AUTHORS). */
private val Bravura = FontFamily(Font(R.font.bravura))

/**
 * The staff view: the piece's pitches on a grand staff, in time, in step with the roll. Not
 * engraved notation (no beams, rests, ties or voices): a filled head per note, a sharp before
 * the head of a black key, and a hairline trailing the head for the note's length. Keys from
 * middle C up sit on the treble staff, the rest on the bass staff, with ledger lines (in the
 * note's colour) as far as the piano reaches. The five lines of each staff are 1 dp in the
 * tertiary grey, not the hairline token, which all but vanishes on the dark surface. Time runs right to left at the roll's pixels per second through a 2 dp
 * playhead a third of the way in; upcoming heads are the secondary colour, and a head brightens
 * over 120 ms as it crosses the playhead (a cut when motion is reduced) and stays bright for its
 * duration. Clefs, sharps and heads are Bravura's glyphs.
 *
 * Inputs are the roll's: [notes] mapped through [KeyMap] with [transpose] and [fold], and the one
 * [frameNanos] state read inside the draw phase, so each frame redraws without recomposing. The
 * visible window is found by the same binary search, and nothing is allocated per note.
 */
@Composable
fun StaffCanvas(
    notes: NoteList,
    transpose: Int,
    fold: Boolean,
    frameNanos: LongState,
    clock: SongClock,
    modifier: Modifier = Modifier,
) {
    val upcoming = MaterialTheme.colorScheme.onSurfaceVariant
    val sounding = MaterialTheme.colorScheme.onSurface
    val line = LocalTertiary.current   // legible in the dark, still quieter than the notes
    val reduced = rememberReducedMotion()
    val measurer = rememberTextMeasurer()
    val staffNotes = remember(notes, transpose, fold) { StaffNotes(notes, transpose, fold) }
    Spacer(
        modifier
            .clipToBounds()
            .drawWithCache {
                val staff = Staff(
                    scope = this,
                    measurer = measurer,
                    notes = staffNotes,
                    flipMicros = if (reduced) 0L else Motion.FastMs * 1_000L,
                    ramp = colorRamp(upcoming, sounding),
                    line = line,
                    clef = upcoming,
                    playhead = sounding,
                )
                onDrawBehind { staff.draw(this, clock.positionAt(frameNanos.longValue)) }
            },
    )
}

/** A piece's notes as the staff draws them: the key each sounds (after transpose and fold), and which heads a second moves aside. */
private class StaffNotes(val list: NoteList, transpose: Int, fold: Boolean) {
    val keys = IntArray(list.size) { KeyMap.map(list.note(it), transpose, fold) }
    val moved: BooleanArray = StaffPitch.secondOffsets(list.startMicros, keys, CHORD_MICROS)
}

/** One size's staff: where the lines are, the glyphs measured once, and the per-frame drawing. */
private class Staff(
    scope: CacheDrawScope,
    measurer: TextMeasurer,
    private val notes: StaffNotes,
    private val flipMicros: Long,
    private val ramp: Array<Color>,
    private val line: Color,
    private val clef: Color,
    private val playhead: Color,
) {
    private val width = scope.size.width
    private val height = scope.size.height
    private val gap = with(scope) { LineGap.toPx() }
    private val step = gap / 2
    private val hair = with(scope) { Hairline.toPx() }
    private val overhang = with(scope) { LedgerOverhang.toPx() }
    private val sharpGap = with(scope) { SharpGap.toPx() }
    private val playheadWidth = with(scope) { PlayheadWidth.toPx() }
    private val pxPerMicro = with(scope) { NOTES_DP_PER_SECOND.dp.toPx() } / 1_000_000f

    // SMuFL fonts are drawn at four staff spaces to the em, so a head is 1.18 spaces (about 7 dp) wide.
    // In dp, not sp: the staff is a drawing, and keeps its size at any font scale, as the roll does.
    private val music = TextStyle(fontFamily = Bravura, fontSize = with(scope) { (LineGap * 4).toSp() })
    private val gClef = measurer.measure(G_CLEF, music, maxLines = 1, density = scope)
    private val fClef = measurer.measure(F_CLEF, music, maxLines = 1, density = scope)
    private val sharp = measurer.measure(SHARP, music, maxLines = 1, density = scope)
    private val head = measurer.measure(BLACK_HEAD, music, maxLines = 1, density = scope)
    private val headWidth = head.size.width.toFloat()

    private val trebleBottom: Float
    private val bassBottom: Float

    init {
        val staffHeight = gap * 4
        val total = staffHeight * 2 + with(scope) { StaveGap.toPx() }
        var top = (height - total) / 2   // the treble's top line, the grand staff centred
        // Room above for B7's ledger lines, taken from below where C1's still fit.
        val above = (StaffPitch.position(KeyMap.HIGHEST) - StaffPitch.TOP_LINE) * step + gap / 2
        val below = -StaffPitch.position(KeyMap.LOWEST) * step + gap / 2
        if (top < above) top += min(above - top, max(0f, height - (top + total) - below))
        trebleBottom = top + staffHeight
        bassBottom = top + total
    }

    private val clefLeft = with(scope) { ClefInset.toPx() }
    private val notesLeft = clefLeft + max(gClef.size.width, fClef.size.width) + with(scope) { ClefToNotes.toPx() }
    private val playheadX = max(notesLeft, width * PLAYHEAD_AT)

    /** A note is drawn while any of it (sharp, head, duration) is between these edges of the window, in µs from now. */
    private val behindMicros = ((playheadX - notesLeft + 2 * headWidth) / pxPerMicro).toLong()
    private val aheadMicros = ((width - playheadX + sharp.size.width + sharpGap) / pxPerMicro).toLong()

    fun draw(scope: DrawScope, now: Long) = with(scope) {
        for (n in 0..4) {
            drawRect(line, Offset(0f, trebleBottom - n * gap - hair / 2), Size(width, hair))
            drawRect(line, Offset(0f, bassBottom - n * gap - hair / 2), Size(width, hair))
        }
        glyph(gClef, clefLeft, trebleBottom - gap, clef)       // on the G line
        glyph(fClef, clefLeft, bassBottom - 3 * gap, clef)     // on the F line
        clipRect(left = notesLeft) { drawNotes(now) }
        drawRect(playhead, Offset(playheadX - playheadWidth / 2, 0f), Size(playheadWidth, height))
    }

    private fun DrawScope.drawNotes(now: Long) {
        val list = notes.list
        val starts = list.startMicros
        val ends = list.endMicros
        val windowStart = now - behindMicros
        val windowEnd = now + aheadMicros
        for (i in list.firstStartingAtOrAfter(windowStart - list.maxDurationMicros) until list.size) {
            val start = starts[i]
            if (start > windowEnd) break
            val end = ends[i]
            if (end < windowStart) continue
            val key = notes.keys[i]
            if (key == KeyMap.UNPLAYABLE) continue
            val x = playheadX + (start - now) * pxPerMicro
            val left = if (notes.moved[i]) x + headWidth else x
            val treble = StaffPitch.onTreble(key)
            val bottom = if (treble) trebleBottom else bassBottom
            val position = StaffPitch.position(key)
            val y = bottom - position * step
            val color = ramp[rampLevel(now, start, end, flipMicros)]
            // Ledger lines belong to their note: drawn in its colour, since a short hairline in the
            // staff's own grey all but disappears, and they carry the pitch.
            val ledgers = StaffPitch.ledgerLines(position)
            for (n in 1..(if (ledgers < 0) -ledgers else ledgers)) {
                val ledgerY = if (ledgers < 0) bottom + n * gap else bottom - (StaffPitch.TOP_LINE / 2 + n) * gap
                drawRect(color, Offset(left - overhang, ledgerY - hair / 2), Size(headWidth + 2 * overhang, hair))
            }
            drawRect(color, Offset(left + headWidth, y - hair / 2), Size((end - start) * pxPerMicro, hair))
            if (StaffPitch.isSharp(key)) glyph(sharp, x - sharpGap - sharp.size.width, y, color)
            glyph(head, left, y, color)
        }
    }

    /** Draws [glyph] with its origin (SMuFL's: left edge, on the baseline) at ([x], [baseline]). */
    private fun DrawScope.glyph(glyph: TextLayoutResult, x: Float, baseline: Float, color: Color) {
        drawText(glyph, color = color, topLeft = Offset(x, baseline - glyph.firstBaseline))
    }
}
