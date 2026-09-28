// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Schema v2 against Room's own export: the migration's statements are the ones Room generated
 * in `app/schemas/…/2.json`, v1's export is kept beside it, and the position backfill numbers
 * each playlist from 0. The real database check (install 1.1, add a playlist, install 1.2 over
 * it, Room validates on open) runs on the emulator.
 */
class SchemaV2Test {
    private val v1 = ExportedSchema.read(1)
    private val v2 = ExportedSchema.read(2)

    @Test
    fun `positions number each playlist's links from 0 in the order given`() {
        assertArrayEquals(intArrayOf(0, 1, 2, 0, 1, 0), SchemaV2.positions(longArrayOf(1, 1, 1, 2, 2, 5)))
        assertArrayEquals(intArrayOf(), SchemaV2.positions(longArrayOf()))
    }

    @Test
    fun `positions count per playlist however the playlists interleave`() {
        assertArrayEquals(intArrayOf(0, 0, 1, 1, 2), SchemaV2.positions(longArrayOf(7, 3, 7, 3, 7)))
    }

    @Test
    fun `the backfill orders each playlist by when pieces were added, then title`() {
        val order = SchemaV2.LINKS_IN_ORDER
        assertTrue(order, order.endsWith("ORDER BY cp.collectionId, cp.addedAt, p.titleKey, cp.pieceId"))
        assertTrue(!order.contains("OVER", ignoreCase = true))   // no window functions on API 26
    }

    @Test
    fun `the migration creates the new index and the artwork table with 2_json's statements`() {
        assertEquals(v2.index("collection_pieces", "index_collection_pieces_collectionId_position"), SchemaV2.CREATE_POSITION_INDEX)
        assertEquals(v2.table("artwork"), SchemaV2.CREATE_ARTWORK)
        assertEquals(listOf(SchemaV2.ADD_POSITION, SchemaV2.CREATE_POSITION_INDEX, SchemaV2.CREATE_ARTWORK), SchemaV2.DDL)
    }

    @Test
    fun `v2's playlist links are v1's plus exactly the position column, as 2_json declares it`() {
        val linksV2 = v2.table("collection_pieces")
        assertTrue(linksV2, linksV2.contains(", ${SchemaV2.POSITION_COLUMN}, "))
        assertEquals(v1.table("collection_pieces"), linksV2.replace(", ${SchemaV2.POSITION_COLUMN}", ""))
        assertEquals("ALTER TABLE `collection_pieces` ADD COLUMN ${SchemaV2.POSITION_COLUMN}", SchemaV2.ADD_POSITION)
        assertEquals(
            v1.index("collection_pieces", "index_collection_pieces_pieceId"),
            v2.index("collection_pieces", "index_collection_pieces_pieceId"),
        )
    }

    @Test
    fun `everything else is as v1 left it`() {
        assertEquals(1, v1.version)
        assertEquals(2, v2.version)
        for (table in listOf("pieces", "collections")) {
            assertEquals(v1.table(table), v2.table(table))
            assertEquals(v1.indices(table), v2.indices(table))
        }
        assertEquals(v1.views, v2.views)
        assertEquals(setOf("pieces", "collections", "collection_pieces", "artwork"), v2.tables.keys)
    }
}
