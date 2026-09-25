// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistOrderTest {
    private val order = listOf(10L, 20L, 30L, 40L)

    @Test
    fun `move up and down one place, stopping at either end`() {
        assertEquals(listOf(10L, 30L, 20L, 40L), PlaylistOrder.move(order, 30, -1))
        assertEquals(listOf(10L, 30L, 20L, 40L), PlaylistOrder.move(order, 20, 1))
        assertEquals(order, PlaylistOrder.move(order, 10, -1))
        assertEquals(order, PlaylistOrder.move(order, 40, 1))
        assertEquals(order, PlaylistOrder.move(order, 99, 1))
    }

    @Test
    fun `a drag over the whole list is the new order`() {
        assertEquals(listOf(40L, 10L, 20L, 30L), PlaylistOrder.reordered(order, listOf(40, 10, 20, 30)))
    }

    @Test
    fun `a drag over a search's subset keeps the other pieces where they were`() {
        // Search showed 20 and 40; 40 was dragged above 20.
        assertEquals(listOf(10L, 40L, 30L, 20L), PlaylistOrder.reordered(order, listOf(40, 20)))
    }

    @Test
    fun `pieces removed meanwhile are ignored, and nothing is duplicated`() {
        assertEquals(listOf(30L, 20L, 10L, 40L), PlaylistOrder.reordered(order, listOf(30, 99, 20, 30, 10)))
    }
}
