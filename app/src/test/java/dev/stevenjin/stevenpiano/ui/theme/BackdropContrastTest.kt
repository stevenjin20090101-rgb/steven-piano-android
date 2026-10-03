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

/**
 * The album-colour backdrop's proof (DESIGN.md › v1.15 — M41): through the veil, the primary colour keeps 4.5:1 over a
 * disc of any hue at full saturation, the strongest the art can give ([Wcag]: the blend Android draws, then WCAG 2's
 * contrast), on paper, on ink, and behind the words on the resting screen's black.
 */
class BackdropContrastTest {
    /** The weakest contrast of [text] over [surface] at [veil] across every hue's disc ([dark]: the ink lightness). */
    private fun worst(surface: Color, veil: Float, text: Color, dark: Boolean): Double =
        (0 until 360).minOf { hue -> Wcag.contrast(text, Wcag.over(surface, veil, discColour(hue.toFloat(), 1f, dark))) }

    @Test
    fun `the primary colour keeps 4,5 to 1 through the veil over every hue, on paper and on ink`() {
        val paper = worst(PaperSurface, Backdrop.PaperVeil, CarbonPrimary, dark = false)
        val ink = worst(InkSurface, Backdrop.InkVeil, SilverPrimary, dark = true)
        assertTrue("paper: $paper", paper >= 4.5)
        assertTrue("ink: $ink", ink >= 4.5)
        // The figures Backdrop.kt records: paper's worst is the blue disc, ink's the yellow.
        assertEquals(5.7, paper, 0.05)
        assertEquals(8.6, ink, 0.05)
    }

    @Test
    fun `on the resting screen's black the words' veil keeps the camera body's primary at 4,5 to 1`() {
        val black = worst(DisplayBlack, Backdrop.BlackWordsVeil, SilverPrimary, dark = true)
        assertTrue("black: $black", black >= 4.5)
        assertEquals(5.1, black, 0.05)
    }

    @Test
    fun `the veils, the discs' lightness and their paths are the design's`() {
        assertEquals(0.55f, Backdrop.PaperVeil)
        assertEquals(0.62f, Backdrop.InkVeil)
        assertEquals(0.35f, Backdrop.BlackWordsVeil)
        assertEquals(Backdrop.InkVeil, Backdrop.veil(dark = true))
        assertEquals(Backdrop.PaperVeil, Backdrop.veil(dark = false))
        assertEquals(0.6f, Backdrop.Radius)
        assertEquals(listOf(20_000, 22_500, 24_000, 26_000), Backdrop.PeriodsMs.toList())
        // A disc's colour holds the appearance's lightness, whatever the art's hue: HSL L 0.45 on paper, 0.32 on ink.
        for (hue in listOf(0f, 60f, 240f)) {
            val paper = discColour(hue, 1f, dark = false)
            val ink = discColour(hue, 1f, dark = true)
            assertEquals(0.45f, (maxOf(paper.red, paper.green, paper.blue) + minOf(paper.red, paper.green, paper.blue)) / 2f, 0.01f)
            assertEquals(0.32f, (maxOf(ink.red, ink.green, ink.blue) + minOf(ink.red, ink.green, ink.blue)) / 2f, 0.01f)
            assertEquals(hue, Wcag.hue(paper), 1f)
        }
    }
}
