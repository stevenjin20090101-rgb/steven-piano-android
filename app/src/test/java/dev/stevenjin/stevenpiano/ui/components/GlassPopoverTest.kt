// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where [GlassPopover] opens ([popoverPosition]): ends aligned with its control, as it always has, or
 * starts aligned, for the speaker at the start of the tablet's now-playing panel, whose popover opened
 * across the panes' divider over the list (the 1.9 review). In pixels, at 240 dpi (1.5 px a dp): the
 * 8 dp window margin is 12, the 4 dp gap below the control 6, a 48 dp control 72, the 300 dp popover 450.
 */
class GlassPopoverTest {
    private val window = IntSize(2560, 1600)
    private val popover = IntSize(450, 330)
    private val gap = IntOffset(0, 6)
    private val margin = 12

    private fun place(anchor: IntRect, alignment: Alignment.Horizontal, direction: LayoutDirection = LayoutDirection.Ltr, offset: IntOffset = gap) =
        popoverPosition(anchor, window, popover, direction, alignment, offset, margin)

    /** A 48 dp control well inside the window, with room below it. */
    private val control = IntRect(1200, 300, 1272, 372)

    @Test
    fun `ends aligned, below the control, as before`() {
        val at = place(control, Alignment.End)
        assertEquals("the popover's end at the control's end", control.right, at.x + popover.width)
        assertEquals("4 dp below the control", control.bottom + 6, at.y)
    }

    @Test
    fun `starts aligned puts the popover's start at the control's start`() {
        val at = place(control, Alignment.Start)
        assertEquals(control.left, at.x)
        assertEquals(control.bottom + 6, at.y)
    }

    @Test
    fun `right to left mirrors both`() {
        assertEquals("the end is the left", control.left, place(control, Alignment.End, LayoutDirection.Rtl).x)
        assertEquals("the start is the right", control.right, place(control, Alignment.Start, LayoutDirection.Rtl).x + popover.width)
    }

    @Test
    fun `the offset's x moves it toward the end, in either direction`() {
        val nudge = IntOffset(15, 6)
        assertEquals(control.right - popover.width + 15, place(control, Alignment.End, offset = nudge).x)
        assertEquals(control.left - 15, place(control, Alignment.End, LayoutDirection.Rtl, nudge).x)
        assertEquals(control.left + 15, place(control, Alignment.Start, offset = nudge).x)
    }

    @Test
    fun `above the control where the window has no room below`() {
        val low = IntRect(1200, 1500, 1272, 1572)
        assertEquals(low.top - 6 - popover.height, place(low, Alignment.Start).y)
        assertEquals(low.top - 6 - popover.height, place(low, Alignment.End).y)
    }

    @Test
    fun `kept 8 dp inside the window, either way`() {
        val atStart = IntRect(0, 300, 72, 372)
        assertEquals(margin, place(atStart, Alignment.End).x)
        val atEnd = IntRect(2488, 300, 2560, 372)
        assertEquals(window.width - margin - popover.width, place(atEnd, Alignment.Start).x)
        val squeezed = IntSize(300, 200)
        assertEquals(margin, popoverPosition(atStart, squeezed, popover, LayoutDirection.Ltr, Alignment.End, gap, margin).x)
    }

    @Test
    fun `the panel's speaker opens its popover within the panel`() {
        // The panel's pane begins at the divider; the speaker is at the start of its foot row, 16 dp in.
        val divider = 1100
        val speaker = IntRect(divider + 24, 1516, divider + 24 + 72, 1588)
        val started = place(speaker, Alignment.Start)
        assertTrue("within the panel: ${started.x} ≥ $divider", started.x >= divider)
        assertEquals("above the speaker, the panel's foot having no room below", speaker.top - 6 - popover.height, started.y)
        // Ends aligned, as in 1.9, it reached back over the divider onto the list's pane.
        assertTrue(place(speaker, Alignment.End).x < divider)
    }

    @Test
    fun `the Playlists' sort opens its menu within the list's pane, its end at the button's (v1_10_1)`() {
        // The tablet's two panes (2560 px at 320 dpi, 2 px a dp): the list's pane ends at the divider; the pop-up button
        // stands at the end of the Playlists header row, 4 dp in, about 130 dp wide and 48 dp tall; its menu about 200 dp.
        val divider = 1408
        val button = IntRect(divider - 8 - 260, 900, divider - 8, 996)
        val menu = IntSize(400, 256)
        val ended = popoverPosition(button, window, menu, LayoutDirection.Ltr, Alignment.End, IntOffset(0, 8), 16)
        assertTrue("within the list's pane: ${ended.x + menu.width} ≤ $divider", ended.x + menu.width <= divider)
        assertEquals(button.bottom + 8, ended.y)
        val started = popoverPosition(button, window, menu, LayoutDirection.Ltr, Alignment.Start, IntOffset(0, 8), 16)
        assertTrue("starts aligned, it would cross onto the now-playing panel", started.x + menu.width > divider)
        val calls = File("src/main/java/dev/stevenjin/stevenpiano/ui/screens/library/PlaylistsHeader.kt").readLines()
            .map { it.substringBefore("//") }
            .filter { "GlassPopover(" in it }
        assertEquals(1, calls.size)
        assertTrue(calls.single(), "alignment = Alignment.End" in calls.single())
    }

    @Test
    fun `the panel's foot starts its popover at the speaker, Now playing's tempo row keeps the ends`() {
        val sources = File("src/main/java/dev/stevenjin/stevenpiano/ui/screens/nowplaying")
        fun speakerCalls(name: String) = File(sources, name).readLines()
            .map { it.substringBefore("//") }
            .filter { "TabletSoundSpeaker(" in it }
        val panel = speakerCalls("NowPlayingPanel.kt")
        assertEquals(1, panel.size)
        assertTrue(panel.single(), "popoverAlignment = Alignment.Start" in panel.single())
        val nowPlaying = speakerCalls("NowPlayingScreen.kt")
        assertEquals(1, nowPlaying.size)
        assertTrue(nowPlaying.single(), "popoverAlignment" !in nowPlaying.single())
    }
}
