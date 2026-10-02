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

/** Playlists and their pieces (tables `collections` and `collection_pieces`, as in v1). */
@Dao
interface PlaylistDao {
    /**
     * Every playlist by name, with how many pieces it holds, how long they last together, whether it is built in,
     * and how many of its pieces are Classical and how many Modern (v1.14 — M37).
     */
    @Query(
        "SELECT c.id, c.name, c.imported, COUNT(cp.pieceId) AS pieceCount, COALESCE(SUM(p.durationMs), 0) AS durationMs, " +
            "c.builtIn, c.builtInKey, COALESCE(SUM(p.genre = 1), 0) AS classicalCount, COALESCE(SUM(p.genre = 2), 0) AS modernCount " +
            "FROM collections c LEFT JOIN collection_pieces cp ON cp.collectionId = c.id " +
            "LEFT JOIN pieces p ON p.id = cp.pieceId GROUP BY c.id ORDER BY c.name COLLATE NOCASE",
    )
    fun summaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT collectionId FROM collection_pieces WHERE pieceId = :pieceId")
    fun playlistIdsOf(pieceId: Long): Flow<List<Long>>

    @Query("SELECT * FROM collections WHERE name = :name COLLATE NOCASE")
    suspend fun byName(name: String): PlaylistEntity?

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    /** The built-in playlist [key] ("popular", "recognisable", "epic"), or null while there is none. */
    @Query("SELECT * FROM collections WHERE builtInKey = :key")
    suspend fun byBuiltInKey(key: String): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE collections SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    /** Returns -1 when the piece is already in the playlist. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addPiece(link: PlaylistPieceEntity): Long

    @Query("DELETE FROM collection_pieces WHERE collectionId = :playlistId AND pieceId = :pieceId")
    suspend fun removePiece(playlistId: Long, pieceId: Long)

    /** One past the last position in the playlist: where an added piece goes. 0 when it is empty. */
    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM collection_pieces WHERE collectionId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Query("UPDATE collection_pieces SET position = :position WHERE collectionId = :playlistId AND pieceId = :pieceId")
    suspend fun setPosition(playlistId: Long, pieceId: Long, position: Int)

    /** The playlist's pieces in playing order (position, then title), with their stored positions. */
    @Query(
        "SELECT cp.pieceId, cp.position FROM collection_pieces cp JOIN pieces p ON p.id = cp.pieceId " +
            "WHERE cp.collectionId = :playlistId ORDER BY cp.position, p.titleKey",
    )
    suspend fun positions(playlistId: Long): List<PiecePosition>
}
