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
import kotlin.math.pow

/**
 * The palette's contrast, computed as WCAG 2 defines it (relative luminance of sRGB, then
 * (L1 + 0.05) / (L2 + 0.05)): the hands' colours (DESIGN.md › v1.3 › The waterfall format) clear 3:1,
 * the floor for graphics, on both surfaces of their own appearance.
 */
class ColorTokensTest {
    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double = 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

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
}
