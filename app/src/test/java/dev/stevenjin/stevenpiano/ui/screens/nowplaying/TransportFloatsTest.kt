// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.nowplaying

import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.ui.NotesLayout
import dev.stevenjin.stevenpiano.ui.NotesPlan
import dev.stevenjin.stevenpiano.ui.components.SplitAxis
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the transport floats on glass over the roll's history (DESIGN.md › v1.5 — M16), and when it stands below. */
class TransportFloatsTest {
    private val roll = NotesPlan(NotesLayout.ROLL, NoteDisplay.PAPER_ROLL)

    @Test
    fun `over a paper roll whose history can hold it, with room under the tracker bar`() {
        // A phone upright: about 560 dp for the card; its history third is about 170 dp, the controls 128.
        assertTrue(transportFloats(roll, 560.dp))
        // The history must hold the controls and 12 dp under the bar: (h - 45) / 3 >= 140.
        assertTrue(transportFloats(roll, 465.dp))
        assertFalse(transportFloats(roll, 460.dp))
    }

    @Test
    fun `never over falling notes, which have no history, nor over the score alone`() {
        assertFalse(transportFloats(NotesPlan(NotesLayout.ROLL, NoteDisplay.FALLING), 900.dp))
        assertFalse(transportFloats(NotesPlan(NotesLayout.SCORE, NoteDisplay.PAPER_ROLL), 900.dp))
    }

    @Test
    fun `stacked the roll has what the committed split leaves it below the score`() {
        fun stacked(split: Float) = NotesPlan(NotesLayout.STACKED, NoteDisplay.PAPER_ROLL, split, SplitAxis.Stacked)
        // A third for the score: a roll card of about 595 dp at 900, 395 dp at 600 (its history too short).
        assertTrue(transportFloats(stacked(1f / 3f), 900.dp))
        assertFalse(transportFloats(stacked(1f / 3f), 600.dp))
        // A half: 446 dp at 900 is too short, 466 dp at 940 holds it.
        assertFalse(transportFloats(stacked(0.5f), 900.dp))
        assertTrue(transportFloats(stacked(0.5f), 940.dp))
        // A fifth for the score: 474 dp at 600.
        assertTrue(transportFloats(stacked(0.2f), 600.dp))
    }

    @Test
    fun `side by side the roll has all of the height, if it is as wide as the controls`() {
        fun side(split: Float) = NotesPlan(NotesLayout.SIDE_BY_SIDE, NoteDisplay.PAPER_ROLL, split, SplitAxis.SideBySide)
        assertTrue(transportFloats(side(0.5f), 600.dp))   // no width given: the height alone decides
        assertTrue(transportFloats(side(0.5f), 600.dp, 1_168.dp))   // a tablet on its side: 580 dp of roll
        assertTrue(transportFloats(side(2f / 3f), 600.dp, 1_168.dp))   // 387 dp
        assertFalse(transportFloats(side(0.75f), 600.dp, 1_168.dp))   // 290 dp: the controls need 344
        assertFalse(transportFloats(side(0.5f), 400.dp, 1_168.dp))   // too short, however wide
    }

    @Test
    fun `with the roll hidden there is nothing to float over`() {
        assertFalse(transportFloats(NotesPlan(NotesLayout.SCORE, NoteDisplay.PAPER_ROLL, 1f, SplitAxis.Stacked), 900.dp))
        assertTrue(transportFloats(NotesPlan(NotesLayout.ROLL, NoteDisplay.PAPER_ROLL, 0f, SplitAxis.Stacked), 900.dp))
    }
}
