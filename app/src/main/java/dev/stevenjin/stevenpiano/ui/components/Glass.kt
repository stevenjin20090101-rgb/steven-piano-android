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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.LayoutDirection
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.stevenjin.stevenpiano.ui.theme.GlassCanBlur
import dev.stevenjin.stevenpiano.ui.theme.GlassTokens
import dev.stevenjin.stevenpiano.ui.theme.LocalGlassEdge
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlin.math.min
import dev.chrisbanes.haze.HazeState as LibraryHazeState
import dev.chrisbanes.haze.hazeSource as libraryHazeSource

/*
 * Liquid Glass across the functional layer (DESIGN.md › v1.9, after v1.5 — M16), and the only file
 * that names the blur library (Haze 1.7.2, Apache-2.0). A source ([hazeSource]) records what it
 * draws; a glass surface over it ([GlassSurface]) draws that content blurred, under the surface
 * colour at its fill ([GlassFill]), with a hairline edge and a faint specular line. A surface is
 * always a sibling of its source, never inside it (the blur would feed back). One source per glass
 * surface: the navigation content, under the tab bar, the rail, the mini player and every sheet,
 * menu and dialog; each pane's own content, under its header ([GlassHeaderPane]); and the note
 * panel on Now playing and in the now-playing panel, under the transport.
 */

/** Content a glass surface can blur: one [hazeSource] and the glass drawn over it. */
typealias HazeState = LibraryHazeState

/** A new, empty source, for one [hazeSource]. Blurring is on where the platform can (API 31 and up). */
@Composable
fun rememberHazeState(): HazeState = remember { LibraryHazeState(initialBlurEnabled = GlassCanBlur) }

/** Records this content for the glass drawn over it. The glass must not be a descendant of it. */
fun Modifier.hazeSource(state: HazeState): Modifier = libraryHazeSource(state)

/** The source a [GlassSurface] blurs unless it is given another: the navigation content, provided by the nav host. */
val LocalHazeState = staticCompositionLocalOf { LibraryHazeState(initialBlurEnabled = false) }

/**
 * Whether the glass draws as the solid surface, today's look ([dev.stevenjin.stevenpiano.ui.theme.rememberGlassAccessibility]):
 * the nav host provides it once for the whole app.
 */
val LocalReducedTransparency = staticCompositionLocalOf { false }

/**
 * True inside a [GlassSurface] that has the glass's look (false on its solid fallback): text on a
 * bar's glass is the content colour and never the tertiary grey (GlassTokensTest), while the solid
 * surface keeps today's colours.
 */
val LocalOnGlass = staticCompositionLocalOf { false }

/**
 * The colour secondary text takes where it stands ([secondaryText]): null for the palette's own
 * secondary grey; on a bar's glass the content colour (the secondary grey reads 3.1:1 over the
 * worst backdrop there); on a header, the one or the other as content passes beneath it.
 */
val LocalSecondaryText = staticCompositionLocalOf<Color?> { null }

/** Secondary text's colour here: the secondary grey, or where it sits on a bar's glass, the content colour ([LocalSecondaryText]). */
@Composable
fun secondaryText(): Color = LocalSecondaryText.current ?: MaterialTheme.colorScheme.onSurfaceVariant

/**
 * Whether glass can be drawn at all here: the device blurs (API 31 and up) and transparency is not
 * reduced. Where it cannot, controls that would float on glass keep today's solid places instead.
 */
@Composable
fun glassAvailable(): Boolean = GlassCanBlur && !LocalReducedTransparency.current

/** Where a surface's hairline and specular line run: the edge that faces the content. */
enum class GlassEdge {
    /** Bars at the bottom (the tab bar, the transport): their top edge. */
    Top,

    /** A header at the top of a pane: its bottom edge. */
    Bottom,

    /** The rail: its end edge. */
    End,

    /** A floating shape (a pill, a sheet, a menu, a dialog): all the way round, the specular line along its top. */
    Outline,
}

