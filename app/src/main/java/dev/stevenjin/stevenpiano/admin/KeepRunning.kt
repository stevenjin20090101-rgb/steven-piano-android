// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.admin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri

/**
 * The app keeps running on the school tablet (v1.39 — M77; DESIGN.md › v1.39 — M77): Android's battery limits, as the
 * app reads and asks about them. [batteryUnrestricted] reads them; [askBattery] shows Android's own dialog asking the
 * person to let the app run without them (the first start asks once, Piano › System any time); [openAppSettings] opens
 * the app's page in Android's settings, where Lenovo's ZUI keeps "Allow background activity" under Battery.
 */
object KeepRunning {
    private const val TAG = "KeepRunning"

    /** Whether Android lets the app run without battery limits now (`isIgnoringBatteryOptimizations`); false when it can't say. */
    fun batteryUnrestricted(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (e: RuntimeException) {
        false
    }

    /**
     * Android's dialog, "Let the app always run in the background?", for this app; the battery optimisation list where
     * a tablet has no such dialog. True when one opened. The app is distributed on GitHub, where Play's policy on this
     * permission doesn't apply, and the school tablet's watches over the piano's power need it.
     */
    @SuppressLint("BatteryLife")
    fun askBattery(context: Context): Boolean =
        open(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())) ||
            open(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    /** The app's page in Android's settings (Battery › Allow background activity on Lenovo's ZUI). True when it opened. */
    fun openAppSettings(context: Context): Boolean =
        open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))

    private fun open(context: Context, intent: Intent): Boolean = try {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No screen for ${intent.action}")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to open ${intent.action}")
        false
    }
}
