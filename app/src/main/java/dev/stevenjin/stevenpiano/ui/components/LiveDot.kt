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
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.stevenjin.stevenpiano.ui.theme.LocalLive
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.cos

/** In sp, so the dot keeps its size next to the word it sits beside at any font scale. */
private val DotSize = 8.sp
private const val BREATH_LOW = 0.55f
private val Sine = Easing { x -> (1f - cos(PI.toFloat() * x)) / 2f }

/**
 * The app's only red, and the only reader of [LocalLive]. [live] (the piano is connected) shows
 * a filled dot, faded in over 320 ms; otherwise a hollow ring. While [breathing] (the piano is
 * playing) the dot breathes 100 % to 55 % over 2 s, unless motion is reduced. Always pair it
 * with a word: the dot never carries meaning alone. The breath is the dot's own layer's alpha, so
 * breathing redraws nothing else (drawn in the canvas it re-recorded the whole screen every frame,
 * and with it re-blurred the glass over the screen).
 */
@Composable
fun LiveDot(live: Boolean, breathing: Boolean, modifier: Modifier = Modifier) {
    val red = LocalLive.current
    val ring = MaterialTheme.colorScheme.onSurfaceVariant
    val reduced = rememberReducedMotion()
    val shown = remember { Animatable(if (live) 1f else 0f) }
    LaunchedEffect(live, reduced) {
        val target = if (live) 1f else 0f
        if (reduced) shown.snapTo(target) else shown.animateTo(target, tween(Motion.RollStartMs, easing = Motion.EaseOut))
    }
    val breath = if (live && breathing && !reduced) {
        rememberInfiniteTransition(label = "live")
            .animateFloat(1f, BREATH_LOW, infiniteRepeatable(tween(Motion.LivePulseMs / 2, easing = Sine), RepeatMode.Reverse), label = "breath")
    } else {
        null
    }
    Canvas(
        modifier
            .size(with(LocalDensity.current) { DotSize.toDp() })
            .graphicsLayer { alpha = breath?.value ?: 1f },
    ) {
        val on = shown.value
        val stroke = 1.dp.toPx()
        if (on < 1f) drawCircle(ring, radius = size.minDimension / 2 - stroke / 2, alpha = 1f - on, style = Stroke(stroke))
        if (on > 0f) drawCircle(red, alpha = on)
    }
}
