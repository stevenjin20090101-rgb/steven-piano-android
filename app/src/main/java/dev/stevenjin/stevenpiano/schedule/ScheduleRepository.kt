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
 * The quiet times (v1.20 — M54), kept in the schedules table ([ScheduleEntity], one row a block): read as they change
 * for the gate, the pages and the planner, and changed only through here, by the Quiet times pages of the tablet and
 * the web panel, which give every section at once ([replaceQuiet]: the old blocks go and the new ones come in one
 * [transaction], so nothing ever reads half of them). Sections with a problem are never written ([QuietTimes.validate]).
 * The timed plays' rows (the kinds before 1.20) stay in the table, never read.
 */
class ScheduleRepository(
    private val dao: ScheduleDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val transaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    /** The quiet times' blocks, in the order they were saved, as they change. */
    val quiet: Flow<List<ScheduleEntity>> = dao.observeQuiet()

    /** The quiet times' blocks now. */
    suspend fun quietRows(): List<ScheduleEntity> = dao.quiet()

    /** The sections as they are kept now. */
    suspend fun sections(): List<QuietSection> = QuietTimes.sections(dao.quiet())

    /** Every section, which must have no problem ([QuietTimes.validate]): the blocks kept are these and no others. */
    suspend fun replaceQuiet(sections: List<QuietSection>) {
        val problem = QuietTimes.validate(sections)
        require(problem == null) { "Quiet times with a problem can't be saved: $problem" }
        val rows = QuietTimes.rows(sections, createdAt = clock())
        transaction {
            dao.deleteQuiet()
            if (rows.isNotEmpty()) dao.insertAll(rows)
        }
    }
}
