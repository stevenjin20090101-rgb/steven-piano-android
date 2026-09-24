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
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline

private val ThumbSize = 12.dp
private const val NOT_DRAGGING = -1f

/**
 * Where the piece is: elapsed time, a hairline track with a 12 dp round thumb, total time. The
 * times are eyebrows in tabular figures. Dragging scrubs (thumb and time follow the finger);
 * release seeks. The thumb is drawn per frame from [frameNanos] and [clock]; the times
 * change once a second. TalkBack can move it too.
 */
@Composable
fun Scrubber(
    durationMicros: Long,
    frameNanos: LongState,
    clock: SongClock,
    onSeek: (micros: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragFraction by remember { mutableFloatStateOf(NOT_DRAGGING) }
    val seek by rememberUpdatedState(onSeek)
    val elapsedSeconds by remember(durationMicros, clock) {
        derivedStateOf {
            val micros = if (dragFraction >= 0f) (dragFraction * durationMicros).toLong() else clock.positionAt(frameNanos.longValue)
            micros / 1_000_000L
        }
    }
    val totalSeconds = durationMicros / 1_000_000L
    val ink = MaterialTheme.colorScheme.onSurface
    val track = LocalHairline.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(Format.clock(elapsedSeconds))
        Box(
            Modifier
                .weight(1f)
                .height(48.dp)
                .padding(horizontal = 12.dp)
                .semantics {
                    contentDescription = "Position"
                    stateDescription = "${Format.clock(elapsedSeconds)} of ${Format.clock(totalSeconds)}"
                    progressBarRangeInfo = ProgressBarRangeInfo(elapsedSeconds.toFloat(), 0f..totalSeconds.toFloat().coerceAtLeast(1f))
                    setProgress { seconds ->
                        seek((seconds * 1_000_000f).toLong())
                        true
                    }
                }
                .pointerInput(durationMicros) {
                    val radius = ThumbSize.toPx() / 2
                    fun fractionAt(x: Float) = ((x - radius) / (size.width - 2 * radius)).coerceIn(0f, 1f)
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragFraction = fractionAt(down.position.x)
                        val released = drag(down.id) { change ->
                            change.consume()
                            dragFraction = fractionAt(change.position.x)
                        }
                        val fraction = dragFraction
                        dragFraction = NOT_DRAGGING
                        if (released) seek((fraction * durationMicros).toLong())
                    }
                }
                .drawBehind {
                    val radius = ThumbSize.toPx() / 2
                    val line = Hairline.toPx()
                    val fraction = dragFraction.takeIf { it >= 0f }
                        ?: if (durationMicros > 0) clock.positionAt(frameNanos.longValue).toFloat() / durationMicros else 0f
                    val x = radius + (size.width - 2 * radius) * fraction
                    val y = size.height / 2
                    drawRect(track, Offset(radius, y - line / 2), Size(size.width - 2 * radius, line))
                    drawRect(ink, Offset(radius, y - line / 2), Size(x - radius, line))
                    drawCircle(ink, radius, Offset(x, y))
                },
        )
        Eyebrow(Format.clock(totalSeconds))
    }
}
