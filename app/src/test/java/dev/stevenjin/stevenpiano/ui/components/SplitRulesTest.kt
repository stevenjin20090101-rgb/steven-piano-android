// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The divider between the score and the notes (DESIGN.md › v1.12): stops, minimums, hiding and coming back. */
class SplitRulesTest {
    /** A tablet upright: 800 dp to share stacked, 792 of it the panes' (a third is 264 dp). */
    private val stacked = SplitRules(800f, SplitAxis.Stacked.firstMinDp, SplitAxis.Stacked.secondMinDp)

    /** A tablet on its side: 1,168 dp to share side by side. */
    private val side = SplitRules(1_168f, SplitAxis.SideBySide.firstMinDp, SplitAxis.SideBySide.secondMinDp)

    @Test
    fun `the defaults - a third stacked, a half side by side - and each pane's minimum`() {
        assertEquals(1f / 3f, SplitAxis.Stacked.defaultShare, 0f)
        assertEquals(0.5f, SplitAxis.SideBySide.defaultShare, 0f)
        assertEquals(792f, stacked.room, 0f)
        assertEquals(200f / 792f, stacked.lowest, 1e-6f)
        assertEquals(1f - 165f / 792f, stacked.highest, 1e-6f)
        assertEquals(listOf(1f / 3f, 0.5f, 2f / 3f), stacked.stops)
    }

    @Test
    fun `a drag rests on a third, a half or two thirds within 12 dp`() {
        assertEquals(1f / 3f, stacked.dragged(264f + 12f), 0f)
        assertEquals(1f / 3f, stacked.dragged(264f - 11f), 0f)
        assertEquals(276.5f / 792f, stacked.dragged(276.5f), 1e-6f)
        assertEquals(0.5f, stacked.dragged(391f), 0f)
        assertEquals(2f / 3f, side.dragged(1_160f * 2f / 3f + 8f), 0f)
        assertEquals(0.4f, side.dragged(464f), 1e-6f)
    }

    @Test
    fun `a pane keeps its minimum, hides 56 dp past it, and shows again at its minimum dragged back`() {
        // The score: 200 dp at least; hidden under 144.
        assertEquals(stacked.lowest, stacked.dragged(150f), 0f)
        assertEquals(stacked.lowest, stacked.dragged(144f), 0f)
        assertEquals(0f, stacked.dragged(143.9f), 0f)
        assertEquals(0f, stacked.dragged(-40f), 0f)
        // The notes: 165 dp at least; hidden under 109.
        assertEquals(stacked.highest, stacked.dragged(792f - 109f), 0f)
        assertEquals(1f, stacked.dragged(792f - 108.9f), 0f)
        // From a hidden score, its grabber dragged in: nothing until 144 dp, then the score at its 200 dp.
        assertEquals(0f, stacked.dragged(0f + 100f), 0f)
        assertEquals(200f, stacked.dragged(0f + 150f) * stacked.room, 0.01f)
        // Side by side, 240 dp each.
        assertEquals(0f, side.dragged(183f), 0f)
        assertEquals(240f / 1_160f, side.dragged(185f), 1e-6f)
    }

    @Test
    fun `a committed share is drawn kept to the minimums, a hidden pane as hidden`() {
        assertEquals(0f, stacked.shown(0f), 0f)
        assertEquals(1f, stacked.shown(1f), 0f)
        assertEquals(stacked.lowest, stacked.shown(0.1f), 0f)
        assertEquals(stacked.highest, stacked.shown(0.95f), 0f)
        assertEquals(0.4f, stacked.shown(0.4f), 0f)
        // Where both minimums do not fit, a visible split sits at their ratio.
        val tight = SplitRules(300f, 200f, 165f)
        assertEquals(tight.lowest, tight.highest, 0f)
        assertEquals(200f / 365f, tight.shown(0.9f), 1e-6f)
    }

    @Test
    fun `keys - arrows 2 percent within the minimums, Page keys between the stops, and adjustments never hide`() {
        assertEquals(0.52f, stacked.nudged(0.5f, SplitRules.STEP), 1e-6f)
        assertEquals(stacked.lowest, stacked.nudged(stacked.lowest, -SplitRules.STEP), 0f)
        assertEquals("a hidden score shows at its minimum", stacked.lowest, stacked.nudged(0f, SplitRules.STEP), 0f)
        assertEquals(0f, stacked.nudged(0f, -SplitRules.STEP), 0f)
        assertEquals(stacked.highest, stacked.nudged(1f, -SplitRules.STEP), 0f)
        assertEquals(1f, stacked.nudged(1f, SplitRules.STEP), 0f)
        assertEquals(0.5f, stacked.nextStop(1f / 3f, forward = true), 0f)
        assertEquals(1f / 3f, stacked.nextStop(0.45f, forward = false), 0f)
        assertEquals(2f / 3f, stacked.nextStop(2f / 3f, forward = true), 0f)
        assertEquals(1f / 3f, stacked.nextStop(0f, forward = true), 0f)
        assertEquals(stacked.lowest, stacked.settled(0.05f), 0f)
        assertEquals(0.5f, stacked.settled(0.5f), 0f)
    }

    @Test
    fun `a tick on resting on a stop and on hiding, and TalkBack's words`() {
        assertTrue(stacked.ticks(0.30f, 1f / 3f))
        assertFalse(stacked.ticks(1f / 3f, 1f / 3f))
        assertFalse(stacked.ticks(0.30f, 0.31f))
        assertTrue(stacked.ticks(0.30f, 0f))
        assertTrue(stacked.ticks(0.70f, 1f))
        assertEquals("sheet music 33 percent", SplitRules.stateText(1f / 3f))
        assertEquals("sheet music hidden", SplitRules.stateText(0f))
        assertEquals("notes hidden", SplitRules.stateText(1f))
    }
}
