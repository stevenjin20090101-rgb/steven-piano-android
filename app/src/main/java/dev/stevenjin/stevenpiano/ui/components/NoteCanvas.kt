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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.midi.NoteList
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlin.math.max
import kotlin.math.min

/** How fast notes travel, on the roll and on the staff alike: this many dp per second of music (so it slows with the tempo). */
internal const val NOTES_DP_PER_SECOND = 120f

/** The tracker bar sits this share of the height up from the bottom (paper roll). */
private const val TRACKER_FROM_BOTTOM = 1f / 3f

/** Steps between upcoming and sounding colours, precomputed so drawing never allocates. */
internal const val RAMP_STEPS = 12

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
) {
    val upcoming = MaterialTheme.colorScheme.onSurfaceVariant
    val sounding = MaterialTheme.colorScheme.onSurface
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
                    ramp = colorRamp(upcoming, sounding),
                )
                val bar = 2.dp.toPx()
                val edgeGap = 6.dp.toPx()
                val edgeLine = Hairline.toPx()
                onDrawBehind {
                    val now = clock.positionAt(frameNanos.longValue)
                    for (i in 0 until KeyMap.KEY_COUNT) {
                        if (roll.keys.isBlack(i)) drawRect(blackLane, Offset(roll.keys.left(i), 0f), Size(roll.keys.width(i), size.height))
                    }
                    roll.drawNotes(this, now, black = false)
                    roll.drawNotes(this, now, black = true)
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
    val ramp: Array<Color>,
) {
    /** Where notes sound: the tracker bar, or the bottom edge for falling notes. */
    val hitY: Float = if (paper) height * (1f - TRACKER_FROM_BOTTOM) else height
    private val aheadMicros = (hitY / pxPerMicro).toLong()
    private val behindMicros = ((height - hitY) / pxPerMicro).toLong()

    fun drawNotes(scope: DrawScope, now: Long, black: Boolean) {
        val windowStart = now - behindMicros
        val windowEnd = now + aheadMicros
        val starts = notes.startMicros
        val ends = notes.endMicros
        for (i in notes.firstStartingAtOrAfter(windowStart - notes.maxDurationMicros) until notes.size) {
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
            scope.drawRoundRect(
                color = ramp[rampLevel(now, start, end, flipMicros)],
                topLeft = Offset(keys.left(lane) + inset, top),
                size = Size(width, bottom - top),
                cornerRadius = if (paper) CornerRadius(width / 2) else CornerRadius.Zero,
            )
        }
    }
}
