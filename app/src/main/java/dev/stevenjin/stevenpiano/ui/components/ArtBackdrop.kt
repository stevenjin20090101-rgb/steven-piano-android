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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.stevenjin.stevenpiano.data.art.ArtPalette
import dev.stevenjin.stevenpiano.ui.LocalIdleState
import dev.stevenjin.stevenpiano.ui.theme.Backdrop
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.discColour
import dev.stevenjin.stevenpiano.ui.theme.rememberGlassAccessibility
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The album-colour backdrop (DESIGN.md › v1.15 — M41), the one component that draws it: the playing piece's art
 * colours ([ArtPalette], ui/theme/Backdrop.kt) as four soft discs drifting slowly behind the player on Now playing, the
 * now-playing panel and the resting screen, under a veil of the surface so the words read. No blur: a radial gradient
 * fading to nothing is the soft shape (the blur effect stays the aura's alone). It moves on a loop of its own, in the aura's
 * shape, only while a piece plays, the screen is resumed and in sight and motion is not reduced; still otherwise. Under
 * high contrast, reduced transparency, artwork in black and white or Album colours off there is none ([rememberBackdrop]).
 */

/**
 * The palette the backdrop shows for the piece playing ([pieceId], [composerKey]), or null where none shows: Album
 * colours off ([on]), nothing loaded, artwork in black and white, high-contrast text or reduced transparency, or art
 * without a colour of its own (a grey portrait, a roll card). Non-null is the screens' `backdropShown`.
 */
@Composable
fun rememberBackdrop(pieceId: Long?, composerKey: String?, on: Boolean): ArtPalette? {
    val glass = rememberGlassAccessibility()
    val wanted = on && !LocalArtworkMonochrome.current && !glass.increasedContrast && !glass.reducedTransparency
    return if (wanted && pieceId != null) rememberArtPalette(pieceId, composerKey.orEmpty()) else null
}

/**
 * Over a shown backdrop ([shown]), secondary and tertiary text take the primary colour, as on a bar's glass: through the
 * veil only the primary colour keeps 4.5:1 over every hue (BackdropContrastTest). Otherwise today's colours. The same
 * composition either way, so nothing beneath loses its state when the backdrop comes or goes.
 */
@Composable
fun OnBackdrop(shown: Boolean, content: @Composable () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(
        LocalTertiary provides if (shown) ink else LocalTertiary.current,
        LocalSecondaryText provides if (shown) ink else LocalSecondaryText.current,
        content = content,
    )
}

/** Whether the surface here is ink (or the resting screen's black): the discs' lightness and the veil follow it. */
@Composable
@ReadOnlyComposable
fun backdropDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/**
 * The backdrop in [modifier]'s box (the pane's, `matchParentSize`), clipped to it: [palette]'s four discs, each
 * [Backdrop.Radius] of the shorter side, fading from its colour to nothing, on its own slow path (one of
 * [Backdrop.PeriodsMs]), centred in the lower three quarters; then the surface at [veil] over them all, or with
 * [veilArea] over that rect alone, feathered at its inner edges (the resting screen's black canvas, behind the words).
 * One draw node: the brushes are built once per size and palette, and a frame only moves them (no allocation, no
 * recomposition). A new palette cross-fades over [fadeMs] (a cut under reduced motion); none fades away.
 *
 * It moves while [playing], the screen is resumed and motion is not reduced, and, unless [resting], while the app is
 * not idle (under the resting screen); the resting screen passes `playing && resting`, so it holds still while it fades.
 */
@Composable
fun ArtBackdrop(
    palette: ArtPalette?,
    playing: Boolean,
    modifier: Modifier = Modifier,
    veil: Float = Backdrop.veil(backdropDark()),
    resting: Boolean = false,
    fadeMs: Int = Motion.SlowMs,
    veilArea: State<Rect>? = null,
) {
    val reduced = rememberReducedMotion()
    val dark = backdropDark()
    val surface = MaterialTheme.colorScheme.surface
    val fade = remember { BackdropFade(palette) }
    LaunchedEffect(palette) {
        val spec: AnimationSpec<Float> = when {
            fadeMs <= Motion.SlowMs -> Motion.timed<Float>(fadeMs, reduced)
            reduced -> snap()
            else -> tween(fadeMs, easing = Motion.Standard)   // the resting screen's own pace (RestingMotion.PIECE_MS)
        }
        fade.to(palette, spec)
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
                val radius = Backdrop.Radius * size.minDimension
                val shown = discs(fade.shown, radius, dark)
                val leaving = discs(fade.leaving, radius, dark)
                val cover = surface.copy(alpha = veil)
                val clear = surface.copy(alpha = 0f)
                val feather = FEATHER.toPx()
                val before = Brush.horizontalGradient(listOf(clear, cover), startX = 0f, endX = feather)
                val after = Brush.horizontalGradient(listOf(cover, clear), startX = 0f, endX = feather)
                val above = Brush.verticalGradient(listOf(clear, cover), startY = 0f, endY = feather)
                onDrawBehind {
                    if (shown == null && leaving == null) return@onDrawBehind
                    val progress = fade.progress.value
                    if (leaving != null && progress < 1f) drawDiscs(leaving, radius, motion, 1f - progress)
                    if (shown != null && progress > 0f) drawDiscs(shown, radius, motion, progress)
                    val area = veilArea?.value
                    if (area == null) {
                        drawRect(cover)
                    } else if (!area.isEmpty) {
                        drawRect(cover, area.topLeft, area.size)
                        if (area.left > 0f) translate(area.left - feather, area.top) { drawRect(before, size = Size(feather, area.height)) }
                        if (area.right < size.width) translate(area.right, area.top) { drawRect(after, size = Size(feather, area.height)) }
                        if (area.top > 0f) translate(area.left, area.top - feather) { drawRect(above, size = Size(area.width, feather)) }
                    }
                }
            },
    )
}

