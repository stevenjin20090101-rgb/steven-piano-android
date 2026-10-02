// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.data.db.GenerationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep or Discard on Studio's history (v1.12 — M30): a Studio piece waits while its turn says "made"; a recording
 * still waits in the old DataStore set; the first start after the upgrade imports the old set exactly once (the
 * backfill's "kept" turns of the pieces it named wait again), and a set that can't be read is tried again later.
 * A mistake here would lose a person's Keep or Discard, or bring back a question already answered.
 */
class RoomReviewTest {
    /** Studio's history as the DAO keeps it: just the rows, with the same rules as [dev.stevenjin.stevenpiano.data.db.GenerationSql]. */
    private class History(vararg rows: GenerationEntity) : Generations {
        val rows = rows.toMutableList()

        fun outcome(pieceId: Long): String = rows.single { it.pieceId == pieceId }.outcome

        override suspend fun begin(turn: GenerationEntity): Long = (rows.maxOfOrNull { it.id } ?: 0L).plus(1).also { rows += turn.copy(id = it) }

        override suspend fun update(id: Long, change: (GenerationEntity) -> GenerationEntity) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = change(rows[i])
        }

        override suspend fun interruptPending() = Unit

        override fun recent(limit: Int): Flow<List<GenerationEntity>> = flowOf(rows.toList())

        override suspend fun get(id: Long): GenerationEntity? = rows.firstOrNull { it.id == id }

        override suspend fun forPiece(pieceId: Long): GenerationEntity? = rows.firstOrNull { it.pieceId == pieceId }

        override suspend fun undecided(): List<Long> = rows.filter { it.undecided }.sortedBy { it.createdAt }.mapNotNull { it.pieceId }

        override suspend fun reopen(ids: Collection<Long>) {
            rows.replaceAll { if (it.outcome == GenerationEntity.KEPT && it.pieceId in ids) it.copy(outcome = GenerationEntity.MADE) else it }
        }

        override suspend fun decide(pieceId: Long, kept: Boolean): Boolean {
            val i = rows.indexOfFirst { it.pieceId == pieceId && it.outcome == GenerationEntity.MADE }
            if (i < 0) return false
            rows[i] = rows[i].copy(outcome = if (kept) GenerationEntity.KEPT else GenerationEntity.DISCARDED)
            return true
        }

        override suspend fun madeHere(): List<Long> = rows.mapNotNull { it.pieceId }.reversed()

        override suspend fun remove(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun trim(keep: Int) = Unit

        override suspend fun withoutCover(): List<GenerationEntity> = emptyList()

        override suspend fun setCover(id: Long, kind: String, seed: Long) = Unit
    }

    /** The "studio" DataStore's set and flag; [readable] false is a store that can't be read. */
    private class Old(vararg ids: Long) : WaitingSet {
        var set = ids.toSet()
        var flag = false
        var readable = true

        override suspend fun read(): Set<Long>? = if (readable) set else null

        override suspend fun imported(): Boolean = readable && flag

        override suspend fun edit(imported: Boolean?, change: (Set<Long>) -> Set<Long>): Boolean {
            if (!readable) return false
            set = change(set)
            if (imported != null) flag = imported
            return true
        }
    }

    /** What the migration leaves: a turn for each piece apps 1.7 to 1.11 made, every one "kept". */
    private fun backfilled() = History(
        GenerationEntity(id = 1, createdAt = 20, kind = "compose", pieceId = 4, outcome = GenerationEntity.KEPT),
        GenerationEntity(id = 2, createdAt = 21, kind = "transcribe", pieceId = 5, outcome = GenerationEntity.KEPT),
        GenerationEntity(id = 3, createdAt = 22, kind = "transcribe", pieceId = 6, outcome = GenerationEntity.KEPT),
    )

    @Test
    fun `the first start imports the old set once - Studio's pieces wait on their turns, a recording stays in the set`() = runBlocking {
        val history = backfilled()
        val old = Old(4, 6, 99)   // 99: a recording, which has no turn
        val review = RoomReview(history, old)
        assertEquals(setOf(4L, 6L, 99L), review.undecided())
        assertEquals(listOf("made", "kept", "made"), listOf(4L, 5L, 6L).map(history::outcome))
        assertEquals(setOf(99L), old.set)
        assertTrue(old.flag)
        // Keep piece 4; the next start must not ask about it again.
        review.decided(4, kept = true)
        assertEquals("kept", history.outcome(4))
        assertEquals(setOf(6L, 99L), RoomReview(history, old).undecided())
    }

    @Test
    fun `a set that can't be read imports nothing now and is tried again at the next start`() = runBlocking {
        val history = backfilled()
        val old = Old(5).apply { readable = false }
        assertEquals(emptySet<Long>(), RoomReview(history, old).undecided())
        assertEquals("kept", history.outcome(5))
        assertFalse(old.flag)
        old.readable = true
        assertEquals(setOf(5L), RoomReview(history, old).undecided())
        assertEquals("made", history.outcome(5))
    }

    @Test
    fun `a Studio piece waits on its turn, a recording in the set, and Keep or Discard reach the right one`() = runBlocking {
        val history = backfilled()
        val old = Old().apply { flag = true }
        val review = RoomReview(history, old)
        history.begin(GenerationEntity(createdAt = 30, kind = "compose", pieceId = 7, outcome = GenerationEntity.MADE))
        review.waiting(7)   // Studio's: its turn says so already
        review.waiting(100)   // a recording
        assertEquals(setOf(100L), old.set)
        assertEquals(setOf(7L, 100L), review.undecided())
        review.decided(7, kept = false)
        review.decided(100, kept = true)
        assertEquals("discarded", history.outcome(7))
        assertEquals(emptySet<Long>(), old.set)
        assertEquals(emptySet<Long>(), review.undecided())
        review.waiting(101)
        review.gone(setOf(101))
        assertEquals(emptySet<Long>(), old.set)
    }
}
