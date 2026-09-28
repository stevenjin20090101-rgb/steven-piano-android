// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.stevenjin.stevenpiano.BuildConfig

/**
 * The schedules' alarm on Android's [AlarmManager]: `setExactAndAllowWhileIdle(RTC_WAKEUP)`, which
 * wakes the tablet from sleep and from Doze at the minute, with one explicit, immutable broadcast
 * to [ScheduleReceiver] (request code 0, so a new alarm replaces the last). An exact alarm going off
 * also lets the app start its playback service from the background for a few seconds, which the
 * runner needs. In debug builds `adb shell setprop debug.stevenpiano.noexact 1` makes the app
 * behave as if exact alarms were refused (Android 13+ never refuses them: USE_EXACT_ALARM), so the
 * Schedule page's "Allow exact alarms" row can be seen on the emulator.
 */
class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)

    override fun canScheduleExact(): Boolean {
        if (BuildConfig.DEBUG && refusedForDebugging()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
    }

    override fun schedule(atMillis: Long, occurrence: Occurrence) {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction(ScheduleReceiver.ACTION_FIRE)
            .putExtra(ScheduleReceiver.EXTRA_AT, atMillis)
            .putExtra(ScheduleReceiver.EXTRA_SCHEDULE, occurrence.scheduleId)
            .putExtra(ScheduleReceiver.EXTRA_EDGE, occurrence.edge.name)
        val pending = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) return   // the planner asked first; refused since
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } catch (e: SecurityException) {   // the permission went between the question and the call
            Log.w(TAG, "Android refused the schedule's exact alarm")
        }
    }

    override fun cancel() {
        val intent = Intent(context, ScheduleReceiver::class.java).setAction(ScheduleReceiver.ACTION_FIRE)
        val pending = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) ?: return
        alarms.cancel(pending)
        pending.cancel()
    }

    /** `debug.stevenpiano.noexact` set to anything (debug builds only), read with getprop each time, so it can be turned on and off while the app runs. */
    private fun refusedForDebugging(): Boolean = runCatching {
        ProcessBuilder("getprop", NO_EXACT_PROPERTY).start().inputStream.bufferedReader().use { it.readText().trim() }.isNotEmpty()
    }.getOrDefault(false)

    private companion object {
        const val TAG = "Schedule"
        const val REQUEST_CODE = 0
        const val NO_EXACT_PROPERTY = "debug.stevenpiano.noexact"
    }
}
