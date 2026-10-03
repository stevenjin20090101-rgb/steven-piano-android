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
import dev.stevenjin.stevenpiano.settings.StandbyCanvas
import dev.stevenjin.stevenpiano.settings.StandbyShows
import dev.stevenjin.stevenpiano.ui.components.SplitAxis
import dev.stevenjin.stevenpiano.ui.screens.nowplaying.ViewShow
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
    fun `the Piano tab's pages open beside its hub on anything wider than a phone held upright`() {
        assertFalse(phone.twoPane)
        assertFalse(frame(599, 900).twoPane)
        assertTrue(frame(600, 900).twoPane)
        assertTrue(tabletUpright.twoPane)
        assertTrue(tabletOnItsSide.twoPane)
        assertTrue("a phone on its side", frame(891, 411).twoPane)
        for (f in listOf(phone, tabletUpright, tabletOnItsSide, frame(891, 411))) assertEquals(f.rail, f.twoPane)
    }

    @Test
    fun `a phone on its side gets the medium layout`() {
        val landscape = frame(891, 411)
        assertEquals(WindowWidthSizeClass.Medium, landscape.widthClass)
        assertTrue(landscape.rail)
        assertEquals(AppFrame.MEDIUM_WHITES, landscape.keysVisibleWhites)
        assertEquals(NotesLayout.STACKED, landscape.notesPlan(PAPER_ROLL, null, null).layout)
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
    fun `on a phone Note display picks the one canvas, the score included`() {
        assertEquals(listOf(PAPER_ROLL, FALLING, STAFF), phone.noteDisplayChoices)
        assertEquals(NotesPlan(NotesLayout.ROLL, PAPER_ROLL), phone.notesPlan(PAPER_ROLL, null, null))
        assertEquals(NotesPlan(NotesLayout.ROLL, FALLING), phone.notesPlan(FALLING, 1f, 1f))   // the split is the wide frames' alone
        assertEquals(NotesLayout.SCORE, phone.notesPlan(STAFF, 0f, 0f).layout)
        assertEquals(null, phone.notesPlan(STAFF, null, null).axis)
    }

    @Test
    fun `medium widths stack the score over the notes, a third for the score, expanded ones set them side by side, half each`() {
        assertEquals(NotesPlan(NotesLayout.STACKED, FALLING, 1f / 3f, SplitAxis.Stacked), tabletUpright.notesPlan(FALLING, null, null))
        assertEquals(NotesPlan(NotesLayout.SIDE_BY_SIDE, PAPER_ROLL, 0.5f, SplitAxis.SideBySide), tabletOnItsSide.notesPlan(PAPER_ROLL, null, null))
        // Each arrangement keeps its own share.
        assertEquals(0.6f, tabletUpright.notesPlan(PAPER_ROLL, 0.6f, 0.2f).split)
        assertEquals(0.2f, tabletOnItsSide.notesPlan(PAPER_ROLL, 0.6f, 0.2f).split)
    }

    @Test
    fun `on wide screens a share of 0 shows the notes alone and 1 the score alone, and Note display picks the roll's style`() {
        assertEquals(NotesPlan(NotesLayout.ROLL, FALLING, 0f, SplitAxis.Stacked), tabletUpright.notesPlan(FALLING, 0f, 1f))
        assertEquals(NotesPlan(NotesLayout.SCORE, PAPER_ROLL, 1f, SplitAxis.SideBySide), tabletOnItsSide.notesPlan(PAPER_ROLL, 0f, 1f))
        for (wide in listOf(tabletUpright, tabletOnItsSide)) {
            assertEquals(NotesLayout.ROLL, wide.notesPlan(STAFF, 0f, 0f).layout)
            assertEquals(PAPER_ROLL, wide.notesPlan(STAFF, 0f, 0f).rollStyle)   // the score reads as the paper roll
            assertEquals(NotesLayout.SCORE, wide.notesPlan(PAPER_ROLL, 1f, 1f).layout)
            assertEquals(listOf(PAPER_ROLL, FALLING), wide.noteDisplayChoices)
            assertTrue(wide.wide)
        }
        assertFalse(phone.wide)
    }

    @Test
    fun `the staff is called the score, under the settings' old names`() {
        assertEquals(listOf("Paper roll", "Falling notes", "Score"), listOf(PAPER_ROLL, FALLING, STAFF).map { it.label })
        assertEquals(listOf("Score and notes", "Notes only", "Score only", "Art only"), ViewShow.entries.map { it.label })
        assertEquals(listOf(ViewShow.NOTES, ViewShow.BOTH, ViewShow.BOTH, ViewShow.SCORE), listOf(0f, 0.01f, 0.99f, 1f).map { ViewShow.of(it) })
        // Art only (v1.18 — M49) whatever the split, and on wide frames alone.
        assertEquals(ViewShow.ART, ViewShow.of(AppFrame(WindowWidthSizeClass.Expanded).notesPlan(PAPER_ROLL, 0.5f, 0.5f, artOnly = true)))
        assertFalse(AppFrame(WindowWidthSizeClass.Compact).notesPlan(PAPER_ROLL, null, null, artOnly = true).artOnly)
        assertEquals("STAFF", STAFF.name)   // saved choices carry over
    }

    @Test
    fun `the Display page's standby chips, in their order`() {
        assertEquals(listOf("Black", "Same as the app"), StandbyCanvas.entries.map { it.label })
        assertEquals(listOf("Art and notes", "Paper roll"), StandbyShows.entries.map { it.label })
    }
}
