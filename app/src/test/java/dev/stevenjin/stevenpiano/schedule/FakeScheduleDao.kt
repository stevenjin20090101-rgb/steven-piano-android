// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import dev.stevenjin.stevenpiano.data.db.ScheduleDao
import dev.stevenjin.stevenpiano.data.db.ScheduleEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The schedules table in memory, as Room keeps it: ids from 1, rows by start then id, every change seen by [observeAll]. */
class FakeScheduleDao : ScheduleDao {
    private val rows = MutableStateFlow<Map<Long, ScheduleEntity>>(emptyMap())
    private var nextId = 1L

    val snapshot: List<ScheduleEntity> get() = sorted(rows.value)

    override fun observeAll(): Flow<List<ScheduleEntity>> = rows.map(::sorted)

    override suspend fun list(): List<ScheduleEntity> = snapshot

    override suspend fun byId(id: Long): ScheduleEntity? = rows.value[id]

    override suspend fun count(): Int = rows.value.size

    override suspend fun upsert(schedule: ScheduleEntity): Long {
        val replacing = schedule.id != 0L && schedule.id in rows.value
        val id = if (schedule.id == 0L) nextId++ else schedule.id.also { nextId = maxOf(nextId, it + 1) }
        rows.value = rows.value + (id to schedule.copy(id = id))
        return if (replacing) -1L else id
    }

    override suspend fun delete(id: Long) {
        rows.value = rows.value - id
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) {
        val row = rows.value[id] ?: return
        rows.value = rows.value + (id to row.copy(enabled = enabled))
    }

    private fun sorted(map: Map<Long, ScheduleEntity>): List<ScheduleEntity> = map.values.sortedWith(compareBy({ it.startMinute }, { it.id }))
}
