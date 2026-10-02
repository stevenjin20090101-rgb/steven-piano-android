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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.stevenjin.stevenpiano.ui.LocalIdleState
import dev.stevenjin.stevenpiano.ui.theme.AuraTokens
import dev.stevenjin.stevenpiano.ui.theme.LocalAuraStops
import dev.stevenjin.stevenpiano.ui.theme.rememberGlassAccessibility
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.cos

/**
 * The aura's three states (DESIGN.md › v1.13.1 — the stage): at rest 65 % turning once in 8 s; brighter and quicker
 * when the box has focus; full, a turn in 2.5 s and its glow breathing while music is written.
 */
enum class AuraState(val alpha: Float, val turnMs: Int, val breathes: Boolean) {
    Rest(AuraTokens.RestAlpha, AuraTokens.RestTurnMs, false),
    Focused(AuraTokens.FocusedAlpha, AuraTokens.FocusedTurnMs, false),
    Working(AuraTokens.WorkingAlpha, AuraTokens.WorkingTurnMs, true),
}

/*
 * The aura (DESIGN.md › v1.12 — Studio as a tab, › v1.13.1 — the stage), the one component that draws it: a rotating
 * sweep of its four stops (LocalAuraStops, ui/theme/Aura.kt) and its glow. It moves only while the screen is resumed
 * and in sight (not under the resting screen), on one frame loop per drawing; under reduced motion it is a still
 * gradient with its glow; with high-contrast text or reduced transparency it is the ring alone. It never stands
 * behind text without a solid surface (the bar's own, the understood line's, the card's), and it is never the only
 * sign of status: the words and the hairline carry that.
 */

/**
 * The aura's motion, read only where it is drawn: its turn in degrees ([angle]) and its breath ([breath]: 0 with the
 * glow at [AuraTokens.Glow], 1 at [AuraTokens.GlowBreath]).
 */
@Stable
class AuraMotion internal constructor() {
    internal val angle = mutableFloatStateOf(0f)
    internal val breath = mutableFloatStateOf(0f)
    private var phaseMs = 0f

    /** [ms] later in [state]: that share of a turn, and while it works that share of a breath; after working the glow settles back. */
    internal fun advance(state: AuraState, ms: Float) {
        angle.floatValue = (angle.floatValue + ms * 360f / state.turnMs) % 360f
        if (state.breathes) {
            phaseMs = (phaseMs + ms) % AuraTokens.BreathMs
            breath.floatValue = 0.5f - 0.5f * cos(2f * PI.toFloat() * phaseMs / AuraTokens.BreathMs)
        } else {
            phaseMs = 0f
            if (breath.floatValue > 0f) breath.floatValue = (breath.floatValue - ms / SETTLE_MS).coerceAtLeast(0f)
        }
    }

    /** How far the glow reaches now. */
    internal fun reach(): Dp = lerp(AuraTokens.Glow, AuraTokens.GlowBreath, breath.floatValue)
}

/** The aura's motion in [state], advanced a frame at a time while the screen is resumed and in sight and motion isn't reduced. */
@Composable
fun rememberAuraMotion(state: AuraState): AuraMotion {
    val motion = remember { AuraMotion() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resting = LocalIdleState.current?.idle == true
    val running = !rememberReducedMotion() && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !resting
    if (running) {
        LaunchedEffect(motion, state) {
            var last = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    motion.advance(state, (now - last) / 1e6f)
                    last = now
                }
            }
        }
    }
    return motion
}

/**
 * [content] (a rounded bar on a solid surface) in a 2.5 dp ring of the aura, and its glow round it: a wide stroke of
 * the sweep, blurred (Android 12 and up) or three fading strokes (below), on a layer reaching [GLOW_ROOM] beyond the
 * bar on every side without taking that room from the layout. [shape] is the bar's. The glow is drawn behind the bar,
 * whose own surface hides it inside.
 */
