// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

/**
 * The one alarm the schedules keep ([AndroidAlarmScheduler]; a fake in tests). There is never more
 * than one: [SchedulePlanner] sets it for whatever comes next and sets it again after every change,
 * every alarm, a restart of the tablet, and a change of the clock or the time zone.
 */
interface AlarmScheduler {
    /**
     * Whether Android lets the app wake the tablet at an exact time. From Android 13 the app holds
     * USE_EXACT_ALARM, which the person cannot take away; on Android 12 it holds
     * SCHEDULE_EXACT_ALARM, which they can (Settings › Apps › Special app access › Alarms &
     * reminders). Without it nothing is planned and the Schedule page asks for it.
     */
    fun canScheduleExact(): Boolean

    /** The alarm, at [atMillis] (the wall clock's milliseconds), for [occurrence]; it replaces the one set before. */
    fun schedule(atMillis: Long, occurrence: Occurrence)

    /** No alarm. */
    fun cancel()
}
