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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.score.ChordTrack
import dev.stevenjin.stevenpiano.score.Hands
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.ui.theme.LocalHandColours
import dev.stevenjin.stevenpiano.ui.theme.LocalHandTones
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlin.math.max
import kotlin.math.min

/** How fast notes travel, on the roll and on the staff alike: this many dp per second of music (so it slows with the tempo). */
internal const val NOTES_DP_PER_SECOND = 120f

/** The tracker bar sits this share of the height up from the bottom (paper roll). */
private const val TRACKER_FROM_BOTTOM = 1f / 3f

/** A bar carries its finger's numeral when it is at least this many numerals tall... */
internal const val NUMERALS_TALL = 3f

/** ...and at least this share of a numeral wide (a black key's lane on a phone is not). */
private const val NUMERAL_MIN_WIDTH = 0.75f

/** A numeral sits this far inside its bar's leading edge. */
private val NumeralPad = 2.dp

/** A chord name sits this far from the canvas's left edge, on a backing this much larger than its text. */
private val ChordInset = 4.dp
private val ChordPad = 4.dp

/**
 * With Hand colours on, a sounding note (and a key the strip lights) mixes its hand's colour this far
 * toward the content colour: lighter on the dark surface, darker on the paper, as the grey ones brighten.
 */
internal const val HAND_SOUNDING_MIX = 0.45f

/** Steps between upcoming and sounding colours, precomputed so drawing never allocates. */
internal const val RAMP_STEPS = 12

/**
 * How far before the visible window a frame looks for notes still sounding into it: the longest
 * note, but never more than 30 s. One note left unreleased runs to the piece's end; without the
 * bound every frame would scan from the first note.
 */
internal const val MAX_BACKOFF_MICROS = 30_000_000L

/** Notes drawn per frame at most, however dense the file: the roll and the score's overlay alike. */
internal const val MAX_NOTE_DRAWS = 4_000

/** Chord names drawn per frame at most (names clear of each other fill the tallest canvas with about fifty). */
internal const val MAX_CHORD_DRAWS = 64

/** A system's run of rests, numerals, beams or ties as the score's page draws it: its first [MAX_NOTE_DRAWS] only. */
internal fun IntRange.capped(): IntRange = if (last - first + 1 > MAX_NOTE_DRAWS) first until first + MAX_NOTE_DRAWS else this

/**
 * Of the chords starting at [starts] (in time order), the ones whose names the waterfall draws: each
 * clear of the one kept before it, whose backing reaches [reach] (its index) microseconds past its
 * start. Names travel together, so two that overlap always do: chosen once for the canvas's scale, the
 * same names show every frame, never one popping in as another leaves.
 */
internal fun clearNames(starts: LongArray, reach: (Int) -> Long): IntArray {
    val kept = IntArray(starts.size)
    var count = 0
    var clearFrom = Long.MIN_VALUE   // where the last kept name's backing ends, in time
    for (i in starts.indices) {
        if (starts[i] < clearFrom) continue
        kept[count++] = i
        clearFrom = starts[i] + reach(i)
    }
    return kept.copyOf(count)
}

/** Where a frame's scan starts: the first note that can still be sounding at [windowStart], within [MAX_BACKOFF_MICROS]. */
internal fun NoteList.scanStart(windowStart: Long): Int =
    firstStartingAtOrAfter(windowStart - minOf(maxDurationMicros, MAX_BACKOFF_MICROS))

/** The upcoming-to-sounding colours, [RAMP_STEPS] + 1 of them, for [rampLevel]. */
internal fun colorRamp(upcoming: Color, sounding: Color): Array<Color> =
    Array(RAMP_STEPS + 1) { lerp(upcoming, sounding, it / RAMP_STEPS.toFloat()) }

/**
 * How bright a note is at [now]: 0 upcoming, [RAMP_STEPS] sounding, easing across the line both
 * ways over [flipMicros] (0: a cut, when motion is reduced).
 */
internal fun rampLevel(now: Long, start: Long, end: Long, flipMicros: Long): Int {
    val bright = when {
        now < start -> 0f
        now < end -> if (flipMicros == 0L) 1f else min(1f, (now - start).toFloat() / flipMicros)
        else -> if (flipMicros == 0L) 0f else max(0f, 1f - (now - end).toFloat() / flipMicros)
    }
    return (bright * RAMP_STEPS + 0.5f).toInt()
}