@Composable
fun AuraRing(state: AuraState, shape: Shape, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val stops = LocalAuraStops.current
    val motion = rememberAuraMotion(state)
    val glass = rememberGlassAccessibility()
    val glow = !glass.reducedTransparency && !glass.increasedContrast
    Box(modifier) {
        if (glow) Box(Modifier.matchParentSize().auraGlow(state, shape, stops, motion))
        Box(
            Modifier.drawWithCache {
                val ring = sweep(stops, size.width / 2, size.height / 2)
                val outline = shape.createOutline(size, layoutDirection, this)
                val stroke = Stroke(AuraTokens.Ring.toPx())
                onDrawWithContent {
                    drawContent()
                    ring.turn(motion.angle.floatValue, size.width / 2, size.height / 2)
                    drawOutline(outline, ring.brush, alpha = state.alpha, style = stroke)
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
    val motion = rememberAuraMotion(state)
    Canvas(modifier.fillMaxWidth().height(AuraTokens.Ring)) {
        val shift = motion.angle.floatValue / 360f * size.width
        val colors = stops + stops.first()
        drawRect(Brush.horizontalGradient(colors, startX = -shift, endX = size.width * 2 - shift, tileMode = TileMode.Repeated), alpha = state.alpha)
    }
}

/** The 6 dp dot on the Studio tab's glyph while a job runs: the aura, never the live red. */
@Composable
fun AuraDot(modifier: Modifier = Modifier) {
    val stops = LocalAuraStops.current
    val motion = rememberAuraMotion(AuraState.Working)
    Canvas(modifier.size(AuraTokens.Dot)) {
        val ring = sweep(stops, size.width / 2, size.height / 2)
        ring.turn(motion.angle.floatValue, size.width / 2, size.height / 2)
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

/**
 * The glow, on a layer [GLOW_ROOM] larger than the bar on every side (laid out at the bar's size, drawn beyond it):
 * blurred by [BLUR_SHARE] of its reach on Android 12 and up, three fading strokes below.
 */
private fun Modifier.auraGlow(state: AuraState, shape: Shape, stops: List<Color>, motion: AuraMotion): Modifier {
    val beyond = this.layout { measurable, constraints ->
        val extra = GLOW_ROOM.roundToPx()
        val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth + extra * 2, constraints.maxHeight + extra * 2))
        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-extra, -extra) }
    }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        beyond
            .graphicsLayer {
                val radius = motion.reach().toPx() * BLUR_SHARE
                renderEffect = BlurEffect(radius, radius, TileMode.Decal)
            }
            .glowStrokes(shape, stops, motion, listOf(STROKE_SHARE to state.alpha * GLOW_ALPHA))
    } else {
        beyond.glowStrokes(shape, stops, motion, (1..3).map { i -> 2f * i / 3 to state.alpha * FADING_ALPHA })
    }
}

/** [strokes] (each a width, as a share of the glow's reach, and an alpha) of the sweep round [shape], [GLOW_ROOM] in from the layer's edges. */
private fun Modifier.glowStrokes(shape: Shape, stops: List<Color>, motion: AuraMotion, strokes: List<Pair<Float, Float>>): Modifier = drawWithCache {
    val room = GLOW_ROOM.toPx()
    val inner = Size((size.width - room * 2).coerceAtLeast(0f), (size.height - room * 2).coerceAtLeast(0f))
    val ring = sweep(stops, size.width / 2, size.height / 2)
    val outline = shape.createOutline(inner, layoutDirection, this)
    onDrawBehind {
        ring.turn(motion.angle.floatValue, size.width / 2, size.height / 2)
        val reach = motion.reach().toPx()
        translate(room, room) {
            for ((share, alpha) in strokes) drawOutline(outline, ring.brush, alpha = alpha, style = Stroke(reach * share))
        }
    }
}

/** How far beyond the bar the glow's layer reaches: past the breath's widest, blur and all. */
private val GLOW_ROOM = AuraTokens.GlowBreath * 1.5f

/** The blurred glow: a stroke half its reach wide, blurred by six tenths of it, at 80 % of the ring's alpha (it fades out about its reach away). */
private const val STROKE_SHARE = 0.5f
private const val BLUR_SHARE = 0.6f
private const val GLOW_ALPHA = 0.8f

/** Below Android 12, each of the three strokes at a fifth of the ring's alpha: 60 % near the bar, 20 % at its reach. */
private const val FADING_ALPHA = 0.2f

/** After working, the glow settles back to its rest in this long. */
private const val SETTLE_MS = 320f