/** The palette shown and the one fading out, and how far the change has come (1: done). */
@Stable
private class BackdropFade(initial: ArtPalette?) {
    var shown by mutableStateOf(initial)
    var leaving by mutableStateOf<ArtPalette?>(null)
    val progress = Animatable(1f)

    /** From what shows now to [next] by [spec]; interrupted, the more visible of the two goes on fading out. */
    suspend fun to(next: ArtPalette?, spec: AnimationSpec<Float>) {
        if (next == shown) return
        leaving = if (progress.value >= 0.5f) shown else leaving
        shown = next
        progress.snapTo(0f)
        progress.animateTo(1f, spec)
        leaving = null
    }
}

/** Where each disc is on its path: four turns (0 until 1), read only where they are drawn. */
@Stable
private class BackdropMotion {
    private val phases = Array(DISCS) { mutableFloatStateOf(0f) }

    fun phase(disc: Int): Float = phases[disc].floatValue

    /** [ms] later: each disc that share of its own period further round. */
    fun advance(ms: Float) {
        for (disc in 0 until DISCS) phases[disc].floatValue = (phases[disc].floatValue + ms / Backdrop.PeriodsMs[disc]) % 1f
    }
}

/** [palette]'s four discs as brushes about the origin, [radius] wide: the colour at the centre, nothing at the edge. */
private fun discs(palette: ArtPalette?, radius: Float, dark: Boolean): Array<Brush>? = palette?.let { p ->
    Array(DISCS) { disc ->
        val colour = discColour(p.hues[disc], p.saturations[disc], dark)
        Brush.radialGradient(
            *FALLOFF.map { (at, alpha) -> at to colour.copy(alpha = alpha) }.toTypedArray(),
            center = Offset.Zero,
            radius = radius.coerceAtLeast(1f),
        )
    }
}

/** The discs at [alpha], each where its turn puts it: an ellipse about its place, a quarter of the size across. */
private fun DrawScope.drawDiscs(brushes: Array<Brush>, radius: Float, motion: BackdropMotion, alpha: Float) {
    for (disc in 0 until DISCS) {
        val turn = 2f * PI.toFloat() * (motion.phase(disc) + START[disc])
        val x = size.width * (PLACE_X[disc] + SWAY_X * DIRECTION[disc] * sin(turn))
        val y = size.height * (PLACE_Y[disc] + SWAY_Y * cos(turn))
        translate(x, y) { drawCircle(brushes[disc], radius, Offset.Zero, alpha) }
    }
}

private const val DISCS = 4

/** Each disc's place, as shares of the width and the height: spread across, in the lower three quarters. */
private val PLACE_X = floatArrayOf(0.30f, 0.70f, 0.45f, 0.60f)
private val PLACE_Y = floatArrayOf(0.50f, 0.58f, 0.66f, 0.62f)

/** How far a disc sways from its place, as shares of the width and the height: about a quarter. */
private const val SWAY_X = 0.25f
private const val SWAY_Y = 0.22f

/** Where on its path each disc starts, and which way it goes round. */
private val START = floatArrayOf(0f, 0.25f, 0.5f, 0.75f)
private val DIRECTION = floatArrayOf(1f, -1f, 1f, -1f)

/** A disc's soft edge: its colour's alpha from the centre (0) to its rim (1), a bell rather than a cone. */
private val FALLOFF = listOf(0f to 1f, 0.3f to 0.82f, 0.6f to 0.42f, 0.85f to 0.12f, 1f to 0f)

/** The black canvas's words veil fades in over this much before its edge. */
private val FEATHER = 48.dp
