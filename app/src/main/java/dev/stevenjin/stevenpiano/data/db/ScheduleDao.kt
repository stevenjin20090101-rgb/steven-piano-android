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

/** The schedules table ([ScheduleEntity]); created in schema v3, used from M19. */
@Dao
interface ScheduleDao {
    /** Every schedule, by the time it starts. */
    @Query("SELECT * FROM schedules ORDER BY startMinute, id")
    fun observeAll(): Flow<List<ScheduleEntity>>

    /** Adds [schedule] (id 0) or replaces the one with its id. Returns the new row's id, or -1 when it replaced one. */
    @Upsert
    suspend fun upsert(schedule: ScheduleEntity): Long

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE schedules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}
