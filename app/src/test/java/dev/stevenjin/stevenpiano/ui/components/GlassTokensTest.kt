// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.ui.theme.CarbonPrimary
import dev.stevenjin.stevenpiano.ui.theme.CarbonSecondary
import dev.stevenjin.stevenpiano.ui.theme.CarbonTertiary
import dev.stevenjin.stevenpiano.ui.theme.GlassEdgeDark
import dev.stevenjin.stevenpiano.ui.theme.GlassEdgeLight
import dev.stevenjin.stevenpiano.ui.theme.GlassTokens
import dev.stevenjin.stevenpiano.ui.theme.InkHairline
import dev.stevenjin.stevenpiano.ui.theme.InkSurface
import dev.stevenjin.stevenpiano.ui.theme.PaperHairline
import dev.stevenjin.stevenpiano.ui.theme.PaperSurface
import dev.stevenjin.stevenpiano.ui.theme.SilverPrimary
import dev.stevenjin.stevenpiano.ui.theme.SilverSecondary
import dev.stevenjin.stevenpiano.ui.theme.SilverTertiary
import dev.stevenjin.stevenpiano.ui.theme.Wcag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Liquid Glass across the functional layer (DESIGN.md › v1.9, after v1.5 — M16), its blends
 * recomputed with the palette's WCAG formula ([Wcag]) against the worst backdrop the blur can bring:
 * pure white under the dark glass, pure black under the light one. Two fills: the bars' (0.72) and
 * the sheets', menus' and dialogs' (0.86). On a bar, text is the content colour and glyphs may be the
 * secondary grey; on a sheet, text may be the secondary grey too; nothing tertiary sits on either.
 * The primary action is the filled circle, never glass.
 */
class GlassTokensTest {
    /** A fill as it looks over the worst backdrop, dark and light. */
    private fun darkGlass(alpha: Float) = Wcag.over(InkSurface, alpha, Color.White)
    private fun lightGlass(alpha: Float) = Wcag.over(PaperSurface, alpha, Color.Black)

    @Test
    fun `the material - bars at 0_72, sheets menus and dialogs at 0_86, over a 24 dp blur`() {
        assertEquals(0.72f, GlassTokens.ContainerAlpha, 0f)
        assertEquals(0.86f, GlassTokens.SheetAlpha, 0f)
        assertEquals(GlassTokens.ContainerAlpha, GlassFill.Bar.alpha, 0f)
        assertEquals(GlassTokens.SheetAlpha, GlassFill.Sheet.alpha, 0f)
        assertEquals(24.dp, GlassTokens.Blur)
        assertEquals(1.dp, GlassTokens.Edge)
    }

    @Test
    fun `text on a bar clears 4_5 to 1 over the worst backdrop, 7 to 1 in fact`() {
        val dark = Wcag.contrast(SilverPrimary, darkGlass(GlassTokens.ContainerAlpha))
        val light = Wcag.contrast(CarbonPrimary, lightGlass(GlassTokens.ContainerAlpha))
        assertTrue("dark: $dark", dark >= 4.5)
        assertTrue("light: $light", light >= 4.5)
        assertEquals(7.1, dark, 0.05)
        assertEquals(8.3, light, 0.05)
    }

    @Test
    fun `on a bar secondary glyphs clear 3 to 1, and neither the secondary text nor the tertiary grey would clear 4_5`() {
        // The tab bar's unselected glyphs, the transport's Shuffle and Repeat while off: graphics, 3:1.
        assertTrue(Wcag.contrast(SilverSecondary, darkGlass(GlassTokens.ContainerAlpha)) >= 3.0)
        assertTrue(Wcag.contrast(CarbonSecondary, lightGlass(GlassTokens.ContainerAlpha)) >= 3.0)
        // Why a bar's text is the content colour (a header's byline, its progress lines, with content beneath).
        assertTrue(Wcag.contrast(SilverSecondary, darkGlass(GlassTokens.ContainerAlpha)) < 4.5)
        assertTrue(Wcag.contrast(CarbonSecondary, lightGlass(GlassTokens.ContainerAlpha)) < 4.5)
        assertTrue(Wcag.contrast(SilverTertiary, darkGlass(GlassTokens.ContainerAlpha)) < 3.0)
        assertTrue(Wcag.contrast(CarbonTertiary, lightGlass(GlassTokens.ContainerAlpha)) < 3.0)
    }

