// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.sort

import dev.stevenjin.stevenpiano.data.db.PieceEntity
import dev.stevenjin.stevenpiano.data.db.PieceMeasure
import kotlin.math.ln

/**
 * The order of the Library's piece lists (M82): Pieces, Favorites, Disney, a composer's pieces and a search's results,
 * chosen with the pop-up button at the end of their header row and kept as `pieceSort`, as the playlists' order is (its
 * words are `ui.LibraryCopy.sortLabel`'s).
 * Recent keeps its own order, a playlist its own. The tablet's alone: the web panel lists pieces by title.
 */
enum class PieceSort {
    /** By title, accents ignored: the order before M82, and the default. */
    TITLE,

    /** The world's popularity (Deezer's rank) first, then this piano's own plays: [PieceOrder.popularity]. */
    POPULAR,

    /** Speed, chords, leaps and range together: [PieceOrder.hardness]. */
    HARDEST,

    /** The most keys held down together, then the most moments that reach it. */
    MOST_KEYS,

    /** When it last started on this piano, newest first; never played last. */
    RECENTLY_PLAYED,
    ;

    /** Whether the order ranks a piece against the whole library, so the listing needs every piece's [PieceMeasure]. */
    val ranksLibrary: Boolean get() = this == POPULAR || this == HARDEST
}

/**
 * The pieces of a list in a [PieceSort]'s order. Pure, so it is unit-tested. The lists arrive by title, and every order
 * is a stable sort of that, so pieces that tie (and those with nothing to sort by, which come last) stay by title.
 * Hardest and Popular rank a piece against [library], every piece's measures, so a piece stands the same in every list.
 */
object PieceOrder {
    /**
     * A play's weight beside the world's rank in Popular: its score is the rank's place on the library's log scale (0 the
     * least popular piece found, 1 the most) plus this times ln(1 + plays). Twenty plays add about 0.15, enough to climb
     * past the pieces up to a seventh of the scale above it; one play about 0.035.
     */
    const val PLAY_WEIGHT = 0.05

    fun sorted(pieces: List<PieceEntity>, sort: PieceSort, library: List<PieceMeasure> = emptyList()): List<PieceEntity> = when (sort) {
        PieceSort.TITLE -> pieces
        PieceSort.RECENTLY_PLAYED -> pieces.sortedWith(compareBy({ it.lastPlayedAt == null }, { -(it.lastPlayedAt ?: 0L) }))
        PieceSort.MOST_KEYS -> pieces.sortedWith(compareBy({ it.mostKeys == null }, { -(it.mostKeys ?: 0) }, { -(it.mostKeysMoments ?: 0) }))
        PieceSort.HARDEST -> {
            val hardness = hardness(library)
            val scores = pieces.associate { it.id to hardness(it) }
            pieces.sortedWith(compareBy({ scores[it.id] == null }, { -(scores[it.id] ?: 0.0) }))
        }
        PieceSort.POPULAR -> {
            val popularity = popularity(library)
            val scores = pieces.associate { it.id to popularity(it) }
            // The ranked first, by score; then the rest by plays (then by title).
            pieces.sortedWith(compareBy({ scores[it.id] == null }, { -(scores[it.id] ?: 0.0) }, { -it.playCount }))
        }
    }

    /**
     * How hard a piece is against [library]: the mean of its four measures' percentile ranks among the library's measured
     * pieces (each the share below it, half of those equal counted below): the busiest 10 s, the most keys at once, the
     * leaps and the range. Null for a piece not measured yet.
     */
    fun hardness(library: List<PieceMeasure>): (PieceEntity) -> Double? {
        val measured = library.filter { it.busiestPerSecond != null && it.mostKeys != null && it.leapsPerSecond != null && it.keyRange != null }
        val busiest = measured.map { it.busiestPerSecond!! }.sorted().toDoubleArray()
        val keys = measured.map { it.mostKeys!!.toDouble() }.sorted().toDoubleArray()
        val leaps = measured.map { it.leapsPerSecond!! }.sorted().toDoubleArray()
        val range = measured.map { it.keyRange!!.toDouble() }.sorted().toDoubleArray()
        return { piece ->
            val b = piece.busiestPerSecond
            val k = piece.mostKeys
            val l = piece.leapsPerSecond
            val r = piece.keyRange
            if (b == null || k == null || l == null || r == null || measured.isEmpty()) {
                null
            } else {
                (percentile(busiest, b) + percentile(keys, k.toDouble()) + percentile(leaps, l) + percentile(range, r.toDouble())) / 4
            }
        }
    }

    /**
     * How popular a piece is against [library]: its world rank on the log scale the library's ranked pieces span (0 the
     * lowest, 1 the highest; 1 when they all share one rank), plus [PLAY_WEIGHT] × ln(1 + its plays here). Null for a piece
     * with no rank (not found, or not looked up yet).
     */
    fun popularity(library: List<PieceMeasure>): (PieceEntity) -> Double? {
        val logs = library.mapNotNull { it.worldRank?.let(::logRank) }
        val low = logs.minOrNull() ?: 0.0
        val high = logs.maxOrNull() ?: 0.0
        return { piece ->
            piece.worldRank?.let { rank ->
                val scale = if (high > low) ((logRank(rank) - low) / (high - low)).coerceIn(0.0, 1.0) else 1.0
                scale + PLAY_WEIGHT * ln(1.0 + piece.playCount.coerceAtLeast(0))
            }
        }
    }

    private fun logRank(rank: Int): Double = ln(1.0 + rank.coerceAtLeast(0))

    /** [value]'s percentile rank in [sorted]: the share of values below it, half of those equal to it counted below. */
    private fun percentile(sorted: DoubleArray, value: Double): Double {
        val below = bound(sorted, value, strict = true)
        val upTo = bound(sorted, value, strict = false)
        return (below + (upTo - below) / 2.0) / sorted.size
    }

    /** How many of [sorted] are below [value] ([strict]) or at most it. */
    private fun bound(sorted: DoubleArray, value: Double, strict: Boolean): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (if (strict) sorted[mid] < value else sorted[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