/**
 * The note canvas. 84 lanes for C1-B7 (black-key lanes narrower and darker), notes from [notes]
 * mapped through [KeyMap] with [transpose] and [fold], travelling downward at tempo in both
 * styles ([display]):
 *  - paper roll: perforations (rounded bars 1 dp inside their lane) through a 2 dp tracker bar
 *    one third up from the bottom, with a 1 dp grey edge 6 dp above it; the third below shows
 *    what just played;
 *  - falling notes: square-ended blocks whose hit line is the canvas bottom, the top of the
 *    keyboard strip; nothing below it.
 * Upcoming notes are the secondary colour; crossing the line a note brightens to the content
 * colour over 120 ms (a cut when motion is reduced) and stays bright for its duration.
 * With [hands] (`score.Hands`, one per note) the right hand's notes are filled bars and the left
 * hand's outlined ones: a 1 dp line in the same colours with the elevated surface inside
 * (DESIGN.md › v1.3 › The waterfall format). With [fingers] (`score.Fingering`) each bar at least
 * [NUMERALS_TALL] numerals tall carries its suggested finger in small tabular figures inside it at
 * its leading edge, the end that reaches the line first: knocked out of a filled bar (the elevated
 * surface's colour; the content colour would vanish on a bar that is itself that colour as it
 * sounds) and in the secondary colour inside an outlined one. With Hand colours on (`LocalHandColours`)
 * the bars take their hand's colour (`LocalHandTones`: the left hand green, the right blue, each easing
 * toward the content colour as it sounds); off, they are monochrome. With [chords], each chord's name stands
 * at the canvas's left edge where the chord begins, an eyebrow-sized label on a 4 dp backing of the
 * elevated surface (so it reads over the bars), travelling with the notes; a name that would overlap
 * the one before it is left out, and at most [MAX_CHORD_DRAWS] are drawn a frame (the v1.3 delta
 * audit, L3: beats of a quarter of a millisecond once put 20,000 names in one frame).
 *
 * The only state read is [frameNanos], inside the draw phase, so each frame redraws without
 * recomposing; notes come from start-sorted arrays found by binary search, and nothing is
 * allocated per note or per frame.
 */
@Composable
fun NoteCanvas(
    notes: NoteList,
    transpose: Int,
    fold: Boolean,
    display: NoteDisplay,
    frameNanos: LongState,
    clock: SongClock,
    modifier: Modifier = Modifier,
    hands: ByteArray? = null,
    fingers: ByteArray? = null,
    chords: ChordTrack? = null,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val numeralStyle = MaterialTheme.typography.labelSmall.merge(Tabular).merge(TextStyle(letterSpacing = 0.sp))
    val numerals = remember(measurer, density, numeralStyle) { Numerals(measurer, density, numeralStyle) }
    // Chord names in the eyebrow's size and tracking, in the chord's own case ("Am", not "AM"); each
    // distinct name measured once.
    val chordStyle = MaterialTheme.typography.labelSmall
    val chordLabels = remember(chords, transpose, measurer, density, chordStyle) {
        chords?.takeIf { it.size > 0 }?.let { c ->
            val measured = HashMap<String, TextLayoutResult>()
            Array(c.size) { i -> c.name(i, transpose).let { name -> measured.getOrPut(name) { measurer.measure(name, chordStyle, maxLines = 1, density = density) } } }
        }
    }
    val upcoming = MaterialTheme.colorScheme.onSurfaceVariant
    val sounding = MaterialTheme.colorScheme.onSurface
    val inside = MaterialTheme.colorScheme.surfaceVariant
    val handTones = if (LocalHandColours.current && hands != null) LocalHandTones.current else null
    val blackLane = MaterialTheme.colorScheme.surface
    val edge = LocalTertiary.current
    val reduced = rememberReducedMotion()
    Spacer(
        modifier
            .clipToBounds()
            .drawWithCache {
                val roll = Roll(
                    notes = notes,
                    keys = KeyLayout(size.width),
                    transpose = transpose,
                    fold = fold,
                    paper = display == NoteDisplay.PAPER_ROLL,
                    height = size.height,
                    pxPerMicro = NOTES_DP_PER_SECOND.dp.toPx() / 1_000_000f,
                    inset = 1.dp.toPx(),
                    minHeight = 2.dp.toPx(),
                    flipMicros = if (reduced) 0L else Motion.FastMs * 1_000L,
                    ramp = handTones?.let { colorRamp(it.right, lerp(it.right, sounding, HAND_SOUNDING_MIX)) } ?: colorRamp(upcoming, sounding),
                    leftRamp = handTones?.let { colorRamp(it.left, lerp(it.left, sounding, HAND_SOUNDING_MIX)) },
                    hands = hands?.takeIf { it.size == notes.size },
                    inside = inside,
                    outline = Stroke(width = Hairline.toPx()),
                    fingers = fingers?.takeIf { it.size == notes.size },
                    numerals = numerals,
                    numeralPad = NumeralPad.toPx(),
                    numeralOnOutline = upcoming,
                    chords = if (chordLabels != null) chords else null,
                    chordLabels = chordLabels,
                    chordInset = ChordInset.toPx(),
                    chordPad = ChordPad.toPx(),
                    chordColor = upcoming,
                )
                val bar = 2.dp.toPx()
                val edgeGap = 6.dp.toPx()
                val edgeLine = Hairline.toPx()
                onDrawBehind {
                    val now = clock.positionAt(frameNanos.longValue)
                    for (i in 0 until KeyMap.KEY_COUNT) {
                        if (roll.keys.isBlack(i)) drawRect(blackLane, Offset(roll.keys.left(i), 0f), Size(roll.keys.width(i), size.height))
                    }
                    val drawn = roll.drawNotes(this, now, black = false, budget = MAX_NOTE_DRAWS)
                    roll.drawNotes(this, now, black = true, budget = MAX_NOTE_DRAWS - drawn)
                    roll.drawChords(this, now)
                    if (roll.paper) {
                        drawRect(edge, Offset(0f, roll.hitY - bar / 2 - edgeGap - edgeLine), Size(size.width, edgeLine))
                        drawRect(sounding, Offset(0f, roll.hitY - bar / 2), Size(size.width, bar))
                    }
                }
            },
    )
}

