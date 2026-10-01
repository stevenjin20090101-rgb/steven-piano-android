// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data

import dev.stevenjin.stevenpiano.data.db.PlaylistSummary
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

    // v1.10.1 — M28, D6: the playlists' own order.

    /** As the library lists them: by name, case ignored. */
    private val shelf = listOf(
        PlaylistSummary(2, "Bach", true, 40, 1_000),
        PlaylistSummary(11, "Epic on piano", false, 45, 1_000, builtIn = true, builtInKey = "epic"),
        PlaylistSummary(14, "MIDI", true, 265, 1_000),
        PlaylistSummary(8, "Popular", false, 17, 1_000, builtIn = true, builtInKey = "popular"),
        PlaylistSummary(9, "Recognisable", false, 12, 1_000, builtIn = true, builtInKey = "recognisable"),
        PlaylistSummary(5, "Road trip", false, 3, 1_000),
        PlaylistSummary(15, "Uploads", false, 1, 1_000),
    )
    private val catalogue = listOf("popular", "recognisable", "epic")

    @Test
    fun `newest first, the person's playlists by when they were made, the built-in ones after them in their fixed order`() {
        assertEquals(listOf(15L, 14L, 5L, 2L, 8L, 9L, 11L), PlaylistOrder.listing(shelf, PlaylistSort.NEWEST, catalogue).map { it.id })
        // Made out of order (Epic first, from the Epic zip alone), the built-in ones keep the catalogue's order.
        val epicFirst = shelf.map { if (it.builtInKey == "epic") it.copy(id = 1) else it }
        assertEquals(listOf(15L, 14L, 5L, 2L, 8L, 9L, 1L), PlaylistOrder.listing(epicFirst, PlaylistSort.NEWEST, catalogue).map { it.id })
        assertEquals("keys the order doesn't name go after it, by id", listOf(15L, 14L, 5L, 2L, 11L, 8L, 9L), PlaylistOrder.listing(shelf, PlaylistSort.NEWEST, listOf("epic")).map { it.id })
    }

    @Test
    fun `by name, exactly the order before 1_10_1, the built-in ones first as they were made, then by name`() {
        assertEquals(listOf(8L, 9L, 11L, 2L, 14L, 5L, 15L), PlaylistOrder.listing(shelf, PlaylistSort.NAME, catalogue).map { it.id })
        assertEquals("Newest first", PlaylistSort.NEWEST.label)
        assertEquals("Name", PlaylistSort.NAME.label)
        assertEquals("the default is the first", PlaylistSort.NEWEST, PlaylistSort.entries.first())
    }
}
