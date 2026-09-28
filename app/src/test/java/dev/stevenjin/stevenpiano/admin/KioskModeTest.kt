// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.admin

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.stevenjin.stevenpiano.admin.FakeKioskDevice.Companion.PACKAGE
import dev.stevenjin.stevenpiano.settings.SettingsRepository
import dev.stevenjin.stevenpiano.web.LoginGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Kiosk mode as the app runs it (the switch, the PIN, "Unlock for now", the adb way back), over a fake device policy and a real DataStore. */
class KioskModeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var now = 5_000_000L
    private var files = 0

    @After
    fun stop() = scope.cancel()

    private fun settings(): SettingsRepository =
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "kiosk${files++}.preferences_pb") })

    private fun kiosk(
        device: FakeKioskDevice,
        settings: SettingsRepository,
        letGoMs: Long = KioskMode.LET_GO_MS,
        releaseIfAsked: (endKiosk: () -> Unit) -> Boolean = { false },
    ) = KioskMode(
        KioskController(device, PACKAGE),
        settings,
        kioskEnabled = settings.settings.map { it.kioskEnabled },
        scope = scope,
        releaseIfAsked = releaseIfAsked,
        clock = { now },
        letGoMs = letGoMs,
    )

    private suspend fun KioskMode.awaitLock(wanted: Boolean) = withTimeout(5_000) { lockWanted.first { it == wanted } }

    @Test
    fun `kiosk mode comes on only with a PIN and the device owner, and then the screen should lock`() = runBlocking<Unit> {
        val device = FakeKioskDevice(stayOn = 3)
        val settings = settings()
        val kiosk = kiosk(device, settings)
        kiosk.start()
        assertTrue(kiosk.status.value.checked && kiosk.status.value.owner)
        assertFalse("no PIN yet", kiosk.turnOn())
        assertEquals("nothing touched without a PIN", emptyList<String>(), device.calls)

        kiosk.setPin("123456")
        assertTrue(settings.settings.first().kioskPinSet)
        device.owner = false
        kiosk.refresh()
        assertFalse(kiosk.status.value.owner)
        assertFalse("not the device owner", kiosk.turnOn())
        assertFalse(device.homeAlias)

        device.owner = true
        kiosk.refresh()
        assertTrue(kiosk.turnOn())
        assertTrue(settings.settings.first().kioskEnabled)
        assertEquals("the stay-on it replaced, to put back", 3, settings.kioskStayOnBefore())
        assertTrue(device.homeAlias)
        kiosk.awaitLock(true)
        assertTrue(kiosk.lockTaskPermitted())
    }

    @Test
    fun `nothing locks before the start-up checks are done`() = runBlocking<Unit> {
        val device = FakeKioskDevice()
        val settings = settings()
        KioskController(device, PACKAGE).enable()
        settings.setKioskEnabled(true)
        val kiosk = kiosk(device, settings)
        delay(200)
        assertFalse(kiosk.lockWanted.value)
        kiosk.start()
        kiosk.awaitLock(true)
    }

    @Test
    fun `unlock for now lets go until the app is opened again, or relocks`() = runBlocking<Unit> {
        val kiosk = kiosk(FakeKioskDevice(), settings())
        kiosk.start()
        kiosk.setPin("123456")
        kiosk.turnOn()
        kiosk.awaitLock(true)

        kiosk.unlockForNow()
        kiosk.awaitLock(false)
        kiosk.appOpened()
        delay(100)
        assertFalse("never left, so still unlocked", kiosk.lockWanted.value)
        kiosk.appLeft()
        kiosk.appOpened()
        kiosk.awaitLock(true)

        kiosk.unlockForNow()
        kiosk.awaitLock(false)
        kiosk.relock()   // Lock again, or display mode coming
        kiosk.awaitLock(true)
        kiosk.appLeft()   // left while locked: nothing to undo
        kiosk.appOpened()
        assertTrue(kiosk.lockWanted.value)
    }

    @Test
    fun `turning kiosk mode off waits for the screen to let go before the lock task list empties`() = runBlocking<Unit> {
        val device = FakeKioskDevice(stayOn = 3)
        val settings = settings()
        val kiosk = kiosk(device, settings)
        kiosk.start()
        kiosk.setPin("123456")
        kiosk.turnOn()
        kiosk.awaitLock(true)
        device.locked = true   // the activity locked the screen
        val activity = scope.launch {
            kiosk.lockWanted.collect { wanted ->
                if (!wanted) {
                    delay(150)   // a frame or two later, the activity lets go
                    device.locked = false
                }
            }
        }
        kiosk.turnOff()
        activity.cancel()
        assertFalse("the app never closed under the person", device.taskCleared)
        assertEquals(emptyList<String>(), device.lockTaskList)
        assertFalse(device.homeAlias)
        assertFalse(device.keyguardOff)
        assertEquals("stay-on as it was", 3, device.stayOn)
        assertNull(settings.kioskStayOnBefore())
        assertFalse(settings.settings.first().kioskEnabled)
        assertFalse(kiosk.lockWanted.value)
        assertTrue("the PIN stays for next time", settings.settings.first().kioskPinSet)
    }

    @Test
    fun `an activity that never lets go does not keep kiosk mode on`() = runBlocking<Unit> {
        val device = FakeKioskDevice()
        val kiosk = kiosk(device, settings(), letGoMs = 200)
        kiosk.start()
        kiosk.setPin("123456")
        kiosk.turnOn()
        device.locked = true
        kiosk.turnOff()
        assertTrue("Android closes the locked task as the list empties", device.taskCleared)
        assertFalse(device.homeAlias)
    }

    @Test
    fun `the adb way back ends kiosk mode before the device owner goes`() = runBlocking<Unit> {
        val device = FakeKioskDevice(stayOn = 2)
        val settings = settings()
        val first = kiosk(device, settings)
        first.start()
        first.setPin("123456")
        first.turnOn()
        device.calls.clear()

        // The next start, with debug.stevenpiano.releaseowner set over adb.
        val restarted = kiosk(device, settings) { endKiosk ->
            endKiosk()
            device.calls += "clearDeviceOwnerApp"
            device.owner = false
            true
        }
        restarted.start()
        assertEquals(
            listOf(
                "clearPersistentPreferredActivities",
                "setHomeAliasEnabled false",
                "setStayOnWhilePluggedIn 2",
                "setKeyguardDisabled false",
                "setLockTaskFeatures ${KioskController.LOCK_TASK_FEATURES_DEFAULT}",
                "setLockTaskPackages []",
                "clearDeviceOwnerApp",
            ),
            device.calls,
        )
        assertFalse(settings.settings.first().kioskEnabled)
        assertNull(settings.kioskStayOnBefore())
        assertTrue(restarted.status.value.checked)
        assertFalse(restarted.status.value.owner)
        assertFalse(restarted.lockWanted.value)
    }

    @Test
    fun `without the device owner a home alias left on goes off and kiosk mode reads off`() = runBlocking<Unit> {
        val device = FakeKioskDevice(owner = false).apply { homeAlias = true }
        val settings = settings()
        settings.setKioskEnabled(true)
        val kiosk = kiosk(device, settings)
        kiosk.start()
        assertFalse(device.homeAlias)
        assertFalse(settings.settings.first().kioskEnabled)
        assertTrue(kiosk.status.value.checked)
        assertFalse(kiosk.status.value.owner)
    }

    @Test
    fun `a lock task list Android lost is put back at start, and nothing is touched when it is there`() = runBlocking<Unit> {
        val device = FakeKioskDevice()
        val settings = settings()
        settings.setKioskEnabled(true)
        kiosk(device, settings).start()
        assertEquals(listOf(PACKAGE), device.lockTaskList)
        assertTrue(device.homeAlias)
        device.calls.clear()
        kiosk(device, settings).start()
        assertEquals(emptyList<String>(), device.calls)
    }

    @Test
    fun `the PIN is weighed as the web panel's is, and its wrong tries outlast a restart`() = runBlocking<Unit> {
        val device = FakeKioskDevice()
        val settings = settings()
        val kiosk = kiosk(device, settings)
        kiosk.start()
        kiosk.setPin("246810")
        assertEquals(LoginGuard.Attempt.Right, kiosk.check("246810"))
        repeat(3) { assertEquals(LoginGuard.Attempt.Wrong(0L), kiosk.check("000000")) }
        assertEquals(LoginGuard.Attempt.Wrong(5_000L), kiosk.check("111111"))
        assertEquals("even the right PIN waits", LoginGuard.Attempt.Wait(5_000L), kiosk.check("246810"))
        assertEquals(4 to now + 5_000L, settings.kioskStrikes())

        now += 1_000
        val restarted = kiosk(device, settings)
        restarted.start()
        assertEquals(4_000L, restarted.waitMs())
        now += 4_000
        assertEquals(LoginGuard.Attempt.Wrong(10_000L), restarted.check("999999"))

        restarted.setPin("135790")
        assertEquals("a new PIN, a fresh count", 0 to 0L, settings.kioskStrikes())
        assertEquals(0L, restarted.waitMs())
        assertEquals(LoginGuard.Attempt.Wrong(0L), restarted.check("246810"))
        assertEquals(LoginGuard.Attempt.Right, restarted.check("135790"))
        assertEquals(0 to 0L, settings.kioskStrikes())
    }
}
