// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.settings.NoteDisplay.FALLING
import dev.stevenjin.stevenpiano.settings.NoteDisplay.PAPER_ROLL
import dev.stevenjin.stevenpiano.settings.NoteDisplay.STAFF
import dev.stevenjin.stevenpiano.settings.WideLayout.NOTES_ONLY
import dev.stevenjin.stevenpiano.settings.WideLayout.STAFF_AND_NOTES
import dev.stevenjin.stevenpiano.settings.WideLayout.STAFF_ONLY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveFrameTest {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    private fun frame(widthDp: Int, heightDp: Int): AppFrame {
        val size = WindowSizeClass.calculateFromSize(DpSize(widthDp.dp, heightDp.dp))
        return AppFrame(size.widthSizeClass, size.heightSizeClass)
    }

    private val phone = frame(411, 891)
    private val tabletUpright = frame(800, 1280)
    private val tabletOnItsSide = frame(1280, 800)

    @Test
    fun `width classes split at 600 and 840 dp`() {
        assertEquals(WindowWidthSizeClass.Compact, frame(599, 900).widthClass)
        assertEquals(WindowWidthSizeClass.Medium, frame(600, 900).widthClass)
        assertEquals(WindowWidthSizeClass.Medium, frame(839, 900).widthClass)
        assertEquals(WindowWidthSizeClass.Expanded, frame(840, 900).widthClass)
        assertEquals(WindowWidthSizeClass.Medium, tabletUpright.widthClass)
        assertEquals(WindowWidthSizeClass.Expanded, tabletOnItsSide.widthClass)
    }

    @Test
    fun `phones keep the bottom bar, wider windows get the rail, never both`() {
        assertFalse(phone.rail)
        assertTrue(tabletUpright.rail)
        assertTrue(tabletOnItsSide.rail)
    }

    @Test
    fun `a phone on its side gets the medium layout`() {
        val landscape = frame(891, 411)
        assertEquals(WindowWidthSizeClass.Medium, landscape.widthClass)
        assertTrue(landscape.rail)
        assertEquals(AppFrame.MEDIUM_WHITES, landscape.keysVisibleWhites)
        assertEquals(NotesLayout.STACKED, landscape.notesPlan(PAPER_ROLL, STAFF_AND_NOTES).layout)
    }

    @Test
    fun `Keys shows two octaves, about four, or every key`() {
        assertEquals(15, phone.keysVisibleWhites)
        assertEquals(29, tabletUpright.keysVisibleWhites)
        assertEquals(49, tabletOnItsSide.keysVisibleWhites)
        assertTrue(phone.keysScroll)
        assertTrue(tabletUpright.keysScroll)
        assertFalse(tabletOnItsSide.keysScroll)
    }

    @Test
    fun `the Playlists and Composers grids have 2, 3 or 4 columns`() {
        assertEquals(2, phone.tileColumns)
        assertEquals(3, tabletUpright.tileColumns)
        assertEquals(3, frame(891, 411).tileColumns)   // a phone on its side
        assertEquals(4, tabletOnItsSide.tileColumns)
    }

    @Test
    fun `on a phone Note display picks the one canvas, the staff included`() {
        assertEquals(listOf(PAPER_ROLL, FALLING, STAFF), phone.noteDisplayChoices)
        assertEquals(NotesPlan(NotesLayout.ROLL, PAPER_ROLL), phone.notesPlan(PAPER_ROLL, STAFF_AND_NOTES))
        assertEquals(NotesPlan(NotesLayout.ROLL, FALLING), phone.notesPlan(FALLING, STAFF_ONLY))
        assertEquals(NotesLayout.STAFF, phone.notesPlan(STAFF, NOTES_ONLY).layout)
    }

    @Test
    fun `medium widths stack the staff over the notes, expanded ones set them side by side`() {
        assertEquals(NotesPlan(NotesLayout.STACKED, FALLING), tabletUpright.notesPlan(FALLING, STAFF_AND_NOTES))
        assertEquals(NotesPlan(NotesLayout.SIDE_BY_SIDE, PAPER_ROLL), tabletOnItsSide.notesPlan(PAPER_ROLL, STAFF_AND_NOTES))
    }

    @Test
    fun `on wide screens Wide layout can show either view alone, and Note display picks the roll's style`() {
        for (wide in listOf(tabletUpright, tabletOnItsSide)) {
            assertEquals(NotesPlan(NotesLayout.ROLL, FALLING), wide.notesPlan(FALLING, NOTES_ONLY))
            assertEquals(NotesPlan(NotesLayout.ROLL, PAPER_ROLL), wide.notesPlan(STAFF, NOTES_ONLY))   // Staff reads as the paper roll
            assertEquals(NotesLayout.STAFF, wide.notesPlan(PAPER_ROLL, STAFF_ONLY).layout)
            assertEquals(listOf(PAPER_ROLL, FALLING), wide.noteDisplayChoices)
            assertTrue(wide.wide)
        }
        assertFalse(phone.wide)
    }
}
