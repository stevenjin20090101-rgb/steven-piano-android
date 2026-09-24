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
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PieceDao {
    @Query("SELECT * FROM pieces ORDER BY titleKey")
    fun all(): Flow<List<PieceEntity>>

    /** [pattern] is folded and LIKE-escaped with '\'. */
    @Query("SELECT * FROM pieces WHERE searchText LIKE '%' || :pattern || '%' ESCAPE '\\' ORDER BY titleKey")
    fun search(pattern: String): Flow<List<PieceEntity>>

    @Query("SELECT * FROM pieces WHERE favorite = 1 ORDER BY titleKey")
    fun favorites(): Flow<List<PieceEntity>>

    /** Recently played, falling back to recently added. */
    @Query("SELECT * FROM pieces ORDER BY COALESCE(lastPlayedAt, addedAt) DESC, id DESC LIMIT 100")
    fun recent(): Flow<List<PieceEntity>>

    @Query("SELECT * FROM pieces WHERE composerKey = :composerKey ORDER BY titleKey")
    fun byComposer(composerKey: String): Flow<List<PieceEntity>>

    @Query(
        "SELECT p.* FROM pieces p JOIN collection_pieces cp ON cp.pieceId = p.id " +
            "WHERE cp.collectionId = :collectionId ORDER BY p.titleKey",
    )
    fun inCollection(collectionId: Long): Flow<List<PieceEntity>>

    @Query("SELECT * FROM composer_groups ORDER BY composerKey")
    fun composers(): Flow<List<ComposerGroup>>

    @Query("SELECT COUNT(*) FROM pieces")
    fun count(): Flow<Int>

    @Query("SELECT * FROM pieces WHERE id = :id")
    suspend fun byId(id: Long): PieceEntity?

    @Query("SELECT * FROM pieces WHERE sha256 = :sha256")
    suspend fun bySha(sha256: String): PieceEntity?

    /** Returns the new id, or -1 when a piece with the same bytes is already there. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(piece: PieceEntity): Long

    @Update
    suspend fun update(piece: PieceEntity)

    @Query("UPDATE pieces SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE pieces SET playCount = playCount + 1, lastPlayedAt = :at WHERE id = :id")
    suspend fun markPlayed(id: Long, at: Long)

    @Query("DELETE FROM pieces WHERE id = :id")
    suspend fun delete(id: Long)
}
