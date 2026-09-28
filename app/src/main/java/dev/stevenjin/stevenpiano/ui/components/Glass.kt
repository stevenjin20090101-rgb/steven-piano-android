// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import dev.chrisbanes.haze.HazeState as LibraryHazeState
import dev.chrisbanes.haze.hazeSource as libraryHazeSource

/*
 * The glass of the floating controls (DESIGN.md › v1.5 — M16), and the only file that names the
 * blur library (Haze 1.7.2, Apache-2.0). A source ([hazeSource]) records what it draws; a glass
 * surface over it ([GlassSurface]) draws that content blurred, under the surface colour at
 * [GlassTokens.ContainerAlpha], with a hairline edge and a faint specular line. A surface is
 * always a sibling of its source, never inside it (the blur would feed back). There are two
 * sources: the navigation content, under the tab bar, the rail and the mini player; and the note
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
 * Whether the glass draws as the solid surface, today's look ([dev.stevenjin.stevenpiano.ui.theme.rememberReducedTransparency]):
 * the nav host provides it once for the whole app.
 */
val LocalReducedTransparency = staticCompositionLocalOf { false }

/**
 * True inside a [GlassSurface] that is blurring (false on its solid fallback): what sits on glass
 * takes the content colour for text and never the tertiary grey (GlassTokensTest), while the solid
 * surface keeps today's colours.
 */
val LocalOnGlass = staticCompositionLocalOf { false }

/** Where a surface's hairline and specular line run: the edge that faces the content. */
enum class GlassEdge {
    /** Bars at the bottom (the tab bar, the transport): their top edge. */
    Top,

    /** The rail: its end edge. */
    End,

    /** A floating shape (a circle): all the way round, the specular line along its upper half. */
    Outline,
}

/**
 * A glass surface in [shape] over [source]: the content beneath, blurred [GlassTokens.Blur], under
 * the surface colour at [containerAlpha] ([GlassTokens.LensAlpha] for the 72 dp play circle only),
 * no tint, no noise; a 1 dp edge in the hairline token and a 1 dp specular line inside it
 * ([edge]). Where the glass cannot blur (below API 31), while transparency is reduced
 * ([LocalReducedTransparency]), or before its source has drawn anything, it is the solid surface
 * with the hairline edge: today's look. [LocalOnGlass] tells [content] which: text on glass is the
 * content colour (primary), which clears 7:1 over the worst backdrop (GlassTokensTest).
 *
 * Cost: the blur is drawn only inside the surface (clipped to its shape; it reads the content a
 * blur's reach beyond its edges so rows slide in smoothly) and is worked out on a copy of that
 * content at a third of its resolution ([HazeInputScale.Auto]), a ninth of the pixels, which a
 * 24 dp blur hides. It is redrawn whenever the content beneath changes, every frame while the roll
 * moves beneath the transport.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    source: HazeState = LocalHazeState.current,
    edge: GlassEdge = if (shape == RectangleShape) GlassEdge.Top else GlassEdge.Outline,
    containerAlpha: Float = GlassTokens.ContainerAlpha,
    content: @Composable BoxScope.() -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    val hairline = LocalHairline.current
    val specular = LocalGlassEdge.current
    val glass = GlassCanBlur && !LocalReducedTransparency.current && source.areas.isNotEmpty()
    val style = remember(surface, containerAlpha) {
        HazeStyle(
            backgroundColor = surface,
            tints = listOf(HazeTint(surface.copy(alpha = containerAlpha))),
            blurRadius = GlassTokens.Blur,
            noiseFactor = 0f,
        )
    }
    Box(
        modifier
            .clip(shape)
            .glassEdge(shape, edge, hairline, if (glass) specular else null)
            .then(if (glass) Modifier.hazeEffect(source, style) { inputScale = HazeInputScale.Auto } else Modifier.background(surface)),
    ) {
        CompositionLocalProvider(LocalOnGlass provides glass) { content() }
    }
}

/**
 * The surface's edge, over everything it holds: a 1 dp hairline, and inside it a 1 dp [specular]
 * line (glass only). [shape] clips the surface, so a stroke twice as wide as the line shows exactly
 * the line inside the shape.
 */
private fun Modifier.glassEdge(shape: Shape, edge: GlassEdge, hairline: Color, specular: Color?): Modifier = drawWithCache {
    val line = GlassTokens.Edge.toPx()
    val outline = if (edge == GlassEdge.Outline) shape.createOutline(size, layoutDirection, this) else null
    val sheen = specular?.let { Brush.verticalGradient(0f to it, SPECULAR_REACH to it.copy(alpha = 0f)) }
    val endOnRight = layoutDirection == LayoutDirection.Ltr
    onDrawWithContent {
        drawContent()
        when (edge) {
            GlassEdge.Top -> {
                drawRect(hairline, Offset.Zero, Size(size.width, line))
                if (specular != null) drawRect(specular, Offset(0f, line), Size(size.width, line))
            }
            GlassEdge.End -> {
                val x = if (endOnRight) size.width - line else 0f
                drawRect(hairline, Offset(x, 0f), Size(line, size.height))
                if (specular != null) drawRect(specular, Offset(if (endOnRight) x - line else line, 0f), Size(line, size.height))
            }
            GlassEdge.Outline -> if (outline != null) {
                if (sheen != null) drawOutline(outline, sheen, style = Stroke(width = line * 4))
                drawOutline(outline, hairline, style = Stroke(width = line * 2))
            }
        }
    }
}

/** A round surface's specular line fades out this far down it. */
private const val SPECULAR_REACH = 0.5f
