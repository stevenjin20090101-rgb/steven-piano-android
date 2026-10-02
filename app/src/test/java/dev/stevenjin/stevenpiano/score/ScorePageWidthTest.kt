// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.score

import org.junit.Assert.assertEquals
import org.junit.Test

/** Bars per system from the score page's own width (DESIGN.md › v1.12), not the window's class. */
class ScorePageWidthTest {
    private val density = 2.625f

    private fun fitting(widthDp: Float, heightDp: Float = 500f) =
        ScoreMetrics.fitting(widthDp * density, heightDp * density, density, headWidth = 1.18f * 6 * density, clefWidth = 2.74f * 6 * density)

    @Test
    fun `under 480 dp a page holds two bars a system, under 560 dp three, else four`() {
        assertEquals(ScoreWidth.COMPACT, ScoreWidth.forPage(479.9f))
        assertEquals(ScoreWidth.MEDIUM, ScoreWidth.forPage(480f))
        assertEquals(ScoreWidth.MEDIUM, ScoreWidth.forPage(559.9f))
        assertEquals(ScoreWidth.EXPANDED, ScoreWidth.forPage(560f))
        // A phone (379 dp), a medium pane (488 dp), a tablet upright (688 dp), a tablet on its side at half each (580 dp).
        assertEquals(listOf(2, 3, 4, 4), listOf(379f, 488f, 688f, 580f).map { fitting(it).barsPerSystem })
        // Otherwise the geometry is forPanel's, unchanged.
        for (width in listOf(240f, 479f, 480f, 700f, 839f, 840f, 1_168f)) {
            val fit = fitting(width, 600f)
            assertEquals(
                ScoreMetrics.forPanel(ScoreWidth.forPage(fit.pageWidth / density), width * density, 600f * density, density, 1.18f * 6 * density, 2.74f * 6 * density),
                fit,
            )
        }
    }

    @Test
    fun `840 dp gives two pages of two bars, and a bar keeps about its width either side of it`() {
        val two = fitting(840f)
        assertEquals(2, two.pages)
        assertEquals(2, two.barsPerSystem)
        assertEquals(412f * density, two.pageWidth, 0.01f)
        val one = fitting(839f)
        assertEquals(1, one.pages)
        assertEquals(4, one.barsPerSystem)
        assertEquals(one.pageWidth / 4, two.pageWidth / 2, 5f * density)
        assertEquals("two pages of 576 dp", 4, fitting(1_168f).barsPerSystem)
    }
}
