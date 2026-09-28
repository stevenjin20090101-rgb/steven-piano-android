// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.admin

import dev.stevenjin.stevenpiano.admin.FakeKioskDevice.Companion.PACKAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kiosk mode's Android side over a fake device policy (plan › M20): what it sets, in what order, and how it all comes back off. */
class KioskControllerTest {
    private val on = listOf(
        "setLockTaskPackages [$PACKAGE]",
        "setLockTaskFeatures 0",
        "setKeyguardDisabled true",
        "setStayOnWhilePluggedIn 7",
        "setHomeAliasEnabled true",
        "clearPersistentPreferredActivities",
        "addPersistentPreferredHome",
    )

    @Test
    fun `as device owner it locks the task list first, then the features, the lock screen, stay-on and the home screen`() {
        val device = FakeKioskDevice(stayOn = 3)
        val result = KioskController(device, PACKAGE).enable()
        assertEquals(KioskResult.On(stayOnBefore = 3, keyguardOff = true), result)
        assertEquals("the lock task list before anything that could lock the screen", on, device.calls)
        assertEquals(listOf(PACKAGE), device.lockTaskList)
        assertEquals("no Home, Recents, notifications, status bar or power menu", 0, device.features)
        assertTrue(device.keyguardOff)
        assertEquals("on while plugged in to AC, USB or a wireless charger", 7, device.stayOn)
        assertTrue(device.homeAlias)
        assertEquals(1, device.preferredHomes)
        assertTrue(KioskController(device, PACKAGE).lockTaskPermitted())
    }

    @Test
    fun `turning it on again re-applies the same policy and never stacks two preferred homes`() {
        val device = FakeKioskDevice()
        val controller = KioskController(device, PACKAGE)
        controller.enable()
        val again = controller.enable()
        assertEquals("the stay-on it reads is its own now: the caller keeps the first", KioskResult.On(stayOnBefore = 7, keyguardOff = true), again)
        assertEquals(1, device.preferredHomes)
    }

    @Test
    fun `without the device owner nothing is touched, the home alias least of all`() {
        val device = FakeKioskDevice(owner = false)
        val controller = KioskController(device, PACKAGE)
        assertEquals(KioskResult.NotOwner, controller.enable())
        assertEquals(emptyList<String>(), device.calls)
        assertFalse(device.homeAlias)
        assertFalse(controller.lockTaskPermitted())
    }

    @Test
    fun `a screen lock Android keeps is reported, and the rest still comes on`() {
        val device = FakeKioskDevice(screenLock = true)
        assertEquals(KioskResult.On(stayOnBefore = 0, keyguardOff = false), KioskController(device, PACKAGE).enable())
        assertEquals(on, device.calls)
        assertTrue(device.homeAlias)
    }

    @Test
    fun `off reverses all of it, the preferred home first and the lock task list last`() {
        val device = FakeKioskDevice(stayOn = 2)
        val controller = KioskController(device, PACKAGE)
        controller.enable()
        device.calls.clear()
        controller.disable(stayOnBefore = 2)
        assertEquals(
            listOf(
                "clearPersistentPreferredActivities",
                "setHomeAliasEnabled false",
                "setStayOnWhilePluggedIn 2",
                "setKeyguardDisabled false",
                "setLockTaskFeatures ${KioskController.LOCK_TASK_FEATURES_DEFAULT}",
                "setLockTaskPackages []",
            ),
            device.calls,
        )
        assertEquals(emptyList<String>(), device.lockTaskList)
        assertEquals("Android's default: the power menu", 16, device.features)
        assertFalse(device.keyguardOff)
        assertEquals(2, device.stayOn)
        assertFalse(device.homeAlias)
        assertEquals(0, device.preferredHomes)
    }

    @Test
    fun `a step Android refuses while turning on undoes everything before it`() {
        val device = FakeKioskDevice(stayOn = 1)
        device.refuse = "setHomeAliasEnabled"
        val result = KioskController(device, PACKAGE).enable()
        assertEquals(KioskResult.Refused("SecurityException"), result)
        device.refuse = null
        assertEquals(emptyList<String>(), device.lockTaskList)
        assertFalse(device.keyguardOff)
        assertEquals("stay-on as it was", 1, device.stayOn)
        assertFalse(device.homeAlias)
        assertEquals(0, device.preferredHomes)
    }

    @Test
    fun `one refused step while turning off never stops the others`() {
        val device = FakeKioskDevice()
        val logged = mutableListOf<String>()
        val controller = KioskController(device, PACKAGE) { logged += it }
        controller.enable()
        device.refuse = "setKeyguardDisabled"
        controller.disable(stayOnBefore = 0)
        assertEquals(listOf("Couldn't bring the lock screen back (SecurityException)"), logged)
        assertFalse(device.homeAlias)
        assertEquals(0, device.stayOn)
        assertEquals(emptyList<String>(), device.lockTaskList)
    }

    @Test
    fun `without the device owner, off and the tidy-up only ever turn the home alias off`() {
        val device = FakeKioskDevice(owner = false)
        device.homeAlias = true   // left on by an owner that has since gone
        val controller = KioskController(device, PACKAGE)
        controller.tidyWithoutOwner()
        assertEquals(listOf("setHomeAliasEnabled false"), device.calls)
        assertFalse(device.homeAlias)
        device.calls.clear()
        controller.tidyWithoutOwner()
        assertEquals("nothing to tidy", emptyList<String>(), device.calls)
        device.homeAlias = true
        controller.disable(stayOnBefore = 0)
        assertEquals(listOf("setHomeAliasEnabled false"), device.calls)
    }
}
