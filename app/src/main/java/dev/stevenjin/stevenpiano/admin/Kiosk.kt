// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.admin

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log

// Kiosk mode (DESIGN.md and BUILD_SPEC.md › v1.6 — M20): the school tablet shows the app and
// nothing else. Android gives a device owner a proper kiosk ("lock task"); this file is the part
// that talks to Android, behind the [KioskDevice] seam so [KioskController] is tested on the JVM.

/**
 * What the kiosk asks of Android: the device policy the app holds as the tablet's device owner,
 * the global "stay on while plugged in" setting, the package manager for the [HOME_ALIAS] and the
 * activity manager for whether the screen is locked to the app. One seam, so [KioskController]
 * runs on the JVM against a fake. Every call but [isDeviceOwner], [setHomeAliasEnabled],
 * [isHomeAliasEnabled] and [isLocked] needs the device owner and throws a [SecurityException]
 * without it.
 */
interface KioskDevice {
    /** Whether this app is the tablet's device owner: everything the kiosk changes needs it. */
    fun isDeviceOwner(): Boolean

    /** Which packages may lock the screen to themselves (`startLockTask` without Android's own prompt). */
    fun setLockTaskPackages(packages: List<String>)

    /** What stays usable while the screen is locked to the app: a mask of `LOCK_TASK_FEATURE_*`. Android 9 and later; ignored before. */
    fun setLockTaskFeatures(features: Int)

    /** The lock screen off (true) or back (false); false when Android refused because a PIN, pattern or password is set. */
    fun setKeyguardDisabled(disabled: Boolean): Boolean

    /** `Settings.Global.STAY_ON_WHILE_PLUGGED_IN` as it is now: a mask of `BatteryManager.BATTERY_PLUGGED_*`, 0 for never. */
    fun stayOnWhilePluggedIn(): Int

    fun setStayOnWhilePluggedIn(mask: Int)

    /** The [HOME_ALIAS] (the app as a home screen) on, or back to its manifest's off. Needs no device owner. */
    fun setHomeAliasEnabled(enabled: Boolean)

    fun isHomeAliasEnabled(): Boolean

    /** Home opens the [HOME_ALIAS] and nothing else, with no chooser (a persistent preferred activity). */
    fun addPersistentPreferredHome()

    /** Every persistent preferred activity this app set, gone. */
    fun clearPersistentPreferredActivities()

    /** Whether Android lets this app lock the screen to itself now (its package is on the lock task list). */
    fun isLockTaskPermitted(): Boolean

    /** Whether the screen is locked to the app now (lock task, not the person's own screen pinning). */
    fun isLocked(): Boolean
}

/** What turning kiosk mode on did. */
sealed interface KioskResult {
    /**
     * Locked in: the lock task list, no system features, the lock screen off, the screen on while
     * plugged in, the app as the home screen. [stayOnBefore] is the setting it replaced (to put
     * back later); [keyguardOff] false when the tablet has a screen lock Android keeps.
     */
    data class On(val stayOnBefore: Int, val keyguardOff: Boolean) : KioskResult

    /** The app is not the device owner: nothing was touched, the home alias above all. */
    data object NotOwner : KioskResult

    /** Android refused a step ([reason]); everything done before it was undone. */
    data class Refused(val reason: String) : KioskResult
}

/**
 * Kiosk mode's Android side (plan › M20). [enable]: the lock task list holds this package alone
 * (always first: `startLockTask` before it shows Android's pinning prompt instead), no lock task
 * features (no Home, Recents, notifications, status bar or power menu), the lock screen off, the
 * screen on while plugged in (AC, USB or wireless), and the [HOME_ALIAS] enabled and made the
 * persistent preferred home, so Home and a restart land in the app. [disable] reverses all of it,
 * the lock task list last. Both do nothing without the device owner, and the alias is never turned
 * on without it: an enabled home alias on a tablet the app does not own would make it a launcher
 * nobody could take away ([tidyWithoutOwner] turns one off). Locking the screen itself
 * (`startLockTask`) is the activity's, when [lockTaskPermitted].
 */