    @Test
    fun `on a sheet, menu or dialog the content colour and the secondary grey both clear 4_5 to 1, the tertiary grey would not`() {
        val primaryDark = Wcag.contrast(SilverPrimary, darkGlass(GlassTokens.SheetAlpha))
        val primaryLight = Wcag.contrast(CarbonPrimary, lightGlass(GlassTokens.SheetAlpha))
        assertEquals(11.8, primaryDark, 0.1)
        assertEquals(11.9, primaryLight, 0.1)
        val secondaryDark = Wcag.contrast(SilverSecondary, darkGlass(GlassTokens.SheetAlpha))
        val secondaryLight = Wcag.contrast(CarbonSecondary, lightGlass(GlassTokens.SheetAlpha))
        assertTrue("dark: $secondaryDark", secondaryDark >= 4.5)
        assertTrue("light: $secondaryLight", secondaryLight >= 4.5)
        assertEquals(5.25, secondaryDark, 0.08)
        assertEquals(4.56, secondaryLight, 0.06)
        // Why the tertiary grey gives way to the secondary on a sheet.
        assertTrue(Wcag.contrast(SilverTertiary, darkGlass(GlassTokens.SheetAlpha)) < 4.5)
        assertTrue(Wcag.contrast(CarbonTertiary, lightGlass(GlassTokens.SheetAlpha)) < 4.5)
    }

    @Test
    fun `0_86 is the least sheet fill, in hundredths, at which the secondary grey reads 4_5 to 1 on the paper`() {
        // At the brief's 0.84 it would read 4.3:1 over a black backdrop (a black artwork under the sheet).
        val at84 = Wcag.contrast(CarbonSecondary, lightGlass(0.84f))
        val at85 = Wcag.contrast(CarbonSecondary, lightGlass(0.85f))
        assertTrue("0.84: $at84", at84 < 4.5)
        assertTrue("0.85: $at85", at85 < 4.5)
        assertTrue(Wcag.contrast(CarbonSecondary, lightGlass(GlassTokens.SheetAlpha)) >= 4.5)
    }

    @Test
    fun `the primary action is the filled circle - its glyph the surface on the content colour, whatever lies beneath`() {
        // The existing pair: 17:1 and 16:1.
        val dark = Wcag.contrast(InkSurface, SilverPrimary)
        val light = Wcag.contrast(PaperSurface, CarbonPrimary)
        assertEquals(17.2, dark, 0.1)
        assertEquals(16.3, light, 0.1)
        // And the circle stands out from the glass band it sits on, over the worst backdrop (3:1 for a control's shape).
        assertTrue(Wcag.contrast(SilverPrimary, darkGlass(GlassTokens.ContainerAlpha)) >= 3.0)
        assertTrue(Wcag.contrast(CarbonPrimary, lightGlass(GlassTokens.ContainerAlpha)) >= 3.0)
    }

    @Test
    fun `the scroll-edge band thickens a bar's frost to a sheet's at the edge, over 24 dp, and 16 dp beside the rail`() {
        assertEquals(24.dp, GlassTokens.EdgeBand)
        assertEquals(16.dp, GlassTokens.RailBand)
        assertEquals(GlassTokens.SheetAlpha, GlassTokens.EdgeFadeAlpha, 0f)
        assertEquals(0.5f, GlassTokens.BandAlpha, 0.0001f)
        // Channel by channel (in floats): the band over the bar's fill is the sheet's fill, over any backdrop.
        fun over(top: Float, alpha: Float, under: Float) = top * alpha + under * (1 - alpha)
        for (surface in listOf(InkSurface, PaperSurface)) for (backdrop in listOf(Color.White, Color.Black, Color(0xFF7F3F1F))) {
            for ((s, b) in listOf(surface.red to backdrop.red, surface.green to backdrop.green, surface.blue to backdrop.blue)) {
                val banded = over(s, GlassTokens.BandAlpha, over(s, GlassTokens.ContainerAlpha, b))
                assertEquals(over(s, GlassTokens.SheetAlpha, b), banded, 0.0001f)
            }
        }
    }

    @Test
    fun `increase contrast - hairlines in the content colour at 0_4, stronger than the hairline token on both surfaces`() {
        assertEquals(0.4f, GlassTokens.ContrastHairlineAlpha, 0f)
        val dark = Wcag.contrast(Wcag.over(SilverPrimary, GlassTokens.ContrastHairlineAlpha, InkSurface), InkSurface)
        val light = Wcag.contrast(Wcag.over(CarbonPrimary, GlassTokens.ContrastHairlineAlpha, PaperSurface), PaperSurface)
        assertEquals(3.5, dark, 0.05)
        assertEquals(2.5, light, 0.05)
        assertTrue(dark > 2 * Wcag.contrast(InkHairline, InkSurface))
        assertTrue(light > 1.5 * Wcag.contrast(PaperHairline, PaperSurface))
    }

    @Test
    fun `the specular line is white, faint on the camera body and bright on the paper`() {
        for (edge in listOf(GlassEdgeDark, GlassEdgeLight)) {
            assertEquals(1f, edge.red, 0f)
            assertEquals(1f, edge.green, 0f)
            assertEquals(1f, edge.blue, 0f)
        }
        assertEquals(0.10f, GlassEdgeDark.alpha, 0.005f)
        assertEquals(0.70f, GlassEdgeLight.alpha, 0.005f)
    }
}
