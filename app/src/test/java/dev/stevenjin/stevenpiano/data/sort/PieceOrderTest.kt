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
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

/** M82: the piece lists' orders, Popular's above all: the world's rank first, this piano's plays pushing songs up. */
class PieceOrderTest {
    private fun piece(
        id: Long,
        title: String,
        rank: Int? = null,
        plays: Int = 0,
        lastPlayedAt: Long? = null,
        stats: PieceStats? = null,
    ) = PieceEntity(
        id = id, title = title, composer = "", composerKey = "", composerShort = "", collection = null, sha256 = "s$id",
        fileName = "s$id.mid", sourceName = "", sizeBytes = 0, durationMs = 0, noteCount = 0, addedAt = id, playCount = plays,
        lastPlayedAt = lastPlayedAt, searchText = title.lowercase(), titleKey = title.lowercase(), worldRank = rank,
        busiestPerSecond = stats?.busiestPerSecond, mostKeys = stats?.mostKeys, mostKeysMoments = stats?.mostKeysMoments,
        leapsPerSecond = stats?.leapsPerSecond, keyRange = stats?.keyRange,
    )

    private fun measures(pieces: List<PieceEntity>) =
        pieces.map { PieceMeasure(it.busiestPerSecond, it.mostKeys, it.leapsPerSecond, it.keyRange, it.worldRank) }

    private fun titles(pieces: List<PieceEntity>, sort: PieceSort, library: List<PieceEntity> = pieces) =
        PieceOrder.sorted(pieces.sortedBy { it.titleKey }, sort, measures(library)).map { it.title }

    /** The rank whose place on the log scale between [low] and [high] is [at] (0 to 1). */
    private fun rankAt(at: Double, low: Int, high: Int): Int = (exp(ln(1.0 + low) + at * (ln(1.0 + high) - ln(1.0 + low))) - 1).toInt()

    @Test
    fun `Popular - the world's rank first, then the unranked by plays, then by title`() {
        val pieces = listOf(
            piece(1, "Bohemian Rhapsody", rank = 950_000),
            piece(2, "Clair de lune", rank = 400_000),
            piece(3, "Arabesque", rank = 20_000),
            piece(4, "Etude made here", plays = 9),
            piece(5, "B unranked", plays = 2),
            piece(6, "A unranked", plays = 2),
            piece(7, "Z unranked"),
        )
        assertEquals(
            listOf("Bohemian Rhapsody", "Clair de lune", "Arabesque", "Etude made here", "A unranked", "B unranked", "Z unranked"),
            titles(pieces, PieceSort.POPULAR),
        )
    }

    @Test
    fun `Popular - about 20 plays here move a song up noticeably, a couple do not`() {
        // Five ranked songs a tenth of the library's log scale apart (from 1,000 to 1,000,000): Rising is fourth.
        fun library(plays: Int) = listOf(
            piece(1, "Top", rank = 1_000_000),
            piece(2, "Second", rank = rankAt(0.9, 1_000, 1_000_000)),
            piece(3, "Third", rank = rankAt(0.8, 1_000, 1_000_000)),
            piece(4, "Rising", rank = rankAt(0.7, 1_000, 1_000_000), plays = plays),
            piece(5, "Bottom", rank = 1_000),
        )
        assertEquals(listOf("Top", "Second", "Third", "Rising", "Bottom"), titles(library(plays = 2), PieceSort.POPULAR))
        assertEquals("20 plays climb past one a tenth of the scale above", listOf("Top", "Second", "Rising", "Third", "Bottom"), titles(library(plays = 20), PieceSort.POPULAR))
        assertEquals(listOf("Top", "Rising", "Second", "Third", "Bottom"), titles(library(plays = 80), PieceSort.POPULAR))
        // A play is never worth more than the world: a hundred plays don't lift the bottom song past the top one.
        assertEquals("Top", titles(library(0).map { if (it.id == 5L) it.copy(playCount = 100) else it }, PieceSort.POPULAR).first())
    }

    @Test
    fun `Popular ranks against the whole library, so a list of two keeps their places`() {
        val library = listOf(piece(1, "Hit", rank = 900_000), piece(2, "Middle", rank = 100_000, plays = 20), piece(3, "Rare", rank = 1_000))
        assertEquals(listOf("Hit", "Middle"), titles(library.take(2), PieceSort.POPULAR, library))
    }

    @Test
    fun `Hardest, Most keys at once and Recently played`() {
        val hard = piece(1, "Hard", stats = PieceStats(18.0, 10, 2, 60.0, 80), lastPlayedAt = 100)
        val middling = piece(2, "Middling", stats = PieceStats(9.0, 6, 9, 20.0, 50), lastPlayedAt = 300)
        val chords = piece(3, "Chords", stats = PieceStats(4.0, 10, 5, 5.0, 40))
        val easy = piece(4, "Easy", stats = PieceStats(2.0, 2, 1, 0.0, 12), lastPlayedAt = 200)
        val unmeasured = piece(5, "Aaa not measured yet")
        val all = listOf(hard, middling, chords, easy, unmeasured)
        assertEquals(listOf("Hard", "Middling", "Chords", "Easy", "Aaa not measured yet"), titles(all, PieceSort.HARDEST))
        assertEquals("a tie broken by the moments that reach the peak", listOf("Chords", "Hard", "Middling", "Easy", "Aaa not measured yet"), titles(all, PieceSort.MOST_KEYS))
        assertEquals(listOf("Middling", "Easy", "Hard", "Aaa not measured yet", "Chords"), titles(all, PieceSort.RECENTLY_PLAYED))
        assertEquals("A–Z is the lists' own order", all.sortedBy { it.titleKey }.map { it.title }, titles(all, PieceSort.TITLE))
    }
}
