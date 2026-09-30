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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import dev.stevenjin.stevenpiano.ui.LocalFloatingPadding
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

/**
 * A pane with a glass navigation bar at its top (DESIGN.md › v1.9): [header] (a tab's title and
 * byline with its action, a page's title, the now-playing panel's eyebrow row) on glass, and [content]
 * filling the pane beneath it, the header's height given to it as [LocalFloatingPadding]'s top (the
 * status bar's included: the glass reaches up under it), so a list starts below the header and
 * scrolls beneath it. Each pane has its own: on the tablet's splits (the Library beside the
 * now-playing panel, the Piano hub beside its page) each header is its own glass over its own pane.
 *
 * Its two appearances, as a navigation bar's (UIKit's scroll-edge and standard appearances): at rest,
 * with nothing scrolled beneath it, the header is the surface itself, without its edge, and the tab
 * looks as it always has, its byline in the tertiary grey. Once [scroll] has moved content beneath
 * it, the glass shows: the content blurred under the bar's fill, the hairline and the specular line
 * along its bottom edge, the frost thickening towards that edge ([GlassSurface]'s band), and its text
 * in the content colour (nothing tertiary or secondary sits on a bar's glass); the change fades over
 * 120 ms, a cut when motion is reduced. [scroll] null: nothing ever passes beneath (Keys, a fixed
 * Now playing), and the header never blurs. The content is the header's own source ([hazeSource]),
 * recorded apart from the other pane's, so a roll playing beside a list never makes the list's header
 * blur again; and with nothing scrolled beneath it the header does not blur at all.
 */
@Composable
fun GlassHeaderPane(
    scroll: ScrollableState?,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val floating = LocalFloatingPadding.current
    val source = rememberHazeState()
    val reduced = rememberReducedMotion()
    val scrolled = remember(scroll) { derivedStateOf { scroll?.canScrollBackward == true } }
    val presence = animateFloatAsState(
        targetValue = if (scrolled.value) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(Motion.FastMs),
        label = "header glass",
    )
    val slots = remember { PaneSlots() }
    val direction = LocalLayoutDirection.current
    SubcomposeLayout(modifier) { constraints ->
        val top = floating.calculateTopPadding()
        val bar = subcompose(PaneSlot.Header, slots.header(listOf(top, header, scroll)) { { HeaderBar(source, scroll != null, scrolled, presence, top, header) } })
            .map { it.measure(constraints.copy(minHeight = 0)) }
        val height = (bar.maxOfOrNull { it.height } ?: 0).toDp()
        val below = floating.withTop(height, direction)
        val body = subcompose(PaneSlot.Content, slots.content(listOf(below, content, scroll != null)) { { PaneBody(source, scroll != null, below, content) } })
            .map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            body.forEach { it.place(0, 0) }
            bar.forEach { it.place(0, 0) }
        }
    }
}

private enum class PaneSlot { Header, Content }

/**
 * The pane's two slots, each built again only when what it depends on changes: a header whose text
 * changes (an import's count) measures the pane again, and must not recompose the whole list beneath.
 */
private class PaneSlots {
    private var headerKey: Any? = null
    private var headerSlot: (@Composable () -> Unit)? = null
    private var contentKey: Any? = null
    private var contentSlot: (@Composable () -> Unit)? = null

    fun header(key: Any, make: () -> @Composable () -> Unit): @Composable () -> Unit {
        if (key != headerKey || headerSlot == null) {
            headerKey = key
            headerSlot = make()
        }
        return headerSlot!!
    }

    fun content(key: Any, make: () -> @Composable () -> Unit): @Composable () -> Unit {
        if (key != contentKey || contentSlot == null) {
            contentKey = key
            contentSlot = make()
        }
        return contentSlot!!
    }
}

/** The floating padding below a header [top] tall: the sides and the bottom as they were. */
private fun PaddingValues.withTop(top: Dp, direction: LayoutDirection): PaddingValues = PaddingValues(
    start = calculateStartPadding(direction),
    top = top,
    end = calculateEndPadding(direction),
    bottom = calculateBottomPadding(),
)

@Composable
private fun PaneBody(source: HazeState, sourced: Boolean, padding: PaddingValues, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFloatingPadding provides padding) {
        // Its own layer: a list scrolling redraws only itself, never the header's glass beside it.
        Box(if (sourced) Modifier.fillMaxSize().graphicsLayer().hazeSource(source) else Modifier.fillMaxSize()) { content() }
    }
}

/**
 * The header's glass: blurring only while content is scrolled beneath ([scrolled]); its edge, its
 * band and its text's colours following [presence] (0 at rest, 1 with content beneath).
 */
@Composable
private fun HeaderBar(
    source: HazeState,
    scrolls: Boolean,
    scrolled: State<Boolean>,
    presence: State<Float>,
    top: Dp,
    header: @Composable () -> Unit,
) {
    // Today's greys, read outside the glass (which would give them way at once).
    val tertiary = LocalTertiary.current
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val glass = glassAvailable()
    val edge = remember(presence) { { presence.value } }
    GlassSurface(
        Modifier.fillMaxWidth(),
        source = source,
        edge = GlassEdge.Bottom,
        blur = scrolls && scrolled.value,
        edgeAlpha = edge,
        band = edge,
    ) {
        // At rest, today's greys; with content beneath, the content colour (the bar's rule), fading between.
        val shown = if (glass) presence.value else 0f
        CompositionLocalProvider(
            LocalTertiary provides lerp(tertiary, ink, shown),
            LocalSecondaryText provides lerp(secondary, ink, shown),
        ) {
            Column(Modifier.fillMaxWidth().padding(top = top)) { header() }
        }
    }
}
