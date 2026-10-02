// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.ui.theme.Motion
import dev.stevenjin.stevenpiano.ui.theme.rememberReducedMotion

/**
 * A listing's entrance (DESIGN.md › v1.14 — motion): when a listing is visited, its first [ROWS] rows fade in and
 * rise [Rise], [STAGGER_MS] apart, each once. Only the rows the visit brings: those composed within
 * [WINDOW_MS] of the first one. A row that comes later (scrolled into view, added while the listing shows) just
 * appears, and closing a sheet is no visit (the listing never left the screen). One per visit: remember it with the
 * listing's identity. Pure: [now] is the clock (ListEntranceTest).
 */
class ListEntrance(private val now: () -> Long = SystemClock::uptimeMillis) {
    private var opened: Long? = null
    private val played = HashSet<Any>()

    /** Whether row [key], at [index] in the listing, eases in; asked once, when the row is first composed. */
    fun claims(key: Any, index: Int): Boolean {
        val time = now()
        val first = opened ?: time.also { opened = it }
        if (index !in 0 until ROWS || time - first > WINDOW_MS) return false
        return played.add(key)
    }

    companion object {
        const val ROWS = 10
        const val STAGGER_MS = 12

        /** How far a row rises as it eases in. */
        val Rise: Dp = 8.dp

        /** The rows composed with the first one: the frame they share, and the next few the list may still lay out. */
        const val WINDOW_MS = 100L

        /** How long row [index] waits before it eases in: [STAGGER_MS] for each row above it. */
        fun delayOf(index: Int): Int = index.coerceIn(0, ROWS - 1) * STAGGER_MS
    }
}

/**
 * Row [index] (with [key]) of a listing whose entrance is [entrance]: if the visit brings it, its progress (0 to 1 over
 * [Motion.StandardMs] after its stagger, decelerating) for [easedIn]; otherwise, and under reduced motion, null: the
 * row is simply there.
 */
@Composable
fun rememberEntrance(entrance: ListEntrance, key: Any, index: Int): Animatable<Float, AnimationVector1D>? {
    val reduced = rememberReducedMotion()
    val easing = remember(entrance, key) { if (!reduced && entrance.claims(key, index)) Animatable(0f) else null }
    if (easing != null) {
        LaunchedEffect(easing) {
            easing.animateTo(1f, Motion.timed(Motion.StandardMs, reduced = false, easing = Motion.Enter, delayMs = ListEntrance.delayOf(index)))
        }
    }
    return easing
}

/**
 * Content easing in as [progress] runs from 0 to 1 (v1.14 — motion): it fades in, rising [rise] and growing from
 * [from]; drawn, never laid out, so nothing beside it moves. Null: simply there.
 */
fun Modifier.easedIn(progress: Animatable<Float, AnimationVector1D>?, rise: Dp = 0.dp, from: Float = 1f): Modifier =
    if (progress == null) {
        this
    } else {
        graphicsLayer {
            val shown = progress.value
            alpha = shown
            translationY = (1f - shown) * rise.toPx()
            scaleX = from + (1f - from) * shown
            scaleY = scaleX
        }
    }

/**
 * A listing's row in [item]'s lazy list finding its place when rows are added, removed or reordered (DESIGN.md ›
 * v1.14 — motion): the settle spring, placement only (a new row appears and an old one goes without a fade); a cut
 * under reduced motion.
 */
fun Modifier.placement(item: LazyItemScope, reduced: Boolean): Modifier = with(item) {
    this@placement.animateItem(fadeInSpec = null, placementSpec = if (reduced) null else Motion.settle<IntOffset>(), fadeOutSpec = null)
}
