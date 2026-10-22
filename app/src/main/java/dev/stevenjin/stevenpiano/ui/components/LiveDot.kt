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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import dev.stevenjin.stevenpiano.ui.theme.LocalLive
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

/** In sp, so the dot keeps its size next to the word it sits beside at any font scale. */
private val DotSize = 8.sp
private const val BREATH_LOW = 0.55f

/** The breath's period, 100 % to 55 % and back: status while notes go to the piano, not a transition (Motion's durations). */
private const val BREATH_MS = 2_000
private val Sine = Easing { x -> (1f - cos(PI.toFloat() * x)) / 2f }

/**
 * What a link's dot says (DESIGN.md › v1.25 — M59), so red keeps its one meaning, notes going to the piano: [NotConnected]
 * a hollow ring; [Connected], and silent, a dot filled in the content colour; [Sending] the live red, breathing.
 */
enum class LinkDot {
    NotConnected,
    Connected,
    Sending,
    ;

    companion object {
        /**
         * The dot of a link that is [connected] while [sending] notes to the piano (a piece playing to it, the state the "Sent
         * to piano" capsule reads): red only then, never for a connection alone.
         */
        fun of(connected: Boolean, sending: Boolean): LinkDot = when {
            !connected -> NotConnected
            sending -> Sending
            else -> Connected
        }
    }
}

/**
 * The link's dot ([state]), and the app's only red: the only reader of [LocalLive]. A ring while not connected, filled
 * in the content colour once connected, the live red while notes go to the piano; each change eases over 320 ms (a cut
 * when motion is reduced). While red and [breathing] the dot breathes 100 % to 55 % over 2 s, unless motion is reduced.
 * Always pair it with a word: the dot never carries meaning alone. [size] is its diameter where it stands on no text
 * (the rail's mark), else 8 sp. The breath is the dot's own layer's alpha, so breathing redraws nothing else (drawn in
 * the canvas it re-recorded the whole screen every frame, and with it re-blurred the glass over the screen).
 */
@Composable
fun LiveDot(state: LinkDot, modifier: Modifier = Modifier, breathing: Boolean = true, size: Dp = Dp.Unspecified) {
    val red = LocalLive.current
    val ink = MaterialTheme.colorScheme.onSurface
    val ring = MaterialTheme.colorScheme.onSurfaceVariant
    val reduced = rememberReducedMotion()
    val filled = remember { Animatable(if (state == LinkDot.NotConnected) 0f else 1f) }
    val live = remember { Animatable(if (state == LinkDot.Sending) 1f else 0f) }
    LaunchedEffect(state, reduced) {
        val spec = Motion.timed<Float>(Motion.EmphasisedMs, reduced, Motion.Enter)
        launch { filled.animateTo(if (state == LinkDot.NotConnected) 0f else 1f, spec) }
        live.animateTo(if (state == LinkDot.Sending) 1f else 0f, spec)
    }
    val breath = if (state == LinkDot.Sending && breathing && !reduced) {
        rememberInfiniteTransition(label = "live")
            .animateFloat(1f, BREATH_LOW, infiniteRepeatable(tween(BREATH_MS / 2, easing = Sine), RepeatMode.Reverse), label = "breath")
    } else {
        null
    }
    val diameter = if (size.isSpecified) size else with(LocalDensity.current) { DotSize.toDp() }
    Canvas(
        modifier
            .size(diameter)
            .graphicsLayer { alpha = breath?.value ?: 1f },
    ) {
        val on = filled.value
        val stroke = 1.dp.toPx()
        if (on < 1f) drawCircle(ring, radius = this.size.minDimension / 2 - stroke / 2, alpha = 1f - on, style = Stroke(stroke))
        if (on > 0f) drawCircle(lerp(ink, red, live.value), alpha = on)
    }
}
