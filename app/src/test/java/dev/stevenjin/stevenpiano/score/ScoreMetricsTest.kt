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
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreMetricsTest {
    private val density = 2.625f   // a phone's xxhdpi

    private fun metrics(widthDp: Float, heightDp: Float, width: ScoreWidth = ScoreWidth.COMPACT, fontScale: Float = 1f, chordDp: Float = 0f) =
        ScoreMetrics.forPanel(
            width,
            widthDp * density,
            heightDp * density,
            density,
            headWidth = 1.18f * 6 * density,
            clefWidth = 2.74f * 6 * density,
            numberHeight = 16f * fontScale * density,
            chordHeight = chordDp * fontScale * density,
        )

    @Test
    fun `two pages side by side only when the panel itself is 840 dp wide`() {
        assertEquals(1, metrics(839f, 500f).pages)
        val two = metrics(840f, 500f, ScoreWidth.EXPANDED)
        assertEquals(2, two.pages)
        assertEquals((840f * density - two.pageGap) / 2, two.pageWidth, 0.01f)
        assertEquals(two.pageWidth + two.pageGap, two.slotLeft(1), 0.01f)
        assertEquals(2, metrics(1_100f, 500f, ScoreWidth.MEDIUM).pages)   // the panel's width counts, not the window's class
    }

    @Test
    fun `bars per system follow the window's width`() {
        assertEquals(2, metrics(379f, 500f, ScoreWidth.COMPACT).barsPerSystem)
        assertEquals(3, metrics(700f, 500f, ScoreWidth.MEDIUM).barsPerSystem)
        assertEquals(4, metrics(580f, 500f, ScoreWidth.EXPANDED).barsPerSystem)
    }

    @Test
    fun `a system is an 88 dp grand staff with a bar-number line above it, 32 dp apart`() {
        val m = metrics(379f, 400f)
        assertEquals(88f * density, m.grandStaffHeight, 0.01f)
        assertEquals(6f * density, m.space, 0.01f)
        assertEquals(32f * density, m.systemGap, 0.01f)
        assertEquals(3, m.systemsPerPage)
        for (row in 1 until m.systemsPerPage) {
            val previousBottom = m.staffTop(row - 1) + m.grandStaffHeight
            assertTrue(m.staffTop(row) - m.numberHeight - previousBottom >= m.systemGap - 0.01f)
        }
        assertTrue(m.staffTop(m.systemsPerPage - 1) + m.grandStaffHeight <= m.pageHeight)
    }

    @Test
    fun `systems per page come from the height, one at the short screen's 200 dp`() {
        assertEquals(1, metrics(379f, 200f).systemsPerPage)
        assertEquals(1, metrics(379f, 239f).systemsPerPage)
        assertEquals(2, metrics(379f, 265f).systemsPerPage)
        assertEquals(4, metrics(379f, 600f).systemsPerPage)
        assertEquals(1, metrics(379f, 90f).systemsPerPage)   // even when it cannot fit, one system is laid out
    }

    @Test
    fun `larger text reserves a taller bar-number line, and fewer systems fit`() {
        val normal = metrics(379f, 400f)
        val large = metrics(379f, 400f, fontScale = 2f)
        assertEquals(3, normal.systemsPerPage)
        assertEquals(2, large.systemsPerPage)
        assertTrue(large.staffTop(0) - large.firstSystemTop >= 32f * density - 0.01f)
    }

    @Test
    fun `room left over is spread above, between and below the systems`() {
        val m = metrics(379f, 460f)   // three systems and 60 dp to spare
        assertEquals(3, m.systemsPerPage)
        val extra = 60f * density / 4
        assertEquals(ScoreMetrics.PAD_TOP_DP * density + extra, m.firstSystemTop, 0.5f)
        assertEquals(m.numberHeight + m.grandStaffHeight + m.systemGap + extra, m.systemPitch, 0.5f)
    }

    @Test
    fun `chord names get a line of their own above each system's bar-number line`() {
        val plain = metrics(379f, 460f)
        val named = metrics(379f, 460f, chordDp = 29f)
        assertEquals(0f, plain.chordHeight, 0f)
        assertEquals(29f * density, named.chordHeight, 0.01f)
        // Every system's staff starts a chord line and a number line below its block's top.
        for (row in 0 until named.systemsPerPage) {
            assertEquals(named.firstSystemTop + row * named.systemPitch + named.chordHeight + named.numberHeight, named.staffTop(row), 0.01f)
            if (row > 0) {
                val previousBottom = named.staffTop(row - 1) + named.grandStaffHeight
                assertTrue(named.staffTop(row) - named.numberHeight - named.chordHeight - previousBottom >= named.systemGap - 0.01f)
            }
        }
        assertTrue(named.staffTop(named.systemsPerPage - 1) + named.grandStaffHeight <= named.pageHeight)
        // The line costs room: three systems fit without it, two with it; at twice the text size in 400 dp, one.
        assertEquals(3, plain.systemsPerPage)
        assertEquals(2, named.systemsPerPage)
        assertEquals(1, metrics(379f, 400f, fontScale = 2f, chordDp = 29f).systemsPerPage)
    }
}
