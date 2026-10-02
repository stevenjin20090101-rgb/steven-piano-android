// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import android.graphics.Matrix
import android.graphics.SweepGradient
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.stevenjin.stevenpiano.ui.LocalIdleState
import dev.stevenjin.stevenpiano.ui.theme.AuraTokens
import dev.stevenjin.stevenpiano.ui.theme.LocalAuraStops
import dev.stevenjin.stevenpiano.ui.theme.rememberGlassAccessibility
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

/** The aura's three states: faint at rest, brighter when the box has focus, full and flowing while music is written. */
enum class AuraState(val alpha: Float, val turnMs: Int) {
    Rest(AuraTokens.RestAlpha, AuraTokens.RestTurnMs),
    Focused(AuraTokens.FocusedAlpha, AuraTokens.RestTurnMs),
    Working(AuraTokens.WorkingAlpha, AuraTokens.WorkingTurnMs),
}

/*
 * The aura (DESIGN.md › v1.12 — Studio as a tab), the one component that draws it: a rotating sweep of its four
 * stops (LocalAuraStops, ui/theme/Aura.kt). It turns only while the screen is resumed and in sight (not under
 * the resting screen); under reduced motion it is a still gradient; with high-contrast text or reduced
 * transparency it has no glow. It never stands behind text without the bar's own solid surface, and it is never
 * the only sign of status: the words and the hairline carry that.
 */

/** The aura's turn, in degrees: advanced a frame at a time while [running], read only where it is drawn. */
@Composable
fun rememberAuraAngle(state: AuraState): MutableFloatState {
    val angle = remember { mutableFloatStateOf(0f) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resting = LocalIdleState.current?.idle == true
    val running = !rememberReducedMotion() && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !resting
    if (running) {
        LaunchedEffect(state) {
            var last = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    angle.floatValue = (angle.floatValue + (now - last) / 1e6f * 360f / state.turnMs) % 360f
                    last = now
                }
            }
        }
    }
    return angle
}

/**
 * [content] (a rounded bar) in a 2 dp ring of the aura and its soft glow: [room] around it is left for the glow
 * (a blurred second pass on Android 12 and up, three fading strokes below). [shape] is the bar's.
 */
@Composable
fun AuraRing(state: AuraState, shape: Shape, modifier: Modifier = Modifier, room: Dp = AuraTokens.GlowRoom, content: @Composable BoxScope.() -> Unit) {
    val stops = LocalAuraStops.current
    val angle = rememberAuraAngle(state)
    val glass = rememberGlassAccessibility()
    val glow = !glass.reducedTransparency && !glass.increasedContrast
    Box(modifier) {
        if (glow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { renderEffect = BlurEffect(AuraTokens.Glow.toPx(), AuraTokens.Glow.toPx(), TileMode.Decal) }
                    .auraStroke(shape, stops, { angle.floatValue }, state.alpha * GLOW_ALPHA, AuraTokens.Glow, room),
            )
        } else if (glow) {
            for (i in 1..3) Box(Modifier.matchParentSize().auraStroke(shape, stops, { angle.floatValue }, state.alpha * GLOW_ALPHA / (i + 1), AuraTokens.Ring * (1 + i * 2), room))
        }
        Box(
            Modifier
                .padding(room)
                .drawWithCache {
                    val ring = sweep(stops, size.width / 2, size.height / 2)
                    val outline = shape.createOutline(size, layoutDirection, this)
                    onDrawWithContent {
                        drawContent()
                        ring.turn(angle.floatValue, size.width / 2, size.height / 2)
                        drawOutline(outline, ring.brush, alpha = state.alpha, style = Stroke(AuraTokens.Ring.toPx()))
                    }
                },
            content = content,
        )
    }
}

/** A hairline of the aura (the running card's top line): the stops along it, flowing while [state] works. */
@Composable
fun AuraHairline(state: AuraState, modifier: Modifier = Modifier) {
    val stops = LocalAuraStops.current
    val angle = rememberAuraAngle(state)
    Canvas(modifier.fillMaxWidth().height(AuraTokens.Ring)) {
        val shift = angle.floatValue / 360f * size.width
        val colors = stops + stops.first()
        drawRect(Brush.horizontalGradient(colors, startX = -shift, endX = size.width * 2 - shift, tileMode = TileMode.Repeated), alpha = state.alpha)
    }
}

/** The 6 dp dot on the Studio tab's glyph while a job runs: the aura, never the live red. */
@Composable
fun AuraDot(modifier: Modifier = Modifier) {
    val stops = LocalAuraStops.current
    val angle = rememberAuraAngle(AuraState.Working)
    Canvas(modifier.size(AuraTokens.Dot)) {
        val ring = sweep(stops, size.width / 2, size.height / 2)
        ring.turn(angle.floatValue, size.width / 2, size.height / 2)
        drawCircle(ring.brush)
    }
}

/** A sweep gradient of [stops] about a centre, and its turn. */
private class Sweep(val shader: SweepGradient, val brush: ShaderBrush) {
    private val matrix = Matrix()

    fun turn(degrees: Float, cx: Float, cy: Float) {
        matrix.setRotate(degrees, cx, cy)
        shader.setLocalMatrix(matrix)
    }
}

private fun sweep(stops: List<Color>, cx: Float, cy: Float): Sweep {
    val colors = (stops + stops.first()).map { it.toArgb() }.toIntArray()
    val shader = SweepGradient(cx, cy, colors, null)
    return Sweep(shader, ShaderBrush(shader))
}

/** The glow's stroke, [width] wide, around the box [room] in from this one's edges. */
private fun Modifier.auraStroke(shape: Shape, stops: List<Color>, angle: () -> Float, alpha: Float, width: Dp, room: Dp): Modifier = drawWithCache {
    val inset = room.toPx()
    val inner = androidx.compose.ui.geometry.Size((size.width - inset * 2).coerceAtLeast(0f), (size.height - inset * 2).coerceAtLeast(0f))
    val ring = sweep(stops, size.width / 2, size.height / 2)
    val outline = shape.createOutline(inner, layoutDirection, this)
    val stroke = Stroke(width.toPx())
    onDrawBehind {
        ring.turn(angle(), size.width / 2, size.height / 2)
        translateBy(inset) { drawOutline(outline, ring.brush, alpha = alpha, style = stroke) }
    }
}

private inline fun DrawScope.translateBy(by: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(by, by)
    try {
        block()
    } finally {
        drawContext.transform.translate(-by, -by)
    }
}

private const val GLOW_ALPHA = 0.55f
