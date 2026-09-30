// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.display

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the art and the words stand (DESIGN.md › v1.7.1). The rooms are the windows less their
 * margins (24 dp a side, 16 dp top and foot) and the byline's band above and below (48 dp each).
 */
class RestingLayoutTest {
    private fun room(window: DpSize) = DpSize(window.width - 48.dp, window.height - 32.dp - 96.dp)

    @Test
    fun `a tablet on its side sets the art at the left, 55 percent of its height, the words beside it at reading width`() {
        val window = DpSize(1_707.dp, 1_067.dp)   // 2560 x 1600 px at 240 dpi
        assertTrue(RestingLayout.sideBySide(window))
        val art = RestingLayout.artSide(true, window, room(window))
        assertEquals(586.85f, art.value, 0.01f)
        assertEquals(58.685f, RestingLayout.sideGap(art).value, 0.01f)
        assertEquals(720.dp, RestingLayout.wordsWidth(true, room(window).width, art))
    }

    @Test
    fun `a phone upright sets the art on top, 45 percent of its width, the words under it`() {
        val window = DpSize(411.dp, 914.dp)   // 1080 x 2400 px at 420 dpi
        assertFalse(RestingLayout.sideBySide(window))
        val art = RestingLayout.artSide(false, window, room(window))
        assertEquals(184.95f, art.value, 0.01f)
        assertEquals(363.dp, RestingLayout.wordsWidth(false, room(window).width, art))
    }

    @Test
    fun `a phone on its side sets the art at the left too, the words in what is left`() {
        val window = DpSize(914.dp, 411.dp)
        assertTrue(RestingLayout.sideBySide(window))
        val art = RestingLayout.artSide(true, window, room(window))
        assertEquals(226.05f, art.value, 0.01f)
        assertEquals(32.dp, RestingLayout.sideGap(art))   // a tenth would be 22.6 dp
        assertEquals(866f - 226.05f - 32f, RestingLayout.wordsWidth(true, room(window).width, art).value, 0.01f)
    }

    @Test
    fun `the window's shape decides, not the device, and only a window taller than wide stacks`() {
        val upright = DpSize(1_067.dp, 1_707.dp)   // a tablet stood on end
        assertFalse(RestingLayout.sideBySide(upright))
        assertEquals(480.15f, RestingLayout.artSide(false, upright, room(upright)).value, 0.01f)
        assertTrue("a small phone on its side, a compact width", RestingLayout.sideBySide(DpSize(592.dp, 360.dp)))
        assertTrue("a square window", RestingLayout.sideBySide(DpSize(900.dp, 900.dp)))
        assertFalse(RestingLayout.sideBySide(DpSize(900.dp, 901.dp)))
    }

    @Test
    fun `the art never takes the words' room`() {
        val squat = DpSize(1_000.dp, 600.dp)
        val tight = DpSize(700.dp, 200.dp)
        assertEquals(200.dp, RestingLayout.artSide(true, squat, tight))   // the room's height, not 330 dp
        assertEquals(315f, RestingLayout.artSide(true, DpSize(1_000.dp, 900.dp), DpSize(700.dp, 800.dp)).value, 0.01f)   // 45 % of the room's width
        assertEquals(100.dp, RestingLayout.artSide(false, DpSize(411.dp, 400.dp), DpSize(363.dp, 250.dp)))   // 40 % of the room's height
        assertEquals(0.dp, RestingLayout.wordsWidth(true, 300.dp, 300.dp))
    }
}