/** Regular glass's two fills (DESIGN.md › v1.9): the bars, and the larger, more opaque surfaces. */
enum class GlassFill(val alpha: Float) {
    /** The headers, the tab bar and the rail, the mini player, the transport, the Keys pills: 0.72. */
    Bar(GlassTokens.ContainerAlpha),

    /** Sheets, menus, popovers and dialogs: 0.86. */
    Sheet(GlassTokens.SheetAlpha),
}

/**
 * The transport yields to a scrolling list (DESIGN.md › v1.9): while this reads true, the glass that
 * yields pauses its blur ([GlassSurface]'s `paused`). The Library provides it to the now-playing panel
 * beside its list on two panes, true while the list scrolls, so the panel's transport draws its glass
 * without a blur for that while and the list's header is the one blur drawn on every frame.
 */
val LocalYieldBlur = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(false) }

/** Always shown: a surface's edge that is always there. */
val GlassShown: () -> Float = { 1f }

/** Never shown: no scroll-edge band. */
val GlassHidden: () -> Float = { 0f }

/**
 * A glass surface in [shape] over [source]: the content beneath, blurred [GlassTokens.Blur], under
 * the surface colour at [fill]'s opacity, no tint, no noise; a 1 dp edge in [outline] (the hairline
 * token, or the content colour at 0.4 when contrast is increased) and a 1 dp specular line inside it
 * ([edge]), shown as far as [edgeAlpha] says (a header at rest has none). Where the glass cannot blur
 * (below API 31), while transparency is reduced ([LocalReducedTransparency]), or before its source
 * has drawn anything, it is the solid surface ([solid]: the surface for bars, the elevated tone for
 * sheets, menus and dialogs) with its hairline: today's look. [LocalOnGlass] tells [content] which,
 * and on glass the tertiary grey gives way ([LocalTertiary]: the content colour on a bar, the
 * secondary grey on a sheet, which reads 4.5:1 or more there; GlassTokensTest).
 *
 * [band] is the scroll-edge effect inside the content-facing edge: while the glass blurs, the frost
 * thickens over the last [GlassTokens.EdgeBand] towards the edge, from the bar's fill to a sheet's.
 *
 * [paused]: the glass yields its blur to another's (the panel's transport while the list beside it
 * scrolls, [LocalYieldBlur]). While true the surface draws the glass's look without its blur (the
 * surface, as where nothing passes beneath), at once; when it turns false the blur comes back under a
 * veil of the surface that fades away over 120 ms (a cut when motion is reduced).
 *
 * Cost: the blur is worked out only inside the surface's own bounds, drawn clipped to its shape,
 * and from a copy of that content at a third of its resolution ([HazeInputScale.Auto]), a ninth of
 * the pixels, which a 24 dp blur hides; a header, as wide as its pane, and the sheets, menus and
 * dialogs, through whose 0.86 fill a seventh of the blur shows, from a copy at a fifth
 * ([GlassTokens.WideInputScale]), a twenty-fifth of the pixels. A blurring surface is redrawn whenever anything in its
 * source changes; so where nothing can pass beneath a surface ([blur] false: the rail, beside screens
 * that keep clear of it; the tab bar over the fixed layouts of Now playing and Keys; a header with
 * nothing scrolled beneath it; the Keys pills) it draws the glass's look without blurring: the
 * surface colour, which is exactly what the blur of the bare background under the tint comes to,
 * with the same edge, and [LocalOnGlass] still true.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    source: HazeState = LocalHazeState.current,
    edge: GlassEdge = if (shape == RectangleShape) GlassEdge.Top else GlassEdge.Outline,
    fill: GlassFill = GlassFill.Bar,
    blur: Boolean = true,
    paused: Boolean = false,
    solid: Color = MaterialTheme.colorScheme.surface,
    outline: Color = LocalHairline.current,
    edgeAlpha: () -> Float = GlassShown,
    band: () -> Float = GlassHidden,
    content: @Composable BoxScope.() -> Unit,
) {
    val look = rememberGlassLook(shape, source, edge, fill, blur, solid, outline, edgeAlpha, band, paused)
    Box(modifier.then(look.modifier)) {
        GlassText(look.glass, fill) { content() }
    }
}

/** What a glass surface draws ([modifier]: clip, edge, blur or fill, band), and whether it has the glass's look ([glass]). */
internal class GlassLook(val modifier: Modifier, val glass: Boolean)

