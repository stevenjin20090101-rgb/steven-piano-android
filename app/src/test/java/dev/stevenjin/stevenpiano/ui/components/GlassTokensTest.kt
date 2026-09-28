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
import dev.stevenjin.stevenpiano.ui.theme.InkSurface
import dev.stevenjin.stevenpiano.ui.theme.PaperSurface
import dev.stevenjin.stevenpiano.ui.theme.SilverPrimary
import dev.stevenjin.stevenpiano.ui.theme.SilverSecondary
import dev.stevenjin.stevenpiano.ui.theme.SilverTertiary
import dev.stevenjin.stevenpiano.ui.theme.Wcag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glass of the floating controls (DESIGN.md › v1.5 — M16), its blends recomputed with the
 * palette's WCAG formula ([Wcag]) against the worst backdrop the blur can bring: pure white under
 * the dark bar, pure black under the light one. Text on glass is the content colour; glyphs may be
 * the secondary grey; nothing tertiary sits on glass.
 */
class GlassTokensTest {
    /** The container as it looks over the worst backdrop, dark and light. */
    private fun darkGlass(alpha: Float) = Wcag.over(InkSurface, alpha, Color.White)
    private fun lightGlass(alpha: Float) = Wcag.over(PaperSurface, alpha, Color.Black)

    @Test
    fun `the material is the surface at 0_72 over a 24 dp blur, 0_60 for the play circle alone`() {
        assertEquals(0.72f, GlassTokens.ContainerAlpha, 0f)
        assertEquals(0.60f, GlassTokens.LensAlpha, 0f)
        assertEquals(24.dp, GlassTokens.Blur)
        assertEquals(1.dp, GlassTokens.Edge)
    }

    @Test
    fun `text on the container clears 4_5 to 1 over the worst backdrop, 7 to 1 in fact`() {
        val dark = Wcag.contrast(SilverPrimary, darkGlass(GlassTokens.ContainerAlpha))
        val light = Wcag.contrast(CarbonPrimary, lightGlass(GlassTokens.ContainerAlpha))
        assertTrue("dark: $dark", dark >= 4.5)
        assertTrue("light: $light", light >= 4.5)
        assertEquals(7.1, dark, 0.05)
        assertEquals(8.3, light, 0.05)
    }

    @Test
    fun `the play glyph clears 3 to 1 on the clearer circle over the worst backdrop`() {
        val dark = Wcag.contrast(SilverPrimary, darkGlass(GlassTokens.LensAlpha))
        val light = Wcag.contrast(CarbonPrimary, lightGlass(GlassTokens.LensAlpha))
        assertTrue("dark: $dark", dark >= 3.0)
        assertTrue("light: $light", light >= 3.0)
        assertEquals(4.5, dark, 0.06)
        assertEquals(5.8, light, 0.05)
    }

    @Test
    fun `secondary glyphs clear 3 to 1 on the container, and the tertiary grey would not`() {
        // The tab bar's unselected glyphs, and the transport's Shuffle and Repeat while off: graphics, 3:1.
        assertTrue(Wcag.contrast(SilverSecondary, darkGlass(GlassTokens.ContainerAlpha)) >= 3.0)
        assertTrue(Wcag.contrast(CarbonSecondary, lightGlass(GlassTokens.ContainerAlpha)) >= 3.0)
        // Why the glass never carries the tertiary grey (or secondary text, 3.2:1): below 3:1 at worst.
        assertTrue(Wcag.contrast(SilverTertiary, darkGlass(GlassTokens.ContainerAlpha)) < 3.0)
        assertTrue(Wcag.contrast(CarbonTertiary, lightGlass(GlassTokens.ContainerAlpha)) < 3.0)
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
