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

class PageTurnTest {
    /** Pages shown, left to right, with the cursor in [system] of [systems] at [perPage] a page. */
    private fun shown(system: Int, systems: Int = 20, perPage: Int = 4, pages: Int = 2) =
        PageTurn.pagesShown(system, systems, perPage, pages).toList()

    @Test
    fun `one page shows the cursor's page`() {
        assertEquals(listOf(0), shown(0, pages = 1))
        assertEquals(listOf(0), shown(3, pages = 1))
        assertEquals(listOf(1), shown(4, pages = 1))
        assertEquals(listOf(4), shown(19, pages = 1))
    }

    @Test
    fun `two pages open at the first two`() {
        assertEquals(listOf(0, 1), shown(0))
        assertEquals(listOf(0, 1), shown(2))
    }

    @Test
    fun `the cursor's page keeps its slot, and the left page turns while the right one is finished`() {
        assertEquals(listOf(0, 1), shown(3))     // page 0's last system: page 1 is already open
        assertEquals(listOf(0, 1), shown(4))     // page 1, right: the left keeps page 0 until...
        assertEquals(listOf(0, 1), shown(6))
        assertEquals(listOf(2, 1), shown(7))     // ...page 1's last system: the left turns to page 2
        assertEquals(listOf(2, 1), shown(8))     // page 2, left: the right keeps page 1
        assertEquals(listOf(2, 3), shown(11))    // page 2's last system: the right turns to page 3
        assertEquals(listOf(4, 3), shown(15))
    }

    @Test
    fun `at the end the other slot keeps the page before`() {
        // Ten systems, four a page: pages 0 (0-3), 1 (4-7), 2 (8-9).
        assertEquals(listOf(2, 1), shown(8, systems = 10))
        assertEquals(listOf(2, 1), shown(9, systems = 10))
        assertEquals(listOf(2, 1), shown(50, systems = 10))   // past the end: the last system
    }

    @Test
    fun `with one system a page the other slot always shows the next`() {
        assertEquals(listOf(0, 1), shown(0, perPage = 1))
        assertEquals(listOf(2, 1), shown(1, perPage = 1))
        assertEquals(listOf(2, 3), shown(2, perPage = 1))
        assertEquals(listOf(4, 3), shown(3, perPage = 1))
        assertEquals(listOf(4, 3), shown(4, systems = 5, perPage = 1))
    }

    @Test
    fun `a piece of one page leaves the right slot empty`() {
        assertEquals(listOf(0, -1), shown(2, systems = 3))
        assertEquals(listOf(-1, -1), shown(0, systems = 0))
    }

    @Test
    fun `visible systems are the pages' systems`() {
        assertEquals(listOf(8..11, 4..7), PageTurn.visibleSystems(7, 20, 4, 2))
        assertEquals(listOf(8..9, 4..7), PageTurn.visibleSystems(9, 10, 4, 2))
        assertEquals(listOf(0..2, IntRange.EMPTY), PageTurn.visibleSystems(0, 3, 4, 2))
    }

    @Test
    fun `browsing shows a page and the one after, even pages on the left`() {
        assertEquals(listOf(2, 3), PageTurn.browsing(3, 5, 2).toList())
        assertEquals(listOf(4, -1), PageTurn.browsing(4, 5, 2).toList())
        assertEquals(listOf(4, -1), PageTurn.browsing(9, 5, 2).toList())
        assertEquals(listOf(0, 1), PageTurn.browsing(-2, 5, 2).toList())
        assertEquals(listOf(3), PageTurn.browsing(3, 5, 1).toList())
    }
}