/**
 * The glass's drawing as a modifier, for the containers that take one (a menu's column, [GlassSurface]'s box):
 * see [GlassSurface] for what each parameter does.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
internal fun rememberGlassLook(
    shape: Shape,
    source: HazeState,
    edge: GlassEdge,
    fill: GlassFill,
    blur: Boolean,
    solid: Color,
    outline: Color,
    edgeAlpha: () -> Float,
    band: () -> Float,
    paused: Boolean = false,
): GlassLook {
    val surface = MaterialTheme.colorScheme.surface
    val specular = LocalGlassEdge.current
    val available = glassAvailable()
    // A pause is a cut to the glass without its blur; the blur comes back under a veil that fades away.
    val reduced = rememberReducedMotion()
    val veil = remember { Animatable(if (paused) 1f else 0f) }
    LaunchedEffect(paused, reduced) {
        when {
            paused -> veil.snapTo(1f)
            reduced -> veil.snapTo(0f)
            veil.value > 0f -> veil.animateTo(0f, tween(Motion.FastMs))
        }
    }
    val veiled by remember { derivedStateOf { veil.value >= 1f } }
    // The glass's look (its edge, and what sits on it); blurring only where content can pass beneath.
    val hasSource = source.areas.isNotEmpty()
    val glass = available && (!blur || hasSource)
    val blurring = available && blur && hasSource && !veiled
    val style = remember(surface, fill) {
        HazeStyle(
            backgroundColor = surface,
            tints = listOf(HazeTint(surface.copy(alpha = fill.alpha))),
            blurRadius = GlassTokens.Blur,
            noiseFactor = 0f,
        )
    }
    val modifier = Modifier
        .clip(shape)
        .glassEdge(shape, edge, outline, if (glass) specular else null, edgeAlpha)
        .then(
            when {
                blurring -> Modifier
                    .hazeEffect(source, style) {
                        // A header (as wide as its pane) and the sheets, menus and dialogs blur a fifth-resolution copy.
                        inputScale = if (edge == GlassEdge.Bottom || fill == GlassFill.Sheet) {
                            HazeInputScale.Fixed(GlassTokens.WideInputScale)
                        } else {
                            HazeInputScale.Auto
                        }
                        expandLayerBounds = false
                    }
                    .glassBand(edge, surface, band)
                    .glassVeil(surface) { veil.value }
                glass -> Modifier.background(surface)
                else -> Modifier.background(solid)
            },
        )
    return GlassLook(modifier, glass)
}

/**
 * What sits on glass: [LocalOnGlass], and the tertiary grey given way to the content colour on a
 * bar or to the secondary grey on a sheet ([fill]); secondary text on a bar takes the content colour
 * too ([LocalSecondaryText]). Off glass, today's colours.
 */
@Composable
internal fun GlassText(glass: Boolean, fill: GlassFill, content: @Composable () -> Unit) {
    if (!glass) {
        CompositionLocalProvider(LocalOnGlass provides false, content = content)
        return
    }
    val ink = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    CompositionLocalProvider(
        LocalOnGlass provides true,
        LocalTertiary provides if (fill == GlassFill.Bar) ink else secondary,
        LocalSecondaryText provides if (fill == GlassFill.Bar) ink else null,
        content = content,
    )
}

