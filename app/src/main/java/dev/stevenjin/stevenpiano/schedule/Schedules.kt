// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import dev.stevenjin.stevenpiano.AppGraph
import dev.stevenjin.stevenpiano.data.db.ScheduleDao
import dev.stevenjin.stevenpiano.data.db.ScheduleEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * The schedules, one per process ([AppGraph.schedules]): since 1.20 (DESIGN.md › v1.20 — M54) nothing plays by itself,
 * and the table holds the quiet times alone ([repository]; schema v6, app 1.24, dropped the timed plays' rows). The
 * gate ([AppGraph.quiet]) follows [quietRows] and the clock in the app's own process, which stops the player as a block
 * begins; no alarm is kept (v1.24 — M57).
 */
class Schedules(
    graph: AppGraph,
    dao: ScheduleDao,
    transaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    val repository = ScheduleRepository(dao, transaction = transaction)

    /** The quiet times' blocks as last read, for the gate and the pages; null until first read. */
    val quietRows: StateFlow<List<ScheduleEntity>?> = repository.quiet.stateIn(graph.appScope, SharingStarted.Eagerly, null)

    /** The quiet times as their sections now (the pages' lists, the web panel's `GET /api/quiet`). */
    suspend fun sections(): List<QuietSection> = repository.sections()

    /**
     * Every section, from the tablet's editor or the web panel: what keeps them from being saved ([QuietTimes.validate]),
     * or null once they are (the gate, following [quietRows], weighs the quiet again).
     */
    suspend fun saveQuiet(sections: List<QuietSection>): String? {
        QuietTimes.validate(sections)?.let { return it }
        repository.replaceQuiet(sections)
        return null
    }
}
