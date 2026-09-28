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
 * adb shell am force-stop dev.stevenjin.stevenpiano
 * adb shell am start -n dev.stevenjin.stevenpiano/.MainActivity
 * adb shell setprop debug.stevenpiano.releaseowner ""
 * ```
 *
 * Checked once as the app starts, and only while it is the device owner. Only adb (the shell)
 * can set a `debug.` property; no app on the tablet can, so nothing but the person with the cable
 * can take the role away. It is also the way out of kiosk mode when its PIN is forgotten: kiosk mode
 * ends first (`endKiosk`, [KioskMode.start]: the home screen, the lock screen and the lock task list
 * as they were), then the role goes, and with it silent updates. README › School tablet, › Kiosk.
 */
object DeviceOwnerRelease {
    const val PROPERTY = "debug.stevenpiano.releaseowner"
    private const val TAG = "Updates"
    private val ASKED = setOf("1", "yes", "true")

    /** Gives up the device owner when [PROPERTY] asks for it, after [endKiosk]; true when it did. Call off the main thread. */
    fun releaseIfAsked(context: Context, endKiosk: () -> Unit = {}): Boolean {
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
        if (value !in ASKED) return false
        try {
            endKiosk()   // before the role goes: without it the kiosk's policy could no longer be undone
        } catch (e: RuntimeException) {
            Log.w(TAG, "Kiosk mode couldn't be ended first: ${e.javaClass.simpleName}")
        }
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
