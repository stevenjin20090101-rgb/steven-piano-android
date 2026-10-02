// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.piano.PianoFold
import dev.stevenjin.stevenpiano.ui.SettingsPage
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay

/**
 * A search result chosen (v1.13 — M31b): open [page] with [fold] unfolded, scroll it to [anchor] and give
 * that row one brief highlight. [id] tells two choices of the same row apart.
 */
@Immutable
data class Jump(val page: SettingsPage, val anchor: String?, val fold: PianoFold?, val id: Long)

/**
 * A page's anchors: where each row search can find sits in the page's column, and which one is lit. One per
 * page view; its rows ([Anchored]) note themselves as they are placed.
 */
@Stable
class AnchorHost {
    private val rows = HashMap<String, LayoutCoordinates>()
    internal var column: LayoutCoordinates? = null

    /** The row search has just scrolled to, lit for a moment. */
    var lit by mutableStateOf<String?>(null)
        internal set

    internal fun place(anchor: String, coordinates: LayoutCoordinates) {
        rows[anchor] = coordinates
    }

    /** [anchor]'s top in the page's column (the scroll doesn't move it), once both are placed. */
    internal fun top(anchor: String): Float? {
        val column = column?.takeIf { it.isAttached } ?: return null
        val row = rows[anchor]?.takeIf { it.isAttached } ?: return null
        return column.localPositionOf(row, Offset.Zero).y
    }
}

/** The page's anchors, for the rows beneath; null outside a page (the rows then just draw). */
val LocalAnchorHost = staticCompositionLocalOf<AnchorHost?> { null }

/** The page column's own modifier: the place its anchors are measured from. */
fun Modifier.anchorColumn(host: AnchorHost): Modifier = onGloballyPositioned { host.column = it }

/**
 * A row search can find ([anchor], from [PageRows] or [SettingsIndex.anchorOf]): it notes where it sits,
 * and when search lands on it, it is filled with the elevated surface for a moment, as the open page's row
 * on the hub is, then fades back (a cut under reduced motion). [anchor] null: just the content.
 */
@Composable
fun Anchored(anchor: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val host = LocalAnchorHost.current
    if (anchor == null || host == null) {
        Column(modifier.fillMaxWidth(), content = content)
        return
    }
    val lit = host.lit == anchor
    val reduced = rememberReducedMotion()
    val alpha by animateFloatAsState(
        targetValue = if (lit) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(if (lit) Motion.FastMs else HIGHLIGHT_FADE_MS),
        label = "search highlight",
    )
    val fill = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { host.place(anchor, it) }
            .drawBehind { if (alpha > 0f) drawRect(fill.copy(alpha = alpha * fill.alpha)) },
        content = content,
    )
}

/**
 * The page's side of a [Jump] for it: once the row is placed (a frame or two; at most half a second), [scroll]
 * brings it a little below the page's header and lights it; then [onDone]. A row the page doesn't show just
 * now (the panel's address while the panel is off) leaves the page at its top.
 */
@Composable
fun JumpEffect(jump: Jump?, page: SettingsPage, host: AnchorHost, scroll: ScrollState, onDone: (Jump) -> Unit) {
    val reduced = rememberReducedMotion()
    val margin = with(LocalDensity.current) { JUMP_MARGIN.toPx() }
    LaunchedEffect(jump, page) {
        if (jump == null || jump.page != page) return@LaunchedEffect
        val anchor = jump.anchor
        if (anchor == null) {
            onDone(jump)
            return@LaunchedEffect
        }
        var top: Float? = null
        for (frame in 0 until JUMP_FRAMES) {
            withFrameNanos { }
            top = host.top(anchor)
            if (top != null) break
        }
        try {
            if (top != null) {
                val to = (top - margin).toInt().coerceAtLeast(0)
                if (reduced) scroll.scrollTo(to) else scroll.animateScrollTo(to)
                host.lit = anchor
                delay(HIGHLIGHT_MS)
            }
        } finally {
            if (host.lit == anchor) host.lit = null
            onDone(jump)
        }
    }
}

/** How far below the header a found row comes to rest. */
private val JUMP_MARGIN = 24.dp

/** Frames to wait for the row to be placed (a folded section opening, a page pushed). */
private const val JUMP_FRAMES = 30

/** How long the found row stays lit, and how slowly it fades. */
private const val HIGHLIGHT_MS = 1_100L
private const val HIGHLIGHT_FADE_MS = 600
