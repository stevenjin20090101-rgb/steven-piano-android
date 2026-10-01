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

/**
 * The order of the Playlists listing (DESIGN.md › v1.10.1, D6), chosen with the pop-up button at the end of
 * its header row and kept as `playlistSort`; the web panel lists them the same way.
 */
enum class PlaylistSort(val label: String) {
    /** The person's playlists by when they were made, newest first, then the built-in ones in their fixed order. */
    NEWEST("Newest first"),

    /** As before 1.10.1: the built-in playlists first, in the order they were made, then the rest by name. */
    NAME("Name"),
}

/** A playlist's order as piece ids, rearranged, and the playlists' own order. Pure, so it is unit-tested; the library writes the result. */
object PlaylistOrder {
    /**
     * Every playlist of [all] (as the library lists them: by name, case ignored) in the order [sort] asks for.
     * [NEWEST][PlaylistSort.NEWEST]: the person's own (and those an import made) by id, newest first, then the
     * built-in ones in their fixed order, [builtInOrder] (the catalogue's keys: Popular, Recognisable, Epic on
     * piano), a key it doesn't name after them by id. [NAME][PlaylistSort.NAME]: exactly the order before
     * 1.10.1, the built-in ones first by id, then the rest by name.
     */
    fun listing(all: List<PlaylistSummary>, sort: PlaylistSort, builtInOrder: List<String>): List<PlaylistSummary> {
        val (builtIn, others) = all.partition { it.builtIn }
        return when (sort) {
            PlaylistSort.NAME -> builtIn.sortedBy { it.id } + others
            PlaylistSort.NEWEST -> others.sortedByDescending { it.id } +
                builtIn.sortedWith(compareBy({ builtInOrder.indexOf(it.builtInKey).let { at -> if (at < 0) Int.MAX_VALUE else at } }, { it.id }))
        }
    }

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
