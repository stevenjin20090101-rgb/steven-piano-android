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
    fun `stacked the roll has two thirds of the height, side by side all of it`() {
        val stacked = NotesPlan(NotesLayout.STACKED, NoteDisplay.PAPER_ROLL)
        assertTrue(transportFloats(stacked, 900.dp))   // a roll card of about 595 dp
        assertFalse(transportFloats(stacked, 600.dp))  // about 395 dp: its history is too short
        assertTrue(transportFloats(NotesPlan(NotesLayout.SIDE_BY_SIDE, NoteDisplay.PAPER_ROLL), 600.dp))
    }
}
