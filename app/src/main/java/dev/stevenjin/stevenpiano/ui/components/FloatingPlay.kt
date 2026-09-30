// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import kotlin.math.roundToInt

/** The floating Play: a 56 dp circle, 16 dp in from the list's end and above its bottom. */
val FloatingPlaySize = 56.dp
val FloatingPlayMargin = 16.dp

/** What a list keeps under its last row while the floating Play shows, so that row can rise clear of it. */
val FloatingPlayClearance = FloatingPlayMargin + FloatingPlaySize + FloatingPlayMargin

/**
 * A playlist's Play (DESIGN.md › v1.5 — M16, v1.9): a 56 dp filled circle floating at the bottom end
 * of the list, above the floating controls, the rows passing beneath it: the primary action, solid,
 * as the transport's play is. The screen asks for the circle ([FloatingPlayRequest]) and the nav host
 * draws it over the content ([FloatingPlayLayer]), beside the navigation content and never in it.
 */
@Stable
class FloatingPlaySlot {
    internal var ask by mutableStateOf<FloatingPlayAsk?>(null)
}

/** A screen's request: the circle at the bottom end of [anchor] (in the root's pixels), playing [onPlay]. */
internal data class FloatingPlayAsk(val owner: Any, val anchor: Rect, val onPlay: () -> Unit)

/** The nav host's slot for the floating Play. */
val LocalFloatingPlaySlot = staticCompositionLocalOf { FloatingPlaySlot() }

/**
 * Asks for the floating Play while [visible], at the bottom end of [anchor]: the list's reading
 * column in the root's pixels, its bottom already above what the floating controls cover. The
 * request goes when this leaves the composition.
 */
@Composable
fun FloatingPlayRequest(visible: Boolean, anchor: Rect?, onPlay: () -> Unit) {
    val slot = LocalFloatingPlaySlot.current
    val owner = remember { Any() }
    val latest by rememberUpdatedState(onPlay)
    val play = remember { { latest() } }
    val wanted = if (visible && anchor != null) FloatingPlayAsk(owner, anchor, play) else null
    SideEffect {
        if (wanted != null) slot.ask = wanted else if (slot.ask?.owner === owner) slot.ask = null
    }
    DisposableEffect(slot) {
        onDispose { if (slot.ask?.owner === owner) slot.ask = null }
    }
}

/**
 * The nav host's layer over the content: the floating Play where it was asked for, while [shown]
 * (its screen is the one showing). Touches elsewhere pass through to the content.
 */
@Composable
fun FloatingPlayLayer(slot: FloatingPlaySlot, shown: Boolean) {
    val ask = slot.ask
    if (!shown || ask == null) return
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onPlaced { origin = it.positionInRoot() },
    ) {
        FloatingPlayButton(
            ask.onPlay,
            Modifier.offset {
                val size = FloatingPlaySize.toPx()
                val margin = FloatingPlayMargin.toPx()
                val x = if (rtl) ask.anchor.left + margin else ask.anchor.right - margin - size
                val y = ask.anchor.bottom - margin - size
                IntOffset((x - origin.x).roundToInt(), (y - origin.y).roundToInt())
            },
        )
    }
}

/**
 * The circle: filled in the content colour, the play glyph in the surface colour (the pair reads
 * 17:1 and 16:1), with glass or without (DESIGN.md › v1.9: the primary action is never glass).
 * Pressing it gives the play tick.
 */
@Composable
fun FloatingPlayButton(onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val view = LocalView.current
    Box(
        modifier
            .size(FloatingPlaySize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface)
            .clickable(role = Role.Button) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onPlay()
            }
            .semantics { contentDescription = "Play" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_play),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(28.dp),
        )
    }
}
