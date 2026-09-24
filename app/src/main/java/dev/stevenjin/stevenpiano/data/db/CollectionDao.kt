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
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query(
        "SELECT c.id, c.name, c.imported, COUNT(cp.pieceId) AS pieceCount FROM collections c " +
            "LEFT JOIN collection_pieces cp ON cp.collectionId = c.id GROUP BY c.id ORDER BY c.name COLLATE NOCASE",
    )
    fun summaries(): Flow<List<CollectionSummary>>

    @Query("SELECT collectionId FROM collection_pieces WHERE pieceId = :pieceId")
    fun collectionIdsOf(pieceId: Long): Flow<List<Long>>

    @Query("SELECT * FROM collections WHERE name = :name COLLATE NOCASE")
    suspend fun byName(name: String): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(collection: CollectionEntity): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addPiece(link: CollectionPieceEntity)

    @Query("DELETE FROM collection_pieces WHERE collectionId = :collectionId AND pieceId = :pieceId")
    suspend fun removePiece(collectionId: Long, pieceId: Long)
}
