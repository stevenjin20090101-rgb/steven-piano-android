// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.util.Log

/**
 * The way back from the school tablet's setup. Android will not uninstall a device owner, and
 * `adb shell dpm remove-active-admin` refuses an admin that is not a test-only build ("Attempt to
 * remove non-test admin", measured on the emulator), so the app gives up the role itself when
 * asked over adb:
 *
 * ```
 * adb shell setprop debug.stevenpiano.releaseowner yes
 * adb shell am start -n dev.stevenjin.stevenpiano/.MainActivity
 * adb shell dpm list-owners        # "no owners"
 * adb shell "setprop debug.stevenpiano.releaseowner ''"   # quoted whole: adb drops a bare ""
 * ```
 *
 * Checked as the app starts and whenever its activity starts or is sent an intent (`am start`), and
 * only while it is the device owner: Android ignores `am force-stop` for a device owner's package
 * ("Ignoring request to force stop protected package", measured on API 34), so a restart of the
 * process cannot be relied on. Only adb (the shell) can set a `debug.` property; no app on the tablet
 * can, so nothing but the person with the cable can take the role away. It is also the way out of
 * kiosk mode when its PIN is forgotten: [KioskMode] lets go of the screen and ends kiosk mode
 * first (the home screen, the lock screen and the lock task list as they were), then [release]
 * gives the role up, and with it silent updates. README › School tablet, › Kiosk.
 */
object DeviceOwnerRelease {
    const val PROPERTY = "debug.stevenpiano.releaseowner"
    private const val TAG = "Updates"
    private val ASKED = setOf("1", "yes", "true")

    /** Whether adb asks for the role back: the app is the device owner and [PROPERTY] says yes. Call off the main thread. */
    fun asked(context: Context): Boolean {
        val policy = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        val owner = try {
            policy.isDeviceOwnerApp(context.packageName)
        } catch (e: RuntimeException) {
            false
        }
        if (!owner) return false
        val value = runCatching {
            ProcessBuilder("getprop", PROPERTY).start().inputStream.bufferedReader().use { it.readText().trim().lowercase() }
        }.getOrDefault("")
        return value in ASKED
    }

    /** Gives up the device owner; true when it did. Kiosk mode must have ended first ([KioskMode]). */
    fun release(context: Context): Boolean {
        val policy = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        return try {
            @Suppress("DEPRECATION")   // deprecated for enterprise use; still the device owner's own way out
            policy.clearDeviceOwnerApp(context.packageName)
            Log.w(TAG, "No longer the device owner ($PROPERTY): updates ask again, and the app can be uninstalled")
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Couldn't give up the device owner: ${e.javaClass.simpleName}")
            false
        }
    }
}
