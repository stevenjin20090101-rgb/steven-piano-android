// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data

/** A playlist's order as piece ids, rearranged. Pure, so it is unit-tested; the library writes the result. */
object PlaylistOrder {
    /** [order] with [pieceId] moved [delta] places (-1 is up, +1 down), stopping at either end. */
    fun move(order: List<Long>, pieceId: Long, delta: Int): List<Long> {
        val from = order.indexOf(pieceId)
        if (from < 0) return order
        val to = (from + delta).coerceIn(0, order.lastIndex)
        if (to == from) return order
        return order.toMutableList().apply { add(to, removeAt(from)) }
    }

    /**
     * The whole playlist [order] once the pieces in [shown] were dragged into shown's order. The
     * list on screen may be a search's subset: its pieces take the places they held between them,
     * in their new order, and every other piece stays where it was. Ids no longer in the playlist
     * are ignored.
     */
    fun reordered(order: List<Long>, shown: List<Long>): List<Long> {
        val inPlaylist = order.toHashSet()
        val moving = shown.filter { it in inPlaylist }.distinct()
        val movingSet = moving.toHashSet()
        val next = moving.iterator()
        return order.map { if (it in movingSet) next.next() else it }
    }
}
