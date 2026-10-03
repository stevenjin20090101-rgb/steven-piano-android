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
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.stevenjin.stevenpiano.data.art.BackdropPicture
import dev.stevenjin.stevenpiano.ui.LocalIdleState
import dev.stevenjin.stevenpiano.ui.theme.Backdrop
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberGlassAccessibility
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlin.math.hypot
import kotlin.math.roundToInt

/*
 * The backdrop made of the cover (DESIGN.md › v1.18 — M49), the one component that draws it: the art the piece shows,
 * small and blurred ([BackdropPicture], data/art/BackdropRules.kt), three times over, each turning slowly about its
 * centre, under the black its brightest part needs for light words, edge to edge behind Now playing, the now-playing
 * panel and the resting screen. No blur effect: the picture is soft already (the blur effect stays the aura's alone).
 * It turns, in the aura's loop, only while a piece plays, the screen is resumed and in sight and motion is not reduced;
 * still otherwise. Under high contrast, reduced transparency, artwork in black and white or Album colours off there is
 * none ([rememberBackdrop]), and what stands on it is immersive only while it shows ([Immersive]).
 */

/**
 * The backdrop's picture for the piece playing ([pieceId], [composerKey]), or null where none shows: Album colours off
 * ([on]), nothing loaded, artwork in black and white, high-contrast text or reduced transparency, or art without a
 * picture (a roll card, a monogram). Non-null is the screens' immersive state.
 */
@Composable
fun rememberBackdrop(pieceId: Long?, composerKey: String?, on: Boolean): BackdropPicture? {
    val glass = rememberGlassAccessibility()
    val wanted = on && !LocalArtworkMonochrome.current && !glass.increasedContrast && !glass.reducedTransparency
    return if (wanted && pieceId != null) rememberBackdropPicture(pieceId, composerKey.orEmpty()) else null
}

/**
 * The backdrop in [modifier]'s box (the screen's or the pane's, `matchParentSize`), clipped to it: [picture] three times,
 * each a square [Backdrop.Scales] of the box's diagonal (the first covers the box at any turn) about its own centre (the
 * box's, moved [Backdrop.OffsetsX] and [Backdrop.OffsetsY]), at [Backdrop.Alphas], filtered, each turned as its own
 * loop has come (one turn in [Backdrop.PeriodsMs]); then black over them all from the picture's dim less
 * [Backdrop.TopLighter] at the top to its dim and [Backdrop.FootDeeper] at the foot, [deeper] more on the resting screen.
 * One draw node: the sizes and the black are worked out once per size and picture, and a frame only turns them (no
 * allocation, no recomposition). A new picture cross-fades in over the old, whole, over [fadeMs] (a cut under reduced
 * motion); none fades away.
 *
 * It turns while [playing], the screen is resumed and motion is not reduced, and, unless [resting], while the app is
 * not idle (under the resting screen); the resting screen passes `playing && resting`, so it holds still while it fades.
 */
@Composable
fun ArtBackdrop(
    picture: BackdropPicture?,
    playing: Boolean,
    modifier: Modifier = Modifier,
    resting: Boolean = false,
    fadeMs: Int = Motion.SlowMs,
    deeper: Float = 0f,
) {
    val reduced = rememberReducedMotion()
    val fade = remember { BackdropFade(picture) }
    LaunchedEffect(picture) {
        val spec: AnimationSpec<Float> = when {
            fadeMs <= Motion.SlowMs -> Motion.timed<Float>(fadeMs, reduced)
            reduced -> snap()
            else -> tween(fadeMs, easing = Motion.Standard)   // the resting screen's own pace (RestingMotion.PIECE_MS)
        }
        fade.to(picture, spec)
    }
    val motion = remember { BackdropMotion() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val idle = !resting && LocalIdleState.current?.idle == true
    val drawn = fade.shown != null || fade.leaving != null
    if (drawn && playing && !reduced && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !idle) {
        LaunchedEffect(motion) {
            var last = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    motion.advance((now - last) / 1e6f)
                    last = now
                }
            }
        }
    }
    Spacer(
        modifier
            .clipToBounds()
            .drawWithCache {
                val shown = fade.shown?.let { BackdropLayers(it, size, deeper) }
                val leaving = fade.leaving?.let { BackdropLayers(it, size, deeper) }
                val group = Paint()
                val bounds = size.toRect()
                onDrawBehind {
                    val progress = fade.progress.value
                    val under = leaving?.takeIf { progress < 1f }
                    val over = shown?.takeIf { progress > 0f }
                    when {
                        under != null && over != null -> {
                            under.draw(this, motion)
                            over.drawFaded(this, motion, progress, group, bounds)
                        }
                        under != null -> under.drawFaded(this, motion, 1f - progress, group, bounds)
                        over != null -> over.drawFaded(this, motion, progress, group, bounds)
                    }
                }
            },
    )
}

