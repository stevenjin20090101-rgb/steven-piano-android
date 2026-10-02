// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rows' entrance (DESIGN.md › v1.14 — motion): the first ten rows a visit brings, 12 ms apart, each once; never a row scrolled in. */
class ListEntranceTest {
    private var clock = 1_000L
    private val entrance = ListEntrance { clock }

    @Test
    fun `the visit's first ten rows ease in, twelve milliseconds apart, and no others`() {
        val eased = (0 until 14).filter { entrance.claims("p$it", it) }
        assertEquals((0 until 10).toList(), eased)
        assertEquals(listOf(0, 12, 24, 108), listOf(0, 1, 2, 9).map(ListEntrance::delayOf))
    }

    @Test
    fun `each row once per visit, even composed again`() {
        assertTrue(entrance.claims("p1", 0))
        assertFalse(entrance.claims("p1", 0))
    }

    @Test
    fun `a row scrolled into view later just appears`() {
        assertTrue(entrance.claims("p0", 0))
        clock += ListEntrance.WINDOW_MS + 1
        assertFalse(entrance.claims("p5", 5))   // the rows above were scrolled away and come back
        assertFalse(entrance.claims("p20", 3))  // a row added at the top while the listing shows
    }

    @Test
    fun `a visit that opens scrolled down eases nothing in`() {
        assertFalse(entrance.claims("p40", 40))
        clock += 500
        assertFalse(entrance.claims("p0", 0))
    }

    @Test
    fun `a new visit, a new entrance`() {
        assertTrue(entrance.claims("p0", 0))
        clock += 10_000
        val next = ListEntrance { clock }
        assertTrue(next.claims("p0", 0))
    }
}
