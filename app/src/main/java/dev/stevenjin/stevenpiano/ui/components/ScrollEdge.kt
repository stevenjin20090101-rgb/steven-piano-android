// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.ui.LocalAppFrame
import dev.stevenjin.stevenpiano.ui.LocalFloatingPadding
import dev.stevenjin.stevenpiano.ui.theme.GlassTokens
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlin.math.abs

/**
 * The scroll-edge effect (DESIGN.md › v1.9) on a scrolling container that draws beneath the glass:
 * where its content meets a bar, the content fades into the glass over [GlassTokens.EdgeBand], from
 * clear to the surface at a sheet's opacity at the bar's edge (the glass's own band thickens the
 * frost inside that edge). The bars are the ones [LocalFloatingPadding] keeps the content clear of:
 * the pane's header at the top, while content is scrolled beneath it (at the resting scroll position
 * there is no band); the tab bar and the mini player at the bottom on phones, while there is more
 * beneath them; and the rail at the start on wider screens, over the content's own 16 dp margin
 * ([GlassTokens.RailBand]) and only where the container meets the rail. A band comes and goes in
 * 120 ms (a cut when motion is reduced); with transparency reduced there is none: solid bars and
 * their hairlines, as before. Drawn over the content in the surface colour, which over the app's
 * opaque surface is the content faded, and costs no layer of its own.
 */
@Composable
fun Modifier.scrollEdges(state: ScrollableState): Modifier {
    if (!glassAvailable()) return this
    val floating = LocalFloatingPadding.current
    val frame = LocalAppFrame.current
    val direction = LocalLayoutDirection.current
    val density = LocalDensity.current
    val surface = MaterialTheme.colorScheme.surface
    val reduced = rememberReducedMotion()
    val top = floating.calculateTopPadding()
    // The bar at the bottom is the tab bar's column on phones; wide frames have none (the rail is at the side).
    val bottom = if (frame.rail) 0.dp else floating.calculateBottomPadding()
    val rail = if (frame.rail) floating.calculateStartPadding(direction) else 0.dp
    val topShown = animateFloatAsState(
        if (top > 0.dp && state.canScrollBackward) 1f else 0f,
        if (reduced) snap() else tween(Motion.FastMs),
        label = "scroll edge, top",
    )
    val bottomShown = animateFloatAsState(
        if (bottom > 0.dp && state.canScrollForward) 1f else 0f,
        if (reduced) snap() else tween(Motion.FastMs),
        label = "scroll edge, bottom",
    )
    // Whether this container's start edge is the rail's edge (a pane beside the rail, not the one beyond it).
    var meetsRail by remember { mutableStateOf(false) }
    val railPx = with(density) { rail.toPx() }
    return this
        .then(
            if (rail > 0.dp) {
                Modifier.onGloballyPositioned { coordinates ->
                    meetsRail = if (direction == LayoutDirection.Ltr) {
                        abs(coordinates.positionInWindow().x - railPx) < RAIL_SLOP_PX
                    } else {
                        meetsRailRtl(coordinates, railPx)
                    }
                }
            } else {
                Modifier
            },
        )
        .drawWithContent {
            drawContent()
            val band = GlassTokens.EdgeBand.toPx()
            val edge = surface.copy(alpha = GlassTokens.EdgeFadeAlpha)
            val clear = surface.copy(alpha = 0f)
            val t = topShown.value
            if (t > 0f) {
                val y = top.toPx()
                drawRect(
                    Brush.verticalGradient(0f to edge.copy(alpha = edge.alpha * t), 1f to clear, startY = y, endY = y + band),
                    Offset(0f, y),
                    Size(size.width, band),
                )
            }
            val b = bottomShown.value
            if (b > 0f) {
                val y = size.height - bottom.toPx()
                drawRect(
                    Brush.verticalGradient(0f to clear, 1f to edge.copy(alpha = edge.alpha * b), startY = y - band, endY = y),
                    Offset(0f, y - band),
                    Size(size.width, band),
                )
            }
            if (meetsRail) {
                val w = GlassTokens.RailBand.toPx()
                if (layoutDirection == LayoutDirection.Ltr) {
                    drawRect(Brush.horizontalGradient(0f to edge, 1f to clear, startX = 0f, endX = w), Offset.Zero, Size(w, size.height))
                } else {
                    val x = size.width - w
                    drawRect(Brush.horizontalGradient(0f to clear, 1f to edge, startX = x, endX = size.width), Offset(x, 0f), Size(w, size.height))
                }
            }
        }
}

/** Right to left, the rail is at the window's right: whether this container's right edge meets it. */
private fun meetsRailRtl(coordinates: LayoutCoordinates, railPx: Float): Boolean {
    val root = generateSequence(coordinates) { it.parentLayoutCoordinates }.last()
    val right = coordinates.positionInWindow().x + coordinates.size.width
    return abs(root.size.width - right - railPx) < RAIL_SLOP_PX
}

/** How near, in pixels, a container's edge must be to the rail's to meet it (rounding apart). */
private const val RAIL_SLOP_PX = 1.5f
