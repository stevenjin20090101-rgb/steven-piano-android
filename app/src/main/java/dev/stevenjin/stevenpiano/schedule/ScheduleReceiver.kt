// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.stevenjin.stevenpiano.graph
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the schedules hear from Android (not exported: only the system and the app's own alarm
 * reach it). [ACTION_FIRE] is the one alarm going off ([AndroidAlarmScheduler]) at a quiet time's
 * block's start or end (v1.20 — M54): the gate works the quiet out again ([Schedules.onAlarm]). A
 * restart of the tablet (the alarm is lost with it), a changed clock or time zone, the app updated,
 * and exact alarms granted all plan the alarm again. The broadcast is held open ([goAsync]) until
 * the blocks have been read, the alarm set again and the gate asked.
 */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != ACTION_FIRE && action !in REPLAN) return
        val schedules = context.graph.schedules
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val done = { if (finished.compareAndSet(false, true)) pending.finish() }
        if (action == ACTION_FIRE) {
            val edge = Edge.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_EDGE) } ?: Edge.START
            schedules.onAlarm(intent.getLongExtra(EXTRA_AT, NO_TIME), edge, done)
        } else {
            schedules.replanFor(action, done)
        }
    }

    companion object {
        /** The schedules' alarm; only the app's own immutable PendingIntent sends it. */
        const val ACTION_FIRE = "dev.stevenjin.stevenpiano.action.SCHEDULE_FIRE"

        /** The alarm's minute, in the wall clock's milliseconds. */
        const val EXTRA_AT = "at"

        /** The schedule it was set for (the log names it; what is due is read again from the table). */
        const val EXTRA_SCHEDULE = "schedule"

        /** START or END. */
        const val EXTRA_EDGE = "edge"

        const val NO_TIME = -1L

        /** What makes the alarm be planned again: the tablet restarted, the clock or zone changed, the app updated, exact alarms allowed. */
        val REPLAN = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31), sent when the person allows exact alarms.
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
