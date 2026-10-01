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

    /**
     * Studio's default seed (v1.7 — M24): the piece played last whose composer isn't [except] (Studio's
     * own pieces), else the first such by title; null when there is none.
     */
    @Query("SELECT * FROM pieces WHERE composer != :except ORDER BY lastPlayedAt IS NULL, lastPlayedAt DESC, titleKey, id LIMIT 1")
    suspend fun seedPiece(except: String): PieceEntity?

    /** A playlist's pieces in its order: position, then title. */
    @Query(
        "SELECT p.* FROM pieces p JOIN collection_pieces cp ON cp.pieceId = p.id " +
            "WHERE cp.collectionId = :playlistId ORDER BY cp.position, p.titleKey",
    )
    fun inPlaylist(playlistId: Long): Flow<List<PieceEntity>>

    /** The pieces among [ids] (at most 500 at a time: SQLite's variable limit), in no particular order. */
    @Query("SELECT id, title, composerShort, durationMs, composerKey FROM pieces WHERE id IN (:ids)")
    suspend fun summaries(ids: List<Long>): List<PieceSummary>

    @Query("SELECT * FROM composer_groups ORDER BY composerKey")
    fun composers(): Flow<List<ComposerGroup>>

    /** A composer's first [limit] pieces by title: the roll cards of their mosaic. */
    @Query("SELECT id FROM pieces WHERE composerKey = :composerKey ORDER BY titleKey LIMIT :limit")
    suspend fun idsByComposer(composerKey: String, limit: Int): List<Long>

    /** The composer of a playlist's first piece (its order, then title), for its cover; null when it is empty. */
    @Query(
        "SELECT p.composerKey FROM pieces p JOIN collection_pieces cp ON cp.pieceId = p.id " +
            "WHERE cp.collectionId = :playlistId ORDER BY cp.position, p.titleKey LIMIT 1",
    )
    fun firstComposerKey(playlistId: Long): Flow<String?>

    @Query("SELECT COUNT(*) FROM pieces")
    fun count(): Flow<Int>

    /** Every piece, once, in no particular order: what the built-in playlists and the channels are made from. */
    @Query("SELECT * FROM pieces")
    suspend fun list(): List<PieceEntity>

    /** The pieces among [ids] (at most 500 at a time), whole, in no particular order: the web panel's Up next. */
    @Query("SELECT * FROM pieces WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<PieceEntity>

    /** Which of [ids] (at most 500 at a time) are still in the library. */
    @Query("SELECT id FROM pieces WHERE id IN (:ids)")
    suspend fun existing(ids: List<Long>): List<Long>

    @Query("SELECT * FROM pieces WHERE id = :id")
    suspend fun byId(id: Long): PieceEntity?

    @Query("SELECT * FROM pieces WHERE sha256 = :sha256")
    suspend fun bySha(sha256: String): PieceEntity?

    /** Whether any piece is grouped under [composerKey] (v1.10.1 — M28, D3). */
    @Query("SELECT EXISTS(SELECT 1 FROM pieces WHERE composerKey = :composerKey)")
    suspend fun hasComposerKey(composerKey: String): Boolean

    /**
     * The pieces an upload before 1.10.1 left loose (v1.10.1 — M28, D4, `UploadRepair`): no collection, in no
     * playlist, a folder in their path (`sourceName`), and none made in Studio ([studio], its composer).
     */
    @Query(
        "SELECT * FROM pieces p WHERE (p.collection IS NULL OR p.collection = '') AND instr(p.sourceName, '/') > 1 " +
            "AND p.composer != :studio AND NOT EXISTS (SELECT 1 FROM collection_pieces cp WHERE cp.pieceId = p.id)",
    )
    suspend fun loose(studio: String): List<PieceEntity>

    /** A piece's names and the keys made from them, and nothing else of it (the repair of older uploads). */
    @Query(
        "UPDATE pieces SET title = :title, composer = :composer, composerKey = :composerKey, composerShort = :composerShort, " +
            "searchText = :searchText, titleKey = :titleKey WHERE id = :id",
    )
    suspend fun setNames(id: Long, title: String, composer: String, composerKey: String, composerShort: String, searchText: String, titleKey: String)

    /** The ids of the pieces among [shas] (at most 500 at a time), in no particular order (v1.10.1 — M28, D2). */
    @Query("SELECT id, sha256 FROM pieces WHERE sha256 IN (:shas)")
    suspend fun idsBySha(shas: List<String>): List<PieceSha>

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