/**
 * The scroll-edge band inside a blurring bar's content-facing [edge]: the surface laid over the
 * glass from nothing, [GlassTokens.EdgeBand] in, to [GlassTokens.BandAlpha] at the edge, as far as
 * [band] says (a header's shows only with content scrolled beneath it). Only bars at the top or the
 * bottom have one.
 */
private fun Modifier.glassBand(edge: GlassEdge, surface: Color, band: () -> Float): Modifier =
    if (edge != GlassEdge.Top && edge != GlassEdge.Bottom) {
        this
    } else {
        drawBehind {
            val shown = band()
            if (shown <= 0f) return@drawBehind
            val reach = min(GlassTokens.EdgeBand.toPx(), size.height)
            val thick = surface.copy(alpha = GlassTokens.BandAlpha * shown)
            val clear = surface.copy(alpha = 0f)
            if (edge == GlassEdge.Bottom) {
                val top = size.height - reach
                drawRect(Brush.verticalGradient(0f to clear, 1f to thick, startY = top, endY = size.height), Offset(0f, top), Size(size.width, reach))
            } else {
                drawRect(Brush.verticalGradient(0f to thick, 1f to clear, startY = 0f, endY = reach), Offset.Zero, Size(size.width, reach))
            }
        }
    }

/** The surface over a resuming blur, at [amount] (1 at the pause's end, fading to nothing). */
private fun Modifier.glassVeil(surface: Color, amount: () -> Float): Modifier = drawBehind {
    val shown = amount()
    if (shown > 0f) drawRect(surface.copy(alpha = shown.coerceAtMost(1f)))
}

/**
 * The surface's edge, over everything it holds: a 1 dp hairline, and inside it a 1 dp [specular]
 * line (glass only), as far as [shown] says. [shape] clips the surface, so a stroke twice as wide as
 * the line shows exactly the line inside the shape; a round shape's specular line fades out over
 * [GlassTokens.SpecularReach] (or half its height, if that is less).
 */
private fun Modifier.glassEdge(shape: Shape, edge: GlassEdge, hairline: Color, specular: Color?, shown: () -> Float): Modifier = drawWithCache {
    val line = GlassTokens.Edge.toPx()
    val outline = if (edge == GlassEdge.Outline) shape.createOutline(size, layoutDirection, this) else null
    val reach = min(GlassTokens.SpecularReach.toPx(), size.height / 2f)
    val sheen = specular?.let { Brush.verticalGradient(0f to it, 1f to it.copy(alpha = 0f), startY = 0f, endY = reach) }
    val endOnRight = layoutDirection == LayoutDirection.Ltr
    onDrawWithContent {
        drawContent()
        val alpha = shown().coerceIn(0f, 1f)
        if (alpha <= 0f) return@onDrawWithContent
        when (edge) {
            GlassEdge.Top -> {
                drawRect(hairline, Offset.Zero, Size(size.width, line), alpha = alpha)
                if (specular != null) drawRect(specular, Offset(0f, line), Size(size.width, line), alpha = alpha)
            }
            GlassEdge.Bottom -> {
                drawRect(hairline, Offset(0f, size.height - line), Size(size.width, line), alpha = alpha)
                if (specular != null) drawRect(specular, Offset(0f, size.height - line * 2), Size(size.width, line), alpha = alpha)
            }
            GlassEdge.End -> {
                val x = if (endOnRight) size.width - line else 0f
                drawRect(hairline, Offset(x, 0f), Size(line, size.height), alpha = alpha)
                if (specular != null) drawRect(specular, Offset(if (endOnRight) x - line else line, 0f), Size(line, size.height), alpha = alpha)
            }
            GlassEdge.Outline -> if (outline != null) {
                if (sheen != null) drawOutline(outline, sheen, alpha = alpha, style = Stroke(width = line * 4))
                drawOutline(outline, hairline, alpha = alpha, style = Stroke(width = line * 2))
            }
        }
    }
}
