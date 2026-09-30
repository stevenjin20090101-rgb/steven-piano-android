// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.display

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The resting screen's motion (DESIGN.md › v1.7.1): in over 1.5 s, out in 0.6 s, a piece change in 1.2 s; cuts under reduced motion. */
class RestingMotionTest {
    @Test
    fun `it comes slowly, goes quickly, and a new piece takes a little over a second`() {
        assertEquals(1_500, RestingMotion.ENTER_MS)
        assertEquals(600, RestingMotion.LEAVE_MS)
        assertEquals(1_200, RestingMotion.PIECE_MS)
    }

    @Test
    fun `under reduced motion every one of them is a cut`() {
        assertEquals(EnterTransition.None, RestingMotion.enter(reduced = true))
        assertEquals(ExitTransition.None, RestingMotion.leave(reduced = true))
        val change = RestingMotion.pieceChange(reduced = true)
        assertEquals(EnterTransition.None, change.targetContentEnter)
        assertEquals(ExitTransition.None, change.initialContentExit)
    }

    @Test
    fun `otherwise each is a fade`() {
        assertNotEquals(EnterTransition.None, RestingMotion.enter(reduced = false))
        assertNotEquals(ExitTransition.None, RestingMotion.leave(reduced = false))
        val change = RestingMotion.pieceChange(reduced = false)
        assertNotEquals(EnterTransition.None, change.targetContentEnter)
        assertNotEquals(ExitTransition.None, change.initialContentExit)
    }
}
