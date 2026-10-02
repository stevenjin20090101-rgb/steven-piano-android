// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The motion tokens (DESIGN.md › v1.14 — motion): nothing longer than 480 ms, and every transition a cut when motion is reduced. */
class MotionTokensTest {
    @Test
    fun `no duration is longer than 480 ms, and the four steps are the brief's`() {
        assertTrue(Motion.Durations.all { it in 1..Motion.SlowMs })
        assertEquals(480, Motion.SlowMs)
        assertEquals(listOf(120, 200, 320, 480), listOf(Motion.QuickMs, Motion.StandardMs, Motion.EmphasisedMs, Motion.SlowMs))
        // Asked for more, a timed transition is still held to the maximum.
        assertEquals(Motion.SlowMs, (Motion.timed<Float>(2_000, reduced = false) as TweenSpec).durationMillis)
        // The two springs come to rest well inside it too (a whole unit's move, the default threshold).
        for (spec in listOf(Motion.press<Float>(), Motion.settle<Float>())) {
            val ms = FloatSpringSpec(spec.dampingRatio, spec.stiffness).getDurationNanos(0f, 1f, 0f) / 1_000_000
            assertTrue("a spring takes $ms ms", ms <= Motion.SlowMs)
        }
    }

    @Test
    fun `under reduced motion every transition is a cut`() {
        assertTrue(Motion.timed<Float>(Motion.EmphasisedMs, reduced = true) is SnapSpec)
        assertTrue(Motion.sprung(Motion.press<Float>(), reduced = true) is SnapSpec)
        assertTrue(Motion.sprung(Motion.settle<Float>(), reduced = true) is SnapSpec)
        assertEquals(EnterTransition.None, Motion.enter(fadeIn() + scaleIn(), reduced = true))
        assertEquals(ExitTransition.None, Motion.exit(fadeOut(), reduced = true))
        val change = Motion.change(fadeIn(), fadeOut(), reduced = true)
        assertEquals(EnterTransition.None, change.targetContentEnter)
        assertEquals(ExitTransition.None, change.initialContentExit)
    }

    @Test
    fun `otherwise they move, for as long as asked`() {
        assertEquals(Motion.StandardMs, (Motion.timed<Float>(Motion.StandardMs, reduced = false) as TweenSpec).durationMillis)
        assertTrue(Motion.sprung(Motion.press<Float>(), reduced = false) !is SnapSpec)
        assertNotEquals(EnterTransition.None, Motion.enter(fadeIn(), reduced = false))
        assertNotEquals(ExitTransition.None, Motion.exit(fadeOut(), reduced = false))
    }
}
