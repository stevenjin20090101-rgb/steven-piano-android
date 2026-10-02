// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.data.db.GenerationDao
import dev.stevenjin.stevenpiano.data.db.GenerationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Studio's history (v1.12 — M30): one turn per composition or transcription asked for, in the library's database
 * (`studio_generations`). It is the one record of a Studio piece's Keep or Discard: a piece waits while its turn
 * is "made". [RoomGenerations] in the app; tests use their own.
 */
interface Generations {
    /** A new turn; its id. */
    suspend fun begin(turn: GenerationEntity): Long

    /** Turn [id] changed by [change] (nothing when it is gone). */
    suspend fun update(id: Long, change: (GenerationEntity) -> GenerationEntity)

    /** At start: turns left running by a stop of the app read "interrupted". */
    suspend fun interruptPending()

    /** The newest [limit] turns, newest first, as they change. */
    fun recent(limit: Int): Flow<List<GenerationEntity>>

    suspend fun get(id: Long): GenerationEntity?

    suspend fun forPiece(pieceId: Long): GenerationEntity?

    /** The pieces waiting for Keep or Discard, oldest first. */
    suspend fun undecided(): List<Long>

    /** The one-off import: these pieces wait again (their turns were backfilled as kept). */
    suspend fun reopen(ids: Collection<Long>)

    /** Keep or Discard: false when no waiting turn has [pieceId]. */
    suspend fun decide(pieceId: Long, kept: Boolean): Boolean

    /** Every piece a turn made that the library still holds, newest first ("Made in Studio"). */
    suspend fun madeHere(): List<Long>

    suspend fun remove(id: Long)

    /** Keeps the newest [keep] turns, and every turn whose piece still waits. */
    suspend fun trim(keep: Int = KEEP)

    suspend fun withoutCover(): List<GenerationEntity>

    suspend fun setCover(id: Long, kind: String, seed: Long)

    companion object {
        /** The history keeps this many turns (and any number whose piece waits). */
        const val KEEP = 200
    }
}

/** [Generations] over the library's database. */
class RoomGenerations(private val dao: GenerationDao) : Generations {
    override suspend fun begin(turn: GenerationEntity): Long = dao.insert(turn)

    override suspend fun update(id: Long, change: (GenerationEntity) -> GenerationEntity) {
        val row = dao.get(id) ?: return
        dao.update(change(row))
    }

    override suspend fun interruptPending() {
        dao.interruptPending()
    }

    override fun recent(limit: Int): Flow<List<GenerationEntity>> = dao.recent(limit)

    override suspend fun get(id: Long): GenerationEntity? = dao.get(id)

    override suspend fun forPiece(pieceId: Long): GenerationEntity? = dao.forPiece(pieceId)

    override suspend fun undecided(): List<Long> = dao.undecided()

    override suspend fun reopen(ids: Collection<Long>) {
        for (chunk in ids.toList().chunked(CHUNK)) dao.reopen(chunk)
    }

    override suspend fun decide(pieceId: Long, kept: Boolean): Boolean =
        dao.decide(pieceId, if (kept) GenerationEntity.KEPT else GenerationEntity.DISCARDED) > 0

    override suspend fun madeHere(): List<Long> = dao.madeHere()

    override suspend fun remove(id: Long) = dao.delete(id)

    override suspend fun trim(keep: Int) {
        dao.trim(keep)
    }

    override suspend fun withoutCover(): List<GenerationEntity> = dao.withoutCover()

    override suspend fun setCover(id: Long, kind: String, seed: Long) = dao.setCover(id, kind, seed)

    private companion object {
        /** Ids per statement: under SQLite's 999-variable limit. */
        const val CHUNK = 500
    }
}

/** No history: a Studio made without one (tests that don't look at it). */
object NoGenerations : Generations {
    override suspend fun begin(turn: GenerationEntity): Long = 0

    override suspend fun update(id: Long, change: (GenerationEntity) -> GenerationEntity) = Unit

    override suspend fun interruptPending() = Unit

    override fun recent(limit: Int): Flow<List<GenerationEntity>> = emptyFlow()

    override suspend fun get(id: Long): GenerationEntity? = null

    override suspend fun forPiece(pieceId: Long): GenerationEntity? = null

    override suspend fun undecided(): List<Long> = emptyList()

    override suspend fun reopen(ids: Collection<Long>) = Unit

    override suspend fun decide(pieceId: Long, kept: Boolean): Boolean = false

    override suspend fun madeHere(): List<Long> = emptyList()

    override suspend fun remove(id: Long) = Unit

    override suspend fun trim(keep: Int) = Unit

    override suspend fun withoutCover(): List<GenerationEntity> = emptyList()

    override suspend fun setCover(id: Long, kind: String, seed: Long) = Unit
}