/** One size's geometry for the canvas, built when the size or the inputs change. */
private class Roll(
    val notes: NoteList,
    val keys: KeyLayout,
    val transpose: Int,
    val fold: Boolean,
    val paper: Boolean,
    height: Float,
    val pxPerMicro: Float,
    val inset: Float,
    val minHeight: Float,
    val flipMicros: Long,
    /** Upcoming to sounding colours: every note's, or with hand colours the right hand's... */
    val ramp: Array<Color>,
    /** ...and the left hand's (null: the same as [ramp]). */
    val leftRamp: Array<Color>?,
    /** Each note's hand, or null to draw every note filled. */
    val hands: ByteArray?,
    /** The inside of a left-hand (outlined) bar. */
    val inside: Color,
    val outline: Stroke,
    /** Each note's suggested finger (0: none), or null for no numerals. */
    val fingers: ByteArray?,
    val numerals: Numerals,
    /** A numeral keeps this far inside its bar's leading edge. */
    val numeralPad: Float,
    /** A numeral's colour inside an outlined bar (inside a filled one it takes [inside]'s). */
    val numeralOnOutline: Color,
    /** The chords and each one's measured name, or null for none. */
    val chords: ChordTrack?,
    val chordLabels: Array<TextLayoutResult>?,
    val chordInset: Float,
    val chordPad: Float,
    val chordColor: Color,
) {
    /** The shortest and narrowest bar that carries a numeral. */
    private val numeralMinHeight = NUMERALS_TALL * numerals.height
    private val numeralMinWidth = NUMERAL_MIN_WIDTH * numerals.width

    /** Where notes sound: the tracker bar, or the bottom edge for falling notes. */
    val hitY: Float = if (paper) height * (1f - TRACKER_FROM_BOTTOM) else height
    private val aheadMicros = (hitY / pxPerMicro).toLong()
    private val behindMicros = ((height - hitY) / pxPerMicro).toLong()

    /** The chords whose names are drawn ([clearNames]): each clear of the one kept before it. */
    private val shownChords: IntArray = run {
        val chords = chords
        val labels = chordLabels
        if (chords == null || labels == null) IntArray(0)
        else clearNames(chords.startMicros) { ((labels[it].size.height + 2 * chordPad) / pxPerMicro).toLong() }
    }

    /**
     * The chord names in view at [now], at most [MAX_CHORD_DRAWS]: each at the left edge, its
     * backing's bottom on the line where its chord begins, so it travels with the notes.
     * Allocation-free.
     */
    fun drawChords(scope: DrawScope, now: Long) {
        val chords = chords ?: return
        val labels = chordLabels ?: return
        val shown = shownChords
        if (shown.isEmpty()) return
        val height = hitY + behindMicros * pxPerMicro
        // A label reaches this far above its line, so one that starts just below the top edge still shows.
        val reach = ((labels[0].size.height + 2 * chordPad) / pxPerMicro).toLong()
        var k = firstShownAtOrAfter(chords, now - behindMicros)
        var drawn = 0
        while (k < shown.size && drawn < MAX_CHORD_DRAWS) {
            val i = shown[k]
            val start = chords.startMicros[i]
            if (start > now + aheadMicros + reach) break
            val text = labels[i]
            val bottom = hitY - (start - now) * pxPerMicro
            val boxHeight = text.size.height + 2 * chordPad
            if (bottom > 0f && bottom - boxHeight < height) {
                scope.drawRect(inside, Offset(chordInset, bottom - boxHeight), Size(text.size.width + 2 * chordPad, boxHeight))
                scope.drawText(text, color = chordColor, topLeft = Offset(chordInset + chordPad, bottom - boxHeight + chordPad))
                drawn++
            }
            k++
        }
    }

    /** The first of [shownChords] starting at or after [micros] (their count when there is none). */
    private fun firstShownAtOrAfter(chords: ChordTrack, micros: Long): Int {
        val shown = shownChords
        var lo = 0
        var hi = shown.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (chords.startMicros[shown[mid]] < micros) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Draws the visible white (or black) notes, at most [budget] of them; returns how many it drew. */
    fun drawNotes(scope: DrawScope, now: Long, black: Boolean, budget: Int): Int {
        val windowStart = now - behindMicros
        val windowEnd = now + aheadMicros
        val starts = notes.startMicros
        val ends = notes.endMicros
        var drawn = 0
        for (i in notes.scanStart(windowStart) until notes.size) {
            if (drawn >= budget) break
            val start = starts[i]
            if (start > windowEnd) break
            val end = ends[i]
            if (end < windowStart) continue
            val key = KeyMap.map(notes.note(i), transpose, fold)
            if (key == KeyMap.UNPLAYABLE) continue
            val lane = key - KeyMap.LOWEST
            if (keys.isBlack(lane) != black) continue
            val bottom = hitY - (start - now) * pxPerMicro
            val top = min(hitY - (end - now) * pxPerMicro, bottom - minHeight)
            val width = keys.width(lane) - 2 * inset
            val left = keys.left(lane) + inset
            val outlined = hands != null && hands[i] == Hands.LEFT
            val color = (if (outlined && leftRamp != null) leftRamp else ramp)[rampLevel(now, start, end, flipMicros)]
            if (outlined) {
                // Outlined: the elevated surface inside a 1 dp line drawn just within the bar's edge.
                val line = outline.width
                scope.drawRoundRect(inside, Offset(left, top), Size(width, bottom - top), if (paper) CornerRadius(width / 2) else CornerRadius.Zero)
                scope.drawRoundRect(
                    color = color,
                    topLeft = Offset(left + line / 2, top + line / 2),
                    size = Size(width - line, bottom - top - line),
                    cornerRadius = if (paper) CornerRadius((width - line) / 2) else CornerRadius.Zero,
                    style = outline,
                )
            } else {
                scope.drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(width, bottom - top),
                    cornerRadius = if (paper) CornerRadius(width / 2) else CornerRadius.Zero,
                )
            }
            val finger = fingers?.get(i)?.toInt() ?: 0
            if (finger > 0 && bottom - top >= numeralMinHeight && width >= numeralMinWidth) {
                // The leading edge: the bar's bottom, which meets the line first; clear of a perforation's round end.
                val digit = numerals.digits[finger.coerceAtMost(5)]
                val baseline = bottom - numeralPad - if (paper) min(width / 2, numerals.height / 2) else 0f
                scope.drawText(
                    digit,
                    color = if (outlined) numeralOnOutline else inside,
                    topLeft = Offset(left + (width - digit.size.width) / 2, baseline - digit.firstBaseline),
                )
            }
            drawn++
        }
        return drawn
    }
}
