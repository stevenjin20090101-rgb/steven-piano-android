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
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The artwork table, one row per key (see [ArtworkEntity]). `key` is quoted: SQLite knows the word. */
@Dao
interface ArtworkDao {
    @Query("SELECT * FROM artwork WHERE `key` = :key")
    fun observe(key: String): Flow<ArtworkEntity?>

    @Query("SELECT * FROM artwork WHERE `key` = :key")
    suspend fun get(key: String): ArtworkEntity?

    @Upsert
    suspend fun upsert(artwork: ArtworkEntity)

    @Query("DELETE FROM artwork WHERE `key` = :key")
    suspend fun delete(key: String)
}
