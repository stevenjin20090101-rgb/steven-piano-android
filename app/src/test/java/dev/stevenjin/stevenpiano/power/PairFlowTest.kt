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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pair screen's work (v1.26 — M60) against the emulator's fake plug ([EmulatedShelly]), on a fake clock: the setup in
 * SMART_PLUG.md's order, and the plugs that need another way (one that finished without this tablet, one that locked).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairFlowTest {
    /** The fake plug's Bluetooth, at once (a locked plug's silence takes the caller's whole time): every request's method is in [methods]. */
    private class Transport(private val shelly: EmulatedShelly) : PlugTransport {
        private var up = false
        val methods = mutableListOf<String>()
        override var refused: Boolean = false
        override var reached: Boolean = false

        override fun blocker(): String? = null

        override suspend fun scan(timeoutMs: Long, found: (FoundPlug) -> Unit): String? {
            found(FoundPlug(EmulatedShelly.ADDRESS, EmulatedShelly.NAME, -60))
            return null
        }

        override suspend fun connect(address: String): Boolean {
            up = !shelly.rebooting()
            reached = up
            return up
        }

        override val connected: Boolean get() = up && !shelly.rebooting()

        override suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray? {
            if (!connected) return null
            val text = String(frame, Charsets.UTF_8)
            methods += JSONObject(text).getString("method")
            return when (val answer = shelly.handle(EmulatedShelly.TABLET, text)) {
                is EmulatedShelly.Answer.Reply -> answer.json.toByteArray(Charsets.UTF_8)
                EmulatedShelly.Answer.Refused -> {
                    refused = true
                    null
                }
                EmulatedShelly.Answer.Silent -> {
                    delay(timeoutMs)
                    null
                }
            }
        }

        override fun disconnect() {
            up = false
        }

        override suspend fun bond(address: String, timeoutMs: Long): Boolean = shelly.bond(EmulatedShelly.TABLET)

        override fun bonded(address: String): Boolean = shelly.bonded(EmulatedShelly.TABLET)
    }

    private class Store : PairingStore {
        var pairing: PlugPairing? = null
        var on = false

        override suspend fun pair(pairing: PlugPairing, on: Boolean) {
            this.pairing = pairing
            this.on = on
        }

        override suspend fun setOn(on: Boolean) {
            this.on = on
        }
    }

    /** The flow's work runs in the background scope: [ms] of it, then what is due now. */
    private fun TestScope.settle(ms: Long = SETTLE_MS) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private class Setup(val shelly: EmulatedShelly, val transport: Transport, val store: Store, val flow: PairFlow)

    private fun TestScope.setup(start: Provision, bonded: Boolean = false): Setup {
        val shelly = EmulatedShelly(start, clock = { currentTime })
        if (bonded) shelly.reset(start, bonded = EmulatedShelly.TABLET)
        val transport = Transport(shelly)
        val client = PlugClient(transport, backgroundScope, address = { null }, io = StandardTestDispatcher(testScheduler))
        val store = Store()
        // The emulated piano's boards: up 0.9 s after the plug's relay closes, as with firmware 2.1.0.
        val check: suspend () -> Boards? = {
            val up = shelly.poweredSince()?.let { currentTime - it >= BOARDS_UP_MS } == true
            Boards(List(Boards.TOTAL) { up })
        }
        return Setup(shelly, transport, store, PairFlow(client, store, backgroundScope, check, now = { currentTime }))
    }

    @Test
    fun `the day's setup - confirmed, set up, complete, the network closed, the 24 V checked, then on`() = runTest {
        val s = setup(Provision.Confirmed)
        val shelly = s.shelly
        val transport = s.transport
        val store = s.store
        val flow = s.flow
        flow.shellyAppDone()
        settle()
        assertEquals(PairStep.Find, flow.state.value.step)
        val found = flow.state.value.found.single()
        assertEquals(EmulatedShelly.NAME, found.name)
        flow.choose(found)
        settle()
        assertEquals(Provision.Confirmed, flow.state.value.info?.provision)
        assertNull(flow.state.value.problem)

        flow.setUp()
        advanceTimeBy(30_000)
        assertEquals("every task ticked", SetUpTask.entries.toSet(), flow.state.value.tasks)
        assertEquals("waiting for complete", PairStep.Complete, flow.state.value.step)
        val pairing = store.pairing!!
        assertEquals(EmulatedShelly.ADDRESS, pairing.address)
        assertEquals("this tablet as the plug lists it, for Forget", EmulatedShelly.addressOf(EmulatedShelly.TABLET), pairing.bondEntry)
        assertFalse("the switch stays off until the end", store.on)
        assertTrue(shelly.bonded(EmulatedShelly.TABLET))
        val order = transport.methods.filter { it != "Shelly.GetDeviceInfo" && it != "BLE.ListPairedDevices" }
        assertEquals(listOf("Switch.SetConfig", "BLE.SetConfig", "Shelly.Reboot", "BLE.StartPairing", "Cloud.SetConfig"), order)

        advanceTimeBy(EmulatedShelly.COMPLETE_AFTER_MS)
        settle()
        assertEquals(PairStep.Network, flow.state.value.step)
        assertEquals(Provision.Complete, flow.state.value.info?.provision)

        flow.closeNetwork()
        settle()
        assertEquals(PairStep.Verify, flow.state.value.step)
        assertTrue("off every network, its access point closed", "WiFi.SetConfig" in transport.methods)

        flow.verify()
        advanceTimeBy(15_000)
        settle()
        val verify = flow.state.value.verify!!
        assertTrue("every board with the 24 V on, none after it went off", verify.passed)
        assertEquals(0, verify.answered)
        assertFalse(shelly.powered())
        val arm = transport.methods.count { it == "Switch.Set" }
        assertEquals("one ARM, of 5 s: the plug switched itself off", 1, arm)

        flow.finish()
        settle()
        assertEquals(PairStep.Done, flow.state.value.step)
        assertTrue("the plug switches the rail from now on", store.on)
    }

    @Test
    fun `a plug that finished its setup without this tablet - the factory reset's way`() = runTest {
        val s = setup(Provision.Complete)
        s.flow.shellyAppDone()
        settle()
        s.flow.choose(s.flow.state.value.found.single())
        settle()
        assertTrue(s.flow.state.value.fallback)
        assertEquals(PairFlow.REFUSED, s.flow.state.value.problem)
        assertNull("nothing kept", s.store.pairing)
    }

    @Test
    fun `a plug another tablet set up joins once its pairing window is open`() = runTest {
        val s = setup(Provision.Complete)
        s.shelly.reset(Provision.Complete, bonded = OTHER_TABLET)   // set up and paired by the old tablet
        s.flow.shellyAppDone()
        settle()
        s.flow.choose(s.flow.state.value.found.single())
        settle()
        s.flow.join()
        settle()
        assertEquals("no window: no bond", PairFlow.JOIN_FAILED, s.flow.state.value.problem)
        // The old tablet's Admit a replacement tablet.
        val opened = s.shelly.handle(OTHER_TABLET, """{"id":1,"src":"x","method":"BLE.StartPairing","params":{"timeout":180}}""")
        assertTrue(opened is EmulatedShelly.Answer.Reply)
        s.flow.join()
        settle()
        assertEquals(PairStep.Verify, s.flow.state.value.step)
        assertEquals(EmulatedShelly.ADDRESS, s.store.pairing?.address)
    }

    @Test
    fun `a locked plug - it connects and gives no reply in 5 s, and with no bond a power cycle is said (v1_26 M62)`() = runTest {
        val s = setup(Provision.Locked)
        s.flow.shellyAppDone()
        settle()
        s.flow.choose(s.flow.state.value.found.single())
        advanceTimeBy(PlugClient.SILENT_MS - 100)
        runCurrent()
        assertNull("5 s to answer: no verdict yet", s.flow.state.value.problem)
        settle()
        assertEquals(PairFlow.LOCKED, s.flow.state.value.problem)
        assertNull("it said nothing of itself", s.flow.state.value.info)
        assertEquals("asked once: Shelly.GetDeviceInfo, never answered", listOf("Shelly.GetDeviceInfo"), s.transport.methods)

        // A bonded plug that stays silent is no lock (a bonded plug in complete always answers): it just didn't answer.
        val bonded = setup(Provision.Locked, bonded = true)
        bonded.flow.shellyAppDone()
        settle()
        bonded.flow.choose(bonded.flow.state.value.found.single())
        advanceTimeBy(PlugClient.SILENT_MS)
        settle()
        assertEquals(PairFlow.UNREACHABLE, bonded.flow.state.value.problem)
    }

    @Test
    fun `a new plug set up from here - the bond while pending, then the hotspot over Bluetooth`() = runTest {
        val s = setup(Provision.Pending)
        s.flow.shellyAppDone()
        settle()
        s.flow.choose(s.flow.state.value.found.single())
        settle()
        assertEquals(Provision.Pending, s.flow.state.value.info?.provision)
        s.flow.bondWhilePending()
        settle()
        assertTrue(s.shelly.bonded(EmulatedShelly.TABLET))
        assertTrue(s.flow.state.value.overBluetooth)
        s.flow.provision("Steven's iPhone", "secret")
        advanceTimeBy(20_000)
        assertEquals(PairStep.Complete, s.flow.state.value.step)
        advanceTimeBy(EmulatedShelly.COMPLETE_AFTER_MS + 10_000)
        settle()
        assertEquals(PairStep.Network, s.flow.state.value.step)
    }

    private companion object {
        const val BOARDS_UP_MS = 900L
        const val SETTLE_MS = 1_000L
        const val OTHER_TABLET = "the old tablet"
    }
}
