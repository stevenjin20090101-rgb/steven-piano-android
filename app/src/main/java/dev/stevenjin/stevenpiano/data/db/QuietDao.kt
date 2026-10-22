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
import kotlinx.coroutines.flow.Flow

/**
 * The schedules table ([QuietEntity]); created in schema v3, used from M19 (app 1.6.2). Since 1.20 (M54) only the
 * quiet times' blocks ([QuietKind.QUIET]) are read or written; since schema v6 (v1.24 — M57) they are all it holds.
 */
@Dao
interface QuietDao {
    /** The quiet times' blocks, in the order they were saved (section by section), as they change. */
    @Query("SELECT * FROM schedules WHERE kind = 'QUIET' ORDER BY id")
    fun observeQuiet(): Flow<List<QuietEntity>>

    /** The quiet times' blocks now, in the order they were saved. */
    @Query("SELECT * FROM schedules WHERE kind = 'QUIET' ORDER BY id")
    suspend fun quiet(): List<QuietEntity>

    /** Every quiet time's block goes: the first half of replacing them all, in one transaction with [insertAll]. */
    @Query("DELETE FROM schedules WHERE kind = 'QUIET'")
    suspend fun deleteQuiet()

    @Insert
    suspend fun insertAll(rows: List<QuietEntity>)
}
