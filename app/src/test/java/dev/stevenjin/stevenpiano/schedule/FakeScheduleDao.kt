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
import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** The schedules table in memory, as Room keeps it: ids from 1, the quiet rows by id, every change seen by [observeQuiet]. */
class FakeScheduleDao(initial: List<ScheduleEntity> = emptyList()) : ScheduleDao {
    private val rows = MutableStateFlow(initial.associateBy { it.id })
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1

    /** Every row, of every kind, by id. */
    val snapshot: List<ScheduleEntity> get() = rows.value.values.sortedBy { it.id }

    override fun observeQuiet(): Flow<List<ScheduleEntity>> = rows.map(::quietOf)

    override suspend fun quiet(): List<ScheduleEntity> = quietOf(rows.value)

    override suspend fun deleteQuiet() {
        rows.value = rows.value.filterValues { it.kind != ScheduleKind.QUIET }
    }

    override suspend fun insertAll(rows: List<ScheduleEntity>) {
        val added = rows.map { it.copy(id = nextId++) }
        this.rows.value = this.rows.value + added.associateBy { it.id }
    }

    private fun quietOf(map: Map<Long, ScheduleEntity>): List<ScheduleEntity> = map.values.filter { it.kind == ScheduleKind.QUIET }.sortedBy { it.id }
}
