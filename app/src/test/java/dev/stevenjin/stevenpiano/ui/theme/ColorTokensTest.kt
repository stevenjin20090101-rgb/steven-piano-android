// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * The palette's contrast, computed as WCAG 2 defines it ([Wcag]): the hands' colours (DESIGN.md ›
 * v1.3 › The waterfall format) and the score's sounding yellow (DESIGN.md › v1.5 — M16) clear 3:1,
 * the floor for graphics, on both surfaces of their own appearance.
 */
class ColorTokensTest {
    private fun luminance(color: Color): Double = Wcag.luminance(color)

    private fun contrast(a: Color, b: Color): Double = Wcag.contrast(a, b)

    @Test
    fun `the WCAG formula gives WCAG's own figures`() {
        assertEquals(21.0, contrast(Color.White, Color.Black), 0.01)
        assertEquals(4.54, contrast(Color(0xFF767676), Color.White), 0.01)   // the lightest grey that passes AA on white
        assertEquals(1.0, contrast(InkSurface, InkSurface), 0.0)
    }

    @Test
    fun `each hand's colour clears 3 to 1 on both surfaces of its appearance`() {
        val dark = listOf(InkSurface, InkElevated)
        val light = listOf(PaperSurface, PaperElevated)
        for (hand in listOf(HandLeftDark, HandRightDark)) for (surface in dark) {
            assertTrue("$hand on $surface: ${contrast(hand, surface)}", contrast(hand, surface) >= 3.0)
        }
        for (hand in listOf(HandLeftLight, HandRightLight)) for (surface in light) {
            assertTrue("$hand on $surface: ${contrast(hand, surface)}", contrast(hand, surface) >= 3.0)
        }
        // The figures Color.kt records.
        assertEquals(5.8, contrast(HandLeftDark, InkElevated), 0.05)
        assertEquals(5.8, contrast(HandRightDark, InkElevated), 0.05)
        assertEquals(5.4, contrast(HandLeftLight, PaperSurface), 0.05)
        assertEquals(5.7, contrast(HandRightLight, PaperSurface), 0.05)
    }

    @Test
    fun `the hands are a muted green and a muted blue, neither one red`() {
        for (left in listOf(HandLeftDark, HandLeftLight)) assertTrue(left.green > left.red && left.green > left.blue)
        for (right in listOf(HandRightDark, HandRightLight)) assertTrue(right.blue > right.red && right.blue > right.green)
        // Muted: no channel far from the others (saturation well under that of the live red).
        for (hand in listOf(HandLeftDark, HandRightDark, HandLeftLight, HandRightLight)) {
            val spread = max(hand.red, max(hand.green, hand.blue)) - min(hand.red, min(hand.green, hand.blue))
            assertTrue("$hand", spread < 0.3f)
        }
        // The two hands of one appearance weigh the same (one lightness), so neither outshines the other.
        assertEquals(luminance(HandLeftDark), luminance(HandRightDark), 0.01)
        assertEquals(luminance(HandLeftLight), luminance(HandRightLight), 0.02)
    }

    @Test
    fun `the sounding yellow clears 3 to 1 on both surfaces of its appearance, the score panel's among them`() {
        for (surface in listOf(InkSurface, InkElevated)) {
            assertTrue("dark on $surface: ${contrast(NoteSoundingDark, surface)}", contrast(NoteSoundingDark, surface) >= 3.0)
        }
        for (surface in listOf(PaperSurface, PaperElevated)) {
            assertTrue("light on $surface: ${contrast(NoteSoundingLight, surface)}", contrast(NoteSoundingLight, surface) >= 3.0)
        }
        // The figures Color.kt records (the score panel is surfaceElevated).
        assertEquals(11.0, contrast(NoteSoundingDark, InkElevated), 0.05)
        assertEquals(12.2, contrast(NoteSoundingDark, InkSurface), 0.05)
        assertEquals(3.8, contrast(NoteSoundingLight, PaperElevated), 0.05)
        assertEquals(3.6, contrast(NoteSoundingLight, PaperSurface), 0.05)
    }

    @Test
    fun `the sounding yellow is a yellow, and never reads as the live red`() {
        for (yellow in listOf(NoteSoundingDark, NoteSoundingLight)) {
            val hue = Wcag.hue(yellow)
            assertTrue("$yellow: hue $hue", hue in 40f..55f)
            assertTrue("$yellow: red - green ${yellow.red - yellow.green}", yellow.red - yellow.green < 0.2f)
        }
        // The live reds, for scale: their red runs far past their green.
        for (red in listOf(LiveRedDark, LiveRedLight)) assertTrue(red.red - red.green > 0.5f)
    }

    @Test
    fun `display mode's canvas is true black, and the camera body's text reads on it`() {
        assertEquals(Color(0f, 0f, 0f, 1f), DisplayBlack)
        assertTrue(contrast(SilverPrimary, DisplayBlack) >= 7.0)
        assertTrue(contrast(SilverSecondary, DisplayBlack) >= 7.0)
        assertTrue(contrast(SilverTertiary, DisplayBlack) >= 4.5)
        assertEquals(18.8, contrast(SilverPrimary, DisplayBlack), 0.05)
        assertEquals(8.3, contrast(SilverSecondary, DisplayBlack), 0.05)
        assertEquals(6.1, contrast(SilverTertiary, DisplayBlack), 0.05)
        // Nothing else leans darker than the ink surface: the black is display mode's alone.
        assertTrue(Wcag.luminance(InkSurface) > Wcag.luminance(DisplayBlack))
    }

    @Test
    fun `a channel card's band keeps its words at the glass's contrast over any portrait`() {
        // The band is the surface at the glass's opacity over the mosaic: over pure white (dark) or
        // pure black (light), the content colour still reads as it does on the glass.
        val darkBand = Wcag.over(InkSurface, GlassTokens.ContainerAlpha, Color.White)
        val lightBand = Wcag.over(PaperSurface, GlassTokens.ContainerAlpha, Color.Black)
        assertTrue(contrast(SilverPrimary, darkBand) >= 7.0)
        assertTrue(contrast(CarbonPrimary, lightBand) >= 7.0)
    }
}
