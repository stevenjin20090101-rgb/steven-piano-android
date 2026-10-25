// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plug watch keeps the plug's Matter off (v1.32 — M70), through the keeper, [ShellyRail] and [PlugClient] to the
 * emulator's fake plug ([EmulatedShelly]), on a fake clock: found on, it is turned off, quietly the first time (a pairing
 * kept before 1.32) and kept so, said after that; firmware without Matter is left alone; and the restart Matter's change
 * asks for is no missed read, no stray on and no weld.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellyRailTest {
    /** Whether the pairing keeps Matter as turned off ([PlugPairing.matterOff]). */
    private class Kept(var matterOff: Boolean)

    private class Watch(val shelly: EmulatedShelly, val transport: FakePlugTransport, val keeper: RailKeeper, val kept: Kept, val logs: List<String>)

    /** A plug paired, set up and off every network (the end of the Pair screen), watched with nothing wanting the 24 V. */
    private fun TestScope.watch(matterOff: Boolean): Watch {
        val shelly = EmulatedShelly(Provision.Complete, clock = { currentTime })
        shelly.reset(Provision.Complete, bonded = EmulatedShelly.TABLET)
        val transport = FakePlugTransport(shelly)
        val logs = mutableListOf<String>()
        val kept = Kept(matterOff)
        val client = PlugClient(transport, backgroundScope, address = { EmulatedShelly.ADDRESS }, io = StandardTestDispatcher(testScheduler), log = { logs += it })
        val rail = ShellyRail(client, matterOff = { kept.matterOff }, keepMatterOff = { kept.matterOff = true }, log = { logs += it })
        val keeper = RailKeeper(
            backgroundScope, rail, check = { null }, connected = MutableStateFlow(true), stopSequence = {}, stopPlaying = {},
            available = MutableStateFlow(true), now = { currentTime }, log = { logs += it },
        )
        keeper.start()
        runCurrent()
        return Watch(shelly, transport, keeper, kept, logs)
    }

    @Test
    fun `Matter on in a pairing kept before 1_32 - off quietly at the first look and kept, then on again - off and said (v1_32 M70)`() = runTest {
        val w = watch(matterOff = false)
        w.shelly.matterOnFromOutside()   // 1.31 never turned it off
        advanceTimeBy(RailKeeper.WATCH_EVERY_MS + LOOK_MS)
        assertEquals(
            "the first look: the status, the settings, Matter off with its restart, then the plug read again once back",
            listOf(
                "Switch.GetStatus", "Switch.GetConfig", "Cloud.GetConfig", "WiFi.GetConfig",
                "Matter.GetConfig", "Matter.SetConfig", "Shelly.Reboot", "Switch.GetStatus",
            ),
            w.transport.methods,
        )
        assertFalse(w.shelly.matterOn())
        assertFalse("stopped with the restart", w.shelly.matterRunning())
        assertTrue("kept with the pairing", w.kept.matterOff)
        assertNull("quietly: nothing said", w.keeper.message.value)
        assertTrue("logged", w.logs.any { "Matter was on; turned off" in it })

        // The restart is no missed read (the next look is the small one alone), no stray on and no weld.
        val before = w.transport.methods.size
        advanceTimeBy(RailKeeper.WATCH_EVERY_MS)
        assertEquals(listOf("Switch.GetStatus"), w.transport.methods.drop(before))
        assertNull(w.keeper.message.value)
        assertFalse(w.keeper.welded.value)
        assertFalse(w.shelly.powered())

        // Matter on again: the next settings look turns it off, and says so.
        w.shelly.matterOnFromOutside()
        advanceTimeBy(RailKeeper.SETTINGS_EVERY_MS)
        assertEquals(RailMessage.SettingsPutBack, w.keeper.message.value)
        assertFalse(w.shelly.matterOn())
        assertFalse(w.shelly.matterRunning())
        assertEquals("a restart each time", 2, w.transport.methods.count { it == "Shelly.Reboot" })
        assertFalse(w.shelly.powered())
    }

    @Test
    fun `found on with Matter on - Matter off and its restart first, then the CUT, said, and its weld look (v1_32 M70)`() = runTest {
        val w = watch(matterOff = true)
        advanceTimeBy(RailKeeper.WATCH_EVERY_MS + LOOK_MS)
        assertEquals("Matter off already: nothing more", "Matter.GetConfig", w.transport.methods.last())
        w.shelly.matterOnFromOutside()
        w.shelly.switchOnFromOutside()   // a Matter controller, say: no 30 s timer
        val before = w.transport.methods.size
        advanceTimeBy(RailKeeper.WATCH_EVERY_MS + LOOK_MS)
        assertEquals(
            listOf(
                "Switch.GetStatus", "Switch.GetConfig", "Cloud.GetConfig", "WiFi.GetConfig",
                "Matter.GetConfig", "Matter.SetConfig", "Shelly.Reboot", "Switch.GetStatus",
                "Switch.Set", // the CUT, once the plug is back (off already: its power-up state is the tablet's)
                "Switch.GetStatus", // the weld look
            ),
            w.transport.methods.drop(before),
        )
        assertEquals(RailMessage.SwitchedOn, w.keeper.message.value)
        assertFalse(w.shelly.powered())
        assertFalse("no weld", w.keeper.welded.value)
        assertFalse(w.shelly.matterRunning())
    }

    @Test
    fun `firmware without Matter - Matter_GetConfig answers an error, and nothing is done, kept or said (v1_32 M70)`() = runTest {
        val w = watch(matterOff = false)
        w.shelly.hasMatter = false
        advanceTimeBy(RailKeeper.WATCH_EVERY_MS + LOOK_MS)
        assertEquals(listOf("Switch.GetStatus", "Switch.GetConfig", "Cloud.GetConfig", "WiFi.GetConfig", "Matter.GetConfig"), w.transport.methods)
        assertNull(w.keeper.message.value)
        assertFalse("nothing kept", w.kept.matterOff)
        val before = w.transport.methods.size
        advanceTimeBy(RailKeeper.WATCH_EVERY_MS)
        assertEquals("no missed read either", listOf("Switch.GetStatus"), w.transport.methods.drop(before))
    }

    private companion object {
        /** Long enough for a look with Matter's restart (6 s) and a CUT's weld look (3 s). */
        const val LOOK_MS = 10_000L
    }
}