/** The picture shown and the one fading out, and how far the change has come (1: done). */
@Stable
private class BackdropFade(initial: BackdropPicture?) {
    var shown by mutableStateOf(initial)
    var leaving by mutableStateOf<BackdropPicture?>(null)
    val progress = Animatable(1f)

    /** From what shows now to [next] by [spec]; interrupted, the more visible of the two goes on fading out. */
    suspend fun to(next: BackdropPicture?, spec: AnimationSpec<Float>) {
        if (next == shown) return
        leaving = if (progress.value >= 0.5f) shown else leaving
        shown = next
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
        leaving = null
    }
}

/** How far each picture has turned: three turns (0 until 1), read only where they are drawn. */
@Stable
private class BackdropMotion {
    private val turns = Array(LAYERS) { mutableFloatStateOf(0f) }

    /** Picture [layer]'s angle now, in degrees: where it started, and its turn in its own direction. */
    fun degrees(layer: Int): Float = Backdrop.StartDegrees[layer] + FULL_TURN * Backdrop.Directions[layer] * turns[layer].floatValue

    /** [ms] later: each picture that share of its own period further round. */
    fun advance(ms: Float) {
        for (layer in 0 until LAYERS) turns[layer].floatValue = (turns[layer].floatValue + ms / Backdrop.PeriodsMs[layer]) % 1f
    }
}

/** One picture laid out for a box of [size]: each layer's square and centre, and the black over them. */
private class BackdropLayers(picture: BackdropPicture, size: Size, deeper: Float) {
    private val image: ImageBitmap = picture.bitmap.asImageBitmap()
    private val soft: ImageBitmap = picture.soft.asImageBitmap()
    private val source = IntSize(image.width, image.height)
    private val sides = IntArray(LAYERS)
    private val lefts = IntArray(LAYERS)
    private val tops = IntArray(LAYERS)
    private val centresX = FloatArray(LAYERS)
    private val centresY = FloatArray(LAYERS)
    private val shade: Brush

    init {
        val diagonal = hypot(size.width, size.height)
        for (layer in 0 until LAYERS) {
            val side = (Backdrop.Scales[layer] * diagonal).roundToInt().coerceAtLeast(1)
            val x = size.width * (0.5f + Backdrop.OffsetsX[layer])
            val y = size.height * (0.5f + Backdrop.OffsetsY[layer])
            sides[layer] = side
            lefts[layer] = (x - side / 2f).roundToInt()
            tops[layer] = (y - side / 2f).roundToInt()
            centresX[layer] = x
            centresY[layer] = y
        }
        val dim = picture.dim + deeper
        shade = Brush.verticalGradient(
            0f to Backdrop.Shade.copy(alpha = (dim - Backdrop.TopLighter).coerceIn(0f, 1f)),
            1f to Backdrop.Shade.copy(alpha = (dim + Backdrop.FootDeeper).coerceIn(0f, 1f)),
            startY = 0f,
            endY = size.height,
        )
    }

    /** The three layers as [motion] has turned them, then the black. */
    fun draw(scope: DrawScope, motion: BackdropMotion) = with(scope) {
        for (layer in 0 until LAYERS) {
            rotate(motion.degrees(layer), Offset(centresX[layer], centresY[layer])) {
                drawImage(
                    if (layer == LAYERS - 1) soft else image,   // the third does not cover the node: its edge fades
                    srcOffset = IntOffset.Zero,
                    srcSize = source,
                    dstOffset = IntOffset(lefts[layer], tops[layer]),
                    dstSize = IntSize(sides[layer], sides[layer]),
                    alpha = Backdrop.Alphas[layer],
                    filterQuality = FilterQuality.High,
                )
            }
        }
        drawRect(shade)
    }

    /** [draw], the whole of it at [alpha] (through [group], a layer [bounds] large, while it fades). */
    fun drawFaded(scope: DrawScope, motion: BackdropMotion, alpha: Float, group: Paint, bounds: Rect) {
        if (alpha >= 1f) {
            draw(scope, motion)
            return
        }
        val canvas = scope.drawContext.canvas
        group.alpha = alpha
        canvas.saveLayer(bounds, group)
        draw(scope, motion)
        canvas.restore()
    }
}

private const val LAYERS = 3
private const val FULL_TURN = 360f
