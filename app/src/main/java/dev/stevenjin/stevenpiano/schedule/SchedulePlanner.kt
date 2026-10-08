// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import dev.stevenjin.stevenpiano.data.db.ScheduleEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Keeps the one alarm ([AlarmScheduler]) set for whatever the [entries] do next ([Occurrences.next]: a start, or the
 * end of a run going on), and cancelled when nothing will. Since 1.20 (M54) the entries are the quiet times' blocks
 * alone: the alarm wakes the tablet as a block begins (the player stops) and ends. [replan] runs after every change to
 * them (the app follows the table), after every alarm (from the alarm's own minute, so another edge at that minute is
 * not planned again: it was due with it), when the tablet has restarted, when the clock or the time zone changes, and
 * when Android grants exact alarms. Without exact alarms nothing is planned: [exactAllowed] tells the Quiet times page
 * to ask. One plan at a time.
 */
class SchedulePlanner(
    private val entries: suspend () -> List<ScheduleEntity>,
    private val alarms: AlarmScheduler,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val lock = Mutex()

    private val _planned = MutableStateFlow<Occurrence?>(null)

    /** What the alarm is set for; null when none is. */
    val planned: StateFlow<Occurrence?> = _planned.asStateFlow()

    private val _exactAllowed = MutableStateFlow(true)

    /** Whether Android let the app set an exact alarm when last asked. */
    val exactAllowed: StateFlow<Boolean> = _exactAllowed.asStateFlow()

    /**
     * Sets the alarm for what comes next strictly after [afterMillis] (now by default; an alarm's
     * own minute after it goes off), or cancels it. Returns what it planned.
     */
    suspend fun replan(afterMillis: Long? = null): Occurrence? = lock.withLock {
        val exact = alarms.canScheduleExact()
        _exactAllowed.value = exact
        val after = ZonedDateTime.ofInstant(Instant.ofEpochMilli(afterMillis ?: clock()), zone())
        val next = Occurrences.next(entries(), after)
        if (next == null || !exact) {
            alarms.cancel()
            _planned.value = null
            log(if (next == null) "No schedule ahead: no alarm" else "Exact alarms not allowed: no alarm")
            return@withLock null
        }
        alarms.schedule(next.atMillis, next)
        _planned.value = next
        log("Alarm set: schedule ${next.scheduleId}'s ${next.edge.name.lowercase()} at ${next.at}")
        next
    }

    /** Asks Android again whether exact alarms are allowed (the Schedule page coming back from Settings), and plans again when that changed. */
    suspend fun recheckExact() {
        if (alarms.canScheduleExact() != _exactAllowed.value) replan()
    }
}
