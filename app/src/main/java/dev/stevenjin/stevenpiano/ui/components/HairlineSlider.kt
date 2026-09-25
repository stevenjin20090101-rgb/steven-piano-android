// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.ui.theme.LocalDisabledGlyph
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import kotlin.math.roundToInt

private val ThumbSize = 12.dp
private const val NOT_DRAGGING = -1f

/**
 * A setting's slider, drawn like the scrubber: a hairline track, the part up to the value in the
 * content colour, and a 12 dp round thumb, in a 48 dp tall target. A tap sets the value there; a
 * sideways drag moves it with the thumb under the finger, while a vertical one still scrolls the
 * page. [onChange] gets each new value, snapped to [step], as the finger moves. [value] null is
 * not known yet: the thumb waits at the start. Disabled, the thumb is the disabled-glyph grey and
 * the track has no fill. TalkBack reads [contentDescription] and [stateDescription], and moves it
 * a twentieth at a time.
 */
@Composable
fun HairlineSlider(
    value: Float?,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onChange: (Float) -> Unit,
    contentDescription: String,
    stateDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val change by rememberUpdatedState(onChange)
    val radius = with(LocalDensity.current) { ThumbSize.toPx() / 2 }
    val drag = remember { SliderDrag() }
    var dragX by remember { mutableFloatStateOf(NOT_DRAGGING) }   // read only while drawing
    val ink = MaterialTheme.colorScheme.onSurface
    val track = LocalHairline.current
    val disabledThumb = LocalDisabledGlyph.current
    val span = (range.endInclusive - range.start).coerceAtLeast(Float.MIN_VALUE)

    fun snap(v: Float): Float = (range.start + ((v - range.start) / step).roundToInt() * step).coerceIn(range)
    fun valueAt(x: Float): Float = snap(range.start + ((x - radius) / (drag.width - 2 * radius).coerceAtLeast(1f)).coerceIn(0f, 1f) * span)
    fun follow(x: Float) {
        dragX = x.coerceIn(0f, drag.width)
        val v = valueAt(dragX)
        if (v != drag.lastSent) {
            drag.lastSent = v
            change(v)
        }
    }

    Spacer(
        modifier
            .height(48.dp)
            .onSizeChanged { drag.width = it.width.toFloat() }
            .semantics {
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription
                progressBarRangeInfo = ProgressBarRangeInfo((value ?: range.start).coerceIn(range), range)
                if (enabled) {
                    setProgress { target ->
                        change(snap(target))
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(enabled, range, step, radius) {
                if (enabled) detectTapGestures { at -> change(valueAt(at.x)) }
            }
            .pointerInput(enabled, range, step, radius) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    // Sideways past the touch slop before the page scrolls: a drag. Positions, not
                    // deltas, so the thumb stays exactly under the finger.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = awaitHorizontalTouchSlopOrCancellation(down.id) { move, _ -> move.consume() } ?: return@awaitEachGesture
                    drag.lastSent = Float.NaN
                    follow(start.position.x)
                    try {
                        horizontalDrag(start.id) { move ->
                            move.consume()
                            follow(move.position.x)
                        }
                    } finally {
                        dragX = NOT_DRAGGING
                    }
                }
            }
            .drawBehind {
                val line = Hairline.toPx()
                val shown = if (dragX != NOT_DRAGGING) valueAt(dragX) else value
                val live = enabled && shown != null
                val fraction = shown?.let { ((it - range.start) / span).coerceIn(0f, 1f) } ?: 0f
                val x = radius + (size.width - 2 * radius) * fraction
                val y = size.height / 2
                drawRect(track, Offset(radius, y - line / 2), Size(size.width - 2 * radius, line))
                if (live) drawRect(ink, Offset(radius, y - line / 2), Size(x - radius, line))
                drawCircle(if (live) ink else disabledThumb, radius, Offset(x, y))
            },
    )
}

/** What the gestures need between frames: the track's width and the last value sent, so a drag sends each value once. */
private class SliderDrag {
    var width = 0f
    var lastSent = Float.NaN
}
