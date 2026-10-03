// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stevenjin.stevenpiano.ui.theme.DialFigure
import dev.stevenjin.stevenpiano.ui.theme.LocalAttention
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * What a [Dial] shows: the [figure] ("82") with its [unit], the [word] under it ("Charging", "Needs newer firmware"), how
 * much of the arc is filled ([share], 0–1; null with a figure: the figure alone, no arc), whether it needs [attention],
 * and what TalkBack says ([spoken], "82 percent, charging", with [current] of [max] for the progress bar). No figure: an
 * empty arc, "—" and the word saying why ([waiting]).
 */
@Immutable
data class DialValue(
    val figure: String? = null,
    val unit: String = "",
    val word: String = "",
    val share: Float? = null,
    val attention: Boolean = false,
    val current: Float = 0f,
    val max: Float = 100f,
    val spoken: String = "",
) {
    companion object {
        /** No figure: the arc empty, "—", and [word] saying why ("Not connected"; "" while nothing has been read). */
        fun waiting(word: String): DialValue = DialValue(word = word, spoken = word.lowercase().ifEmpty { "not known" })
    }
}

/**
 * A dial (DESIGN.md › v1.18 — M50; the panel's System page draws the same): a 270° arc, 7 dp with round caps, over a faint
 * track and its ticks (every 9°, major every 45°); the figure large in the centre with its unit, the word under it, the two
 * ends ([low], [high]; an empty [high] shows none) under the arc, and [label] under the dial as an eyebrow. The arc is the
 * content colour, or the attention amber (with its word) when [value] needs attention; with no figure it is empty and the
 * figure "—". The arc eases to its value over [Motion.SlowMs] when it first shows and on each change, at once under
 * reduced motion; nothing loops. The dial grows with the text size, so its figure always fits inside it. TalkBack hears
 * one progress bar: "Battery, 82 percent, charging".
 */
@Composable
fun Dial(label: String, value: DialValue, low: String, high: String, modifier: Modifier = Modifier) {
    val side = with(LocalDensity.current) { DIAL_SIDE.sp.toDp() }
    val unit = side / DIAL_SIDE
    val content = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val attention = LocalAttention.current
    val tertiary = LocalTertiary.current
    val reduced = rememberReducedMotion()
    val shown = value.figure != null
    val arc = remember { Animatable(0f) }
    val target = value.share?.coerceIn(0f, 1f) ?: 0f
    LaunchedEffect(target, reduced) { arc.animateTo(target, Motion.timed(Motion.SlowMs, reduced, Motion.EaseOut)) }
    val accent = if (value.attention && shown) attention else content

    val top = value.max.coerceAtLeast(1f)
    Column(
        modifier.clearAndSetSemantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(value.current.coerceIn(0f, top), 0f..top)
            stateDescription = value.spoken
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(side)) {
            Canvas(Modifier.fillMaxSize()) {
                val u = size.width / DIAL_SIDE
                val centre = Offset(size.width / 2f, CENTRE_Y * u)
                for (i in 0..TICKS) {
                    val angle = Math.toRadians((START_ANGLE + i * TICK_STEP).toDouble())
                    val major = i % MAJOR_EVERY == 0
                    val from = TICK_FROM * u
                    val to = (if (major) TICK_MAJOR_TO else TICK_MINOR_TO) * u
                    val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    drawLine(
                        content.copy(alpha = if (major) MAJOR_ALPHA else MINOR_ALPHA),
                        centre + direction * from,
                        centre + direction * to,
                        strokeWidth = (if (major) MAJOR_WIDTH else MINOR_WIDTH) * u,
                    )
                }
                val radius = RADIUS * u
                val topLeft = Offset(centre.x - radius, centre.y - radius)
                val ring = Size(radius * 2, radius * 2)
                val stroke = Stroke(width = STROKE * u, cap = StrokeCap.Round)
                drawArc(content.copy(alpha = TRACK_ALPHA), START_ANGLE, SWEEP, useCenter = false, topLeft = topLeft, size = ring, style = stroke)
                if (shown && value.share != null) {
                    drawArc(accent, START_ANGLE, max(SWEEP * arc.value, MIN_SWEEP), useCenter = false, topLeft = topLeft, size = ring, style = stroke)
                }
            }
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = unit * READ_TOP),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row {
                    Text(
                        value.figure ?: NONE,
                        Modifier.alignByBaseline(),
                        style = DialFigure.merge(Tabular),
                        color = if (shown) content else secondary,
                        maxLines = 1,
                    )
                    if (shown && value.unit.isNotEmpty()) {
                        Text(
                            value.unit,
                            Modifier
                                .alignByBaseline()
                                .padding(start = 1.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = secondary,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    value.word,
                    Modifier.widthIn(max = unit * WORD_WIDTH),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp, fontWeight = if (value.attention && shown) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (value.attention && shown) attention else secondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            End(low, Modifier.align(Alignment.TopStart).padding(start = unit * ENDS_INSET, top = unit * ENDS_TOP), tertiary)
            if (high.isNotEmpty()) End(high, Modifier.align(Alignment.TopEnd).padding(end = unit * ENDS_INSET, top = unit * ENDS_TOP), tertiary)
        }
        Eyebrow(label, Modifier.padding(top = 8.dp), maxLines = 1)
    }
}

/** One end of the arc's range, under it. */
@Composable
private fun End(text: String, modifier: Modifier, color: Color) {
    Text(text, modifier, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = color, maxLines = 1)
}

// The panel's dial, in its own units (a 132 square): the centre a little below the middle, the arc's radius and stroke,
// the ticks outside it, where the figure starts, the ends under the arc. The side is in sp, so the dial grows with the text.
private const val DIAL_SIDE = 132f
private const val CENTRE_Y = 68f
private const val RADIUS = 52f
private const val STROKE = 7f
private const val TICK_FROM = 61f
private const val TICK_MINOR_TO = 64.5f
private const val TICK_MAJOR_TO = 67f
private const val MINOR_WIDTH = 1f
private const val MAJOR_WIDTH = 1.4f
private const val MINOR_ALPHA = 0.3f
private const val MAJOR_ALPHA = 0.6f
private const val TRACK_ALPHA = 0.12f
private const val READ_TOP = 44f
private const val WORD_WIDTH = 96f
private const val ENDS_TOP = 116f
private const val ENDS_INSET = 19f

/** The arc from 135° (lower left) round to 45° (lower right), clockwise; a tick every 9°, a major one every 45°. */
private const val START_ANGLE = 135f
private const val SWEEP = 270f
private const val TICK_STEP = 9f
private const val TICKS = 30
private const val MAJOR_EVERY = 5

/** A filled arc never shrinks to nothing: at 0 it is a dot, as the panel's is. */
private const val MIN_SWEEP = 0.5f

private const val NONE = "—"
