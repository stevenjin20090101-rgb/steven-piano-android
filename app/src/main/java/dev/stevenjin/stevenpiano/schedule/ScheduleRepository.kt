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

/**
 * The schedules (the v3 `schedules` table, [ScheduleEntity]): read as they change for the page,
 * the hub and the planner, and changed only through here, by the Schedule page and the web panel.
 * A draft with a problem is never written ([ScheduleDraft.problem]); a new one past
 * [ScheduleRules.MAX_SCHEDULES] is refused. The planner follows [all], so every change plans the
 * one alarm again.
 */
class ScheduleRepository(private val dao: ScheduleDao, private val clock: () -> Long = System::currentTimeMillis) {
    /** Every schedule, by start time then id, as it changes. */
    val all: Flow<List<ScheduleEntity>> = dao.observeAll()

    /** Every schedule now, by start time then id. */
    suspend fun list(): List<ScheduleEntity> = dao.list()

    suspend fun get(id: Long): ScheduleEntity? = dao.byId(id)

    /**
     * Saves [draft]: a new schedule when its id is 0 ([SaveResult.TooMany] when there are
     * [ScheduleRules.MAX_SCHEDULES] already), else the one with its id, keeping when it was made
     * ([SaveResult.Gone] when it has been deleted meanwhile). The draft must have no problem.
     */
    suspend fun save(draft: ScheduleDraft): SaveResult {
        require(draft.problem == null) { "A schedule with a problem can't be saved: ${draft.problem}" }
        if (draft.isNew) {
            if (dao.count() >= ScheduleRules.MAX_SCHEDULES) return SaveResult.TooMany
            val entity = draft.toEntity(createdAt = clock())
            return SaveResult.Saved(entity.copy(id = dao.upsert(entity)))
        }
        val existing = dao.byId(draft.id) ?: return SaveResult.Gone
        val entity = draft.toEntity(createdAt = existing.createdAt)
        dao.upsert(entity)
        return SaveResult.Saved(entity)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = dao.setEnabled(id, enabled)

    suspend fun delete(id: Long) = dao.delete(id)
}

/** How a save went: the schedule as saved, too many schedules already, or (an edit) the schedule gone meanwhile. */
sealed interface SaveResult {
    data class Saved(val schedule: ScheduleEntity) : SaveResult

    data object TooMany : SaveResult

    data object Gone : SaveResult
}