class KioskController(
    private val device: KioskDevice,
    private val packageName: String,
    private val log: (String) -> Unit = {},
) {
    fun isDeviceOwner(): Boolean = device.isDeviceOwner()

    fun lockTaskPermitted(): Boolean = runCatching { device.isLockTaskPermitted() }.getOrDefault(false)

    fun isLocked(): Boolean = runCatching { device.isLocked() }.getOrDefault(false)

    /** Kiosk mode on. Safe to repeat: turning it on again re-applies the same policy. */
    fun enable(): KioskResult {
        if (!device.isDeviceOwner()) return KioskResult.NotOwner
        val stayOnBefore = runCatching { device.stayOnWhilePluggedIn() }.getOrDefault(0)
        return try {
            device.setLockTaskPackages(listOf(packageName))
            device.setLockTaskFeatures(LOCK_TASK_FEATURES_NONE)
            val keyguardOff = device.setKeyguardDisabled(true)
            device.setStayOnWhilePluggedIn(STAY_ON_WHILE_PLUGGED)
            device.setHomeAliasEnabled(true)
            device.clearPersistentPreferredActivities()   // never two preferred homes of ours
            device.addPersistentPreferredHome()
            KioskResult.On(stayOnBefore, keyguardOff)
        } catch (e: RuntimeException) {
            disable(stayOnBefore)
            KioskResult.Refused(e.javaClass.simpleName)
        }
    }

    /**
     * Kiosk mode off, [stayOnBefore] put back as "stay on while plugged in". The screen must no
     * longer be locked to the app: Android clears a locked task whose package leaves the lock task
     * list (the app would close), so the list empties last and the caller lets go first. Each step
     * stands alone, so one refusal never leaves the rest undone.
     */
    fun disable(stayOnBefore: Int) {
        val owner = runCatching { device.isDeviceOwner() }.getOrDefault(false)
        if (owner) step("clear the preferred home") { device.clearPersistentPreferredActivities() }
        step("turn the home alias off") { device.setHomeAliasEnabled(false) }
        if (!owner) return
        step("put stay-on back") { device.setStayOnWhilePluggedIn(stayOnBefore) }
        step("bring the lock screen back") { device.setKeyguardDisabled(false) }
        step("put the lock task features back") { device.setLockTaskFeatures(LOCK_TASK_FEATURES_DEFAULT) }
        step("empty the lock task list") { device.setLockTaskPackages(emptyList()) }
    }

    /** Not the device owner (any more): a home alias left on goes off, so the app never stays a launcher. Touches nothing else. */
    fun tidyWithoutOwner() {
        if (runCatching { device.isHomeAliasEnabled() }.getOrDefault(false)) step("turn the home alias off") { device.setHomeAliasEnabled(false) }
    }

    private inline fun step(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: RuntimeException) {
            log("Couldn't $what (${e.javaClass.simpleName})")
        }
    }

    companion object {
        /** `DevicePolicyManager.LOCK_TASK_FEATURE_NONE`: nothing but the app. */
        const val LOCK_TASK_FEATURES_NONE = 0

        /** Android's own default, put back when the kiosk ends: `LOCK_TASK_FEATURE_GLOBAL_ACTIONS` (the power menu). */
        const val LOCK_TASK_FEATURES_DEFAULT = 16

        /** `BatteryManager.BATTERY_PLUGGED_AC | BATTERY_PLUGGED_USB | BATTERY_PLUGGED_WIRELESS`. */
        const val STAY_ON_WHILE_PLUGGED = 7

        /** The app's home-screen alias of `MainActivity` (the manifest's `.KioskHome`, off as installed). */
        const val HOME_ALIAS = "dev.stevenjin.stevenpiano.KioskHome"

        private const val TAG = "Kiosk"

        /** The controller over the tablet's own device policy, as [PianoDeviceAdmin]; a refused step is logged. */
        fun of(context: Context): KioskController {
            val app = context.applicationContext
            return KioskController(AndroidKioskDevice(app), app.packageName) { Log.w(TAG, it) }
        }
    }
}

/** [KioskDevice] over Android's DevicePolicyManager (as [PianoDeviceAdmin]), PackageManager and ActivityManager. */
private class AndroidKioskDevice(private val context: Context) : KioskDevice {
    private val policy: DevicePolicyManager? = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, PianoDeviceAdmin::class.java)
    private val home = ComponentName(context.packageName, KioskController.HOME_ALIAS)

    private fun owned(): DevicePolicyManager = policy ?: throw SecurityException("No device policy service")

    override fun isDeviceOwner(): Boolean = try {
        policy?.isDeviceOwnerApp(context.packageName) == true
    } catch (e: RuntimeException) {
        false
    }

    override fun setLockTaskPackages(packages: List<String>) = owned().setLockTaskPackages(admin, packages.toTypedArray())

    override fun setLockTaskFeatures(features: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) owned().setLockTaskFeatures(admin, features)
    }

    override fun setKeyguardDisabled(disabled: Boolean): Boolean = owned().setKeyguardDisabled(admin, disabled)

    override fun stayOnWhilePluggedIn(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0)

    override fun setStayOnWhilePluggedIn(mask: Int) = owned().setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, mask.toString())

    override fun setHomeAliasEnabled(enabled: Boolean) = context.packageManager.setComponentEnabledSetting(
        home,
        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
        PackageManager.DONT_KILL_APP,
    )

    override fun isHomeAliasEnabled(): Boolean =
        context.packageManager.getComponentEnabledSetting(home) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    override fun addPersistentPreferredHome() {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        owned().addPersistentPreferredActivity(admin, filter, home)
    }

    override fun clearPersistentPreferredActivities() = owned().clearPackagePersistentPreferredActivities(admin, context.packageName)

    override fun isLockTaskPermitted(): Boolean = policy?.isLockTaskPermitted(context.packageName) == true

    override fun isLocked(): Boolean =
        context.getSystemService(ActivityManager::class.java)?.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
}
