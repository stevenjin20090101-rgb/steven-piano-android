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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Timed play (DESIGN.md › v1.5.2 — M19), one per process ([AppGraph.schedules]): the schedules
 * ([repository]), the one exact alarm that keeps the next of them ([planner]), and what an alarm
 * does when it goes off ([onAlarm]). Everything runs in the app's scope, on the main thread.
 */
class Schedules(private val app: Application, private val graph: AppGraph, dao: ScheduleDao) {
    val repository = ScheduleRepository(dao)

    val planner = SchedulePlanner(repository::list, AndroidAlarmScheduler(app), log = ::debug)

    /** Every schedule, by start time, as it changes; null until first read. */
    val entries: StateFlow<List<ScheduleEntity>?> = repository.all.stateIn(graph.appScope, SharingStarted.Eagerly, null)

    /** Whether Android lets the schedules set their exact alarm; the Schedule page asks for it when not. */
    val exactAllowed: StateFlow<Boolean> get() = planner.exactAllowed

    private var started = false

    /** From [AppGraph.start]: the alarm is planned now and again after every change to the schedules. */
    fun start() {
        if (started) return
        started = true
        graph.appScope.launch { repository.all.collect { replan() } }
    }

    /** Saves a schedule made or edited on the Schedule page (the table's change plans the alarm again). */
    suspend fun save(draft: ScheduleDraft): SaveResult = repository.save(draft)

    suspend fun setEnabled(id: Long, enabled: Boolean) = repository.setEnabled(id, enabled)

    suspend fun delete(id: Long) = repository.delete(id)

    /** The Schedule page came back into view (perhaps from Android's Alarms & reminders): exact alarms are asked about again. */
    fun recheckExact() {
        graph.appScope.launch { runCatching { planner.recheckExact() } }
    }

    /** The alarm went off at [atMillis] for a schedule's [edge]: the alarm is planned again from that minute. */
    fun onAlarm(atMillis: Long, edge: Edge, done: () -> Unit) {
        graph.appScope.launch {
            try {
                debug("Alarm: ${edge.name.lowercase()} at $atMillis")
                replan(afterMillis = atMillis.takeIf { it > 0 })
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
            Log.w(TAG, "The schedules' alarm couldn't be planned", e)
        }
    }

    private fun debug(line: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, line)
    }

    private companion object {
        const val TAG = "Schedule"
    }
}
