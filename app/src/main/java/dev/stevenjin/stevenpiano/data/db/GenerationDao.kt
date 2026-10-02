// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Studio's history, `studio_generations` (schema v4, app 1.12 — M30); the statements that decide what is kept are [GenerationSql]'s. */
@Dao
interface GenerationDao {
    @Insert
    suspend fun insert(turn: GenerationEntity): Long

    @Update
    suspend fun update(turn: GenerationEntity)

    @Query("SELECT * FROM studio_generations WHERE id = :id")
    suspend fun get(id: Long): GenerationEntity?

    /** The newest [limit] turns, newest first. */
    @Query("SELECT * FROM studio_generations ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<GenerationEntity>>

    @Query("SELECT * FROM studio_generations WHERE pieceId = :pieceId LIMIT 1")
    suspend fun forPiece(pieceId: Long): GenerationEntity?

    @Query("SELECT * FROM studio_generations WHERE pieceId IN (:pieceIds)")
    suspend fun forPieces(pieceIds: List<Long>): List<GenerationEntity>

    @Query(GenerationSql.UNDECIDED)
    suspend fun undecided(): List<Long>

    @Query(GenerationSql.MADE_HERE)
    suspend fun madeHere(): List<Long>

    @Query(GenerationSql.INTERRUPT_PENDING)
    suspend fun interruptPending(): Int

    @Query(GenerationSql.REOPEN)
    suspend fun reopen(ids: List<Long>): Int

    @Query(GenerationSql.DECIDE)
    suspend fun decide(pieceId: Long, outcome: String): Int

    @Query(GenerationSql.TRIM)
    suspend fun trim(keep: Int): Int

    @Query("DELETE FROM studio_generations WHERE id = :id")
    suspend fun delete(id: Long)

    /** Turns whose piece has no cover yet (the pieces 1.7 to 1.11 made). */
    @Query("SELECT * FROM studio_generations WHERE pieceId IS NOT NULL AND coverKind IS NULL ORDER BY id")
    suspend fun withoutCover(): List<GenerationEntity>

    @Query("UPDATE studio_generations SET coverKind = :kind, coverSeed = :seed WHERE id = :id")
    suspend fun setCover(id: Long, kind: String, seed: Long)
}
