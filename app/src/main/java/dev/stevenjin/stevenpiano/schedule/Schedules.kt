// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import android.app.Application
import android.util.Log
import dev.stevenjin.stevenpiano.AppGraph
import dev.stevenjin.stevenpiano.BuildConfig
import dev.stevenjin.stevenpiano.data.db.ScheduleDao
import dev.stevenjin.stevenpiano.data.db.ScheduleEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The schedules, one per process ([AppGraph.schedules]): since 1.20 (DESIGN.md › v1.20 — M54) nothing plays by
 * itself, and the table holds the quiet times ([repository]); the one exact alarm ([planner]) wakes the tablet at their
 * blocks' starts and ends alone, and what an alarm does ([onAlarm]) is ask the gate ([AppGraph.quiet]) again, which stops
 * the player as a block begins. The timed plays' rows stay in the table, never read: nothing starts them. Everything
 * runs in the app's scope, on the main thread.
 */
class Schedules(
    app: Application,
    private val graph: AppGraph,
    dao: ScheduleDao,
    transaction: suspend (suspend () -> Unit) -> Unit = { it() },
) {
    val repository = ScheduleRepository(dao, transaction = transaction)

    /** The alarm follows the quiet times' blocks alone: a timed play's row is never planned. */
    val planner = SchedulePlanner(repository::quietRows, AndroidAlarmScheduler(app), log = ::debug)

    /** The quiet times' blocks as last read, for the gate and the pages; null until first read. */
    val quietRows: StateFlow<List<ScheduleEntity>?> = repository.quiet.stateIn(graph.appScope, SharingStarted.Eagerly, null)

    /** Whether Android lets the schedules set their exact alarm; the Quiet times page asks for it when not. */
    val exactAllowed: StateFlow<Boolean> get() = planner.exactAllowed

    private var started = false

    /** From [AppGraph.start]: the alarm is planned now and after every change to the quiet times. */
    fun start() {
        if (started) return
        started = true
        graph.appScope.launch { repository.quiet.collect { replan() } }
    }

    /** The quiet times as their sections now (the pages' lists, the web panel's `GET /api/quiet`). */
    suspend fun sections(): List<QuietSection> = repository.sections()

    /**
     * Every section, from the tablet's editor or the web panel: what keeps them from being saved ([QuietTimes.validate]),
     * or null once they are, the alarm planned again and the gate asked again.
     */
    suspend fun saveQuiet(sections: List<QuietSection>): String? {
        QuietTimes.validate(sections)?.let { return it }
        repository.replaceQuiet(sections)
        replan()
        return null
    }

    /** The Quiet times page came back into view (perhaps from Android's Alarms & reminders): exact alarms are asked about again. */
    fun recheckExact() {
        graph.appScope.launch { runCatching { planner.recheckExact() } }
    }

    /**
     * The alarm went off at [atMillis] for a block's [edge]: the alarm is planned again from that minute, the gate works
     * the quiet out again (a block beginning stops the player: [QuietGate.check]), and the broadcast is answered ([done]).
     */
    fun onAlarm(atMillis: Long, edge: Edge, done: () -> Unit) {
        graph.appScope.launch {
            try {
                debug("Alarm at $atMillis (${edge.name.lowercase()})")
                replan(afterMillis = atMillis.takeIf { it > 0 })
                // Woken by the alarm, the app may have only just opened: the gate weighs the blocks once they are read.
                withTimeoutOrNull(ROWS_WAIT_MS) { quietRows.first { it != null } }
                graph.quiet.check(atMillis.coerceAtLeast(0L))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "A quiet time's alarm couldn't be handled", e)
            } finally {
                done()
            }
        }
    }

    /** The tablet restarted, the clock or the zone changed, the app was updated, or exact alarms were allowed: the alarm is planned again. */
    fun replanFor(action: String, done: () -> Unit) {
        graph.appScope.launch {
            try {
                debug("Planning again after $action")
                replan()
                graph.quiet.check()
            } finally {
                done()
            }
        }
    }

    /** Plans the alarm; a failure (the table unreadable) is logged and leaves the last alarm as it was. */
    private suspend fun replan(afterMillis: Long? = null) {
        try {
            planner.replan(afterMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The quiet times' alarm couldn't be planned", e)
        }
    }

    private fun debug(line: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, line)
    }

    private companion object {
        const val TAG = "Schedule"
        const val ROWS_WAIT_MS = 3_000L
    }
}
