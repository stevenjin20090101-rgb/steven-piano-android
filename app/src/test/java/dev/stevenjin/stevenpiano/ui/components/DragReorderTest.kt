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
import org.junit.Assert.assertNull
import org.junit.Test

class DragReorderTest {
    /** A header (not movable) then rows of 56 px, laid out one after the other. */
    private val header = ItemSpan(0, "header", 0, 112)
    private val rows = (1..6).map { i -> ItemSpan(i, "p$i", 112 + (i - 1) * 56, 56) }
    private val items = listOf(header) + rows
    private val movable: (Any) -> Boolean = { it != "header" }

    @Test
    fun `a row stays until it passes the middle of the next one`() {
        val third = rows[2]   // index 3, 224..280; the next row's middle is 308
        assertNull(targetIndex(third, 0f, items, movable))
        assertNull(targetIndex(third, 27f, items, movable))    // bottom at 307
        assertEquals(4, targetIndex(third, 29f, items, movable))
    }

    @Test
    fun `a long drag passes several rows at once`() {
        assertEquals(5, targetIndex(rows[2], 90f, items, movable))   // bottom at 370, past 364
        assertEquals(6, targetIndex(rows[2], 1_000f, items, movable))
    }

    @Test
    fun `dragging up passes the middles above, never a row that cannot move`() {
        val fourth = rows[3]   // index 4, 280..336
        assertNull(targetIndex(fourth, -27f, items, movable))    // top at 253, the row above's middle is 252
        assertEquals(3, targetIndex(fourth, -29f, items, movable))
        assertEquals(1, targetIndex(fourth, -1_000f, items, movable))
    }

    @Test
    fun `rows of different heights do not swap back and forth`() {
        // A 56 px row over a 100 px one: it moves once its bottom passes 206, the tall row's middle.
        val short = ItemSpan(1, "p1", 100, 56)
        val tall = ItemSpan(2, "p2", 156, 100)
        assertNull(targetIndex(short, 50f, listOf(short, tall), movable))
        assertEquals(2, targetIndex(short, 51f, listOf(short, tall), movable))
        // Laid out again: the tall row at 100, the short one at 200; the finger has not moved, so
        // the short row is drawn 49 px above its new place, and the tall row's middle (150) is not passed.
        val tallAfter = ItemSpan(1, "p2", 100, 100)
        val shortAfter = ItemSpan(2, "p1", 200, 56)
        assertNull(targetIndex(shortAfter, -49f, listOf(tallAfter, shortAfter), movable))
    }

    @Test
    fun `moved takes a key to another key's place`() {
        assertEquals(listOf(2L, 3L, 1L, 4L), moved(listOf(1L, 2L, 3L, 4L), 1L, 3L))
        assertEquals(listOf(4L, 1L, 2L, 3L), moved(listOf(1L, 2L, 3L, 4L), 4L, 1L))
        assertNull(moved(listOf(1L, 2L), 1L, 9L))
    }

    @Test
    fun `reorderedBy shows items as a drag left them, newcomers at the end`() {
        val items = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b"), reorderedBy(items, listOf("c", "a", "b")) { it })
        assertEquals(listOf("b", "a", "c"), reorderedBy(items, listOf("b", "a")) { it })
        assertEquals(listOf("b", "a"), reorderedBy(listOf("a", "b"), listOf("b", "gone", "a")) { it })
    }
}
