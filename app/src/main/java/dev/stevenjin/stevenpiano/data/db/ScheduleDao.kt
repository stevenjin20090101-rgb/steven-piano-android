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
 * The schedules table ([ScheduleEntity]); created in schema v3, used from M19 (app 1.6.2). Since 1.20 (M54) only the
 * quiet times' blocks ([ScheduleKind.QUIET]) are read or written: the timed plays' rows stay in the table, never read.
 */
@Dao
interface ScheduleDao {
    /** The quiet times' blocks, in the order they were saved (section by section), as they change. */
    @Query("SELECT * FROM schedules WHERE kind = 'QUIET' ORDER BY id")
    fun observeQuiet(): Flow<List<ScheduleEntity>>

    /** The quiet times' blocks now, in the order they were saved: what the alarm planner reads. */
    @Query("SELECT * FROM schedules WHERE kind = 'QUIET' ORDER BY id")
    suspend fun quiet(): List<ScheduleEntity>

    /** Every quiet time's block goes: the first half of replacing them all, in one transaction with [insertAll]. */
    @Query("DELETE FROM schedules WHERE kind = 'QUIET'")
    suspend fun deleteQuiet()

    @Insert
    suspend fun insertAll(rows: List<ScheduleEntity>)
}
