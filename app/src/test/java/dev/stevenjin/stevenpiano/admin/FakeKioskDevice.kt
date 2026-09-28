// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.admin

/**
 * A tablet's device policy as the kiosk sees it, in memory: every change is recorded in [calls],
 * in order, and applied to the fields below, as Android would. Without the device owner ([owner]
 * false) the device owner's calls throw, as Android's do; [refuse] names one call to throw anyway.
 * Emptying the lock task list while [locked] clears the locked task, as Android does, and says so
 * in [taskCleared] (the app would have closed).
 */
class FakeKioskDevice(
    var owner: Boolean = true,
    var stayOn: Int = 0,
    private val screenLock: Boolean = false,
) : KioskDevice {
    val calls = mutableListOf<String>()
    var refuse: String? = null
    var lockTaskList: List<String> = emptyList()
    var features = KioskController.LOCK_TASK_FEATURES_DEFAULT
    var keyguardOff = false
    var homeAlias = false
    var preferredHomes = 0
    var locked = false
    var taskCleared = false

    private fun change(name: String, detail: Any? = null, needsOwner: Boolean = true) {
        calls += if (detail == null) name else "$name $detail"
        if (name == refuse) throw SecurityException("refused: $name")
        if (needsOwner && !owner) throw SecurityException("not the device owner")
    }

    override fun isDeviceOwner(): Boolean = owner

    override fun setLockTaskPackages(packages: List<String>) {
        change("setLockTaskPackages", packages)
        lockTaskList = packages
        if (locked && packages.isEmpty()) {
            locked = false
            taskCleared = true
        }
    }

    override fun setLockTaskFeatures(features: Int) {
        change("setLockTaskFeatures", features)
        this.features = features
    }

    override fun setKeyguardDisabled(disabled: Boolean): Boolean {
        change("setKeyguardDisabled", disabled)
        if (disabled && screenLock) return false
        keyguardOff = disabled
        return true
    }

    override fun stayOnWhilePluggedIn(): Int = stayOn

    override fun setStayOnWhilePluggedIn(mask: Int) {
        change("setStayOnWhilePluggedIn", mask)
        stayOn = mask
    }

    override fun setHomeAliasEnabled(enabled: Boolean) {
        change("setHomeAliasEnabled", enabled, needsOwner = false)
        homeAlias = enabled
    }

    override fun isHomeAliasEnabled(): Boolean = homeAlias

    override fun addPersistentPreferredHome() {
        change("addPersistentPreferredHome")
        preferredHomes++
    }

    override fun clearPersistentPreferredActivities() {
        change("clearPersistentPreferredActivities")
        preferredHomes = 0
    }

    override fun isLockTaskPermitted(): Boolean = owner && PACKAGE in lockTaskList

    override fun isLocked(): Boolean = locked

    companion object {
        const val PACKAGE = "dev.stevenjin.stevenpiano"
    }
}
