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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Pair screen's work (v1.26 — M60) against the emulator's fake plug ([EmulatedShelly]), on a fake clock: the setup in
 * SMART_PLUG.md's order, and the plugs that need another way (one that finished without this tablet, one that locked).
 * Since v1.28 (M65), without the Shelly app: old firmware updated over the hotspot, a new plug set up from here by itself,
 * the pairing kept only once the plug is off every network, and the hotspot kept nowhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairFlowTest {
    /**
     * The fake plug's Bluetooth, at once (a locked plug's silence takes the caller's whole time): every request's method is in
     * [methods] (Android's bond as "bond"), its frame in [frames], and what the plug was at it in [seen].
     */
    private class Transport(private val shelly: EmulatedShelly) : PlugTransport {
        private var up = false
        val methods = mutableListOf<String>()
        val frames = mutableListOf<String>()

        /** At each request: the plug's setup state, and whether this tablet was bonded with it. */
        val seen = mutableListOf<Pair<Provision, Boolean>>()
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
            frames += text
            seen += shelly.provisionNow() to shelly.bonded(EmulatedShelly.TABLET)
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

        override suspend fun bond(address: String, timeoutMs: Long): Boolean = shelly.bond(EmulatedShelly.TABLET).also { methods += "bond" }

        override fun bonded(address: String): Boolean = shelly.bonded(EmulatedShelly.TABLET)
    }

    /** The pairing as kept, whether the plug's network was up at that moment, and every value kept as text ([kept]). */
    private class Store(private val shelly: EmulatedShelly) : PairingStore {
        var pairing: PlugPairing? = null
        var on = false
        var networkWhenPaired: Boolean? = null
        val kept = mutableListOf<String>()

        override suspend fun pair(pairing: PlugPairing, on: Boolean) {
            networkWhenPaired = shelly.networkUp()
            kept += listOfNotNull(pairing.address, pairing.id, pairing.model, pairing.firmware, pairing.bondEntry, "$on")
            this.pairing = pairing
            this.on = on
        }

        override suspend fun setOn(on: Boolean) {
            kept += "$on"
            this.on = on
        }
    }

    /** The flow's work runs in the background scope: [ms] of it, then what is due now. */
    private fun TestScope.settle(ms: Long = SETTLE_MS) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** The flow run on half a second at a time until [done] holds, [limitMs] at most. */
    private fun TestScope.until(limitMs: Long = 600_000, done: () -> Boolean) {
        val end = currentTime + limitMs
        while (!done() && currentTime < end) settle(500)
        assertTrue("not reached within ${limitMs / 1_000} s", done())
    }

    private class Setup(val shelly: EmulatedShelly, val transport: Transport, val store: Store, val flow: PairFlow, val logs: List<String>)

    private fun TestScope.setup(start: Provision, bonded: Boolean = false, firmware: String = EmulatedShelly.FIRMWARE): Setup {
        val shelly = EmulatedShelly(start, clock = { currentTime }, firmware = firmware)
        if (bonded) shelly.reset(start, bonded = EmulatedShelly.TABLET)
        val transport = Transport(shelly)
        val logs = mutableListOf<String>()
        val client = PlugClient(transport, backgroundScope, address = { null }, io = StandardTestDispatcher(testScheduler), log = { logs += it })
        val store = Store(shelly)
        // The emulated piano's boards: up 0.9 s after the plug's relay closes, as with firmware 2.1.0.
        val check: suspend () -> Boards? = {
            val up = shelly.poweredSince()?.let { currentTime - it >= BOARDS_UP_MS } == true
            Boards(List(Boards.TOTAL) { up })
        }
        return Setup(shelly, transport, store, PairFlow(client, store, backgroundScope, check, now = { currentTime }, log = { logs += it }), logs)
    }

    /** Step 1 done, and the one plug found chosen and read. */
    private fun TestScope.found(s: Setup) {
        s.flow.ready()
        settle()
        s.flow.choose(s.flow.state.value.found.single())
        settle()
    }

    @Test
    fun `the Shelly app's way - confirmed, set up, complete, the network closed and only then paired, the 24 V checked, then on`() = runTest {
        val s = setup(Provision.Confirmed)
        val shelly = s.shelly
        val transport = s.transport
        val store = s.store
        val flow = s.flow
        flow.ready()
        settle()
        assertEquals(PairStep.Find, flow.state.value.step)
        val found = flow.state.value.found.single()
        assertEquals(EmulatedShelly.NAME, found.name)
        flow.choose(found)
        settle()
        assertEquals(Provision.Confirmed, flow.state.value.info?.provision)
        assertEquals("waits for the tap", PairStep.Find, flow.state.value.step)
        assertNull(flow.state.value.problem)

        flow.setUp()
        advanceTimeBy(30_000)
        assertEquals("every task ticked", SetUpTask.entries.toSet(), flow.state.value.tasks)
        assertEquals("waiting for complete", PairStep.Complete, flow.state.value.step)
        assertTrue(shelly.bonded(EmulatedShelly.TABLET))
        assertNull("nothing kept while the plug is on the hotspot (v1.28 — M65)", store.pairing)
        val order = transport.methods.filter { it !in POLLS }
        assertEquals(listOf("Switch.SetConfig", "BLE.SetConfig", "Shelly.Reboot", "BLE.StartPairing", "bond", "Cloud.SetConfig"), order)

        advanceTimeBy(EmulatedShelly.COMPLETE_AFTER_MS)
        settle()
        assertEquals(PairStep.Network, flow.state.value.step)
        assertEquals(Provision.Complete, flow.state.value.info?.provision)
        flow.finish()
        settle()
        assertEquals("no Finish with its network on", PairStep.Network, flow.state.value.step)
        assertNull(store.pairing)
        assertFalse(store.on)

        flow.closeNetwork()
        settle()
        assertEquals(PairStep.Verify, flow.state.value.step)
        assertFalse("off every network, its access point closed", shelly.networkUp())
        val pairing = store.pairing!!
        assertEquals("kept once the network was off", false, store.networkWhenPaired)
        assertEquals(EmulatedShelly.ADDRESS, pairing.address)
        assertEquals("this tablet as the plug lists it, for Forget", EmulatedShelly.addressOf(EmulatedShelly.TABLET), pairing.bondEntry)
        assertFalse("the switch stays off until the end", store.on)

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
        found(s)
        assertTrue(s.flow.state.value.fallback)
        assertEquals(PairFlow.REFUSED, s.flow.state.value.problem)
        assertNull("nothing kept", s.store.pairing)
    }

    @Test
    fun `a plug another tablet set up joins once its pairing window is open, its network closed once more`() = runTest {
        val s = setup(Provision.Complete)
        s.shelly.reset(Provision.Complete, bonded = OTHER_TABLET)   // set up and paired by the old tablet
        found(s)
        s.flow.join()
        settle()
        assertEquals("no window: no bond", PairFlow.JOIN_FAILED, s.flow.state.value.problem)
        // The old tablet's Admit a replacement tablet.
        val opened = s.shelly.handle(OTHER_TABLET, """{"id":1,"src":"x","method":"BLE.StartPairing","params":{"timeout":180}}""")
        assertTrue(opened is EmulatedShelly.Answer.Reply)
        s.flow.join()
        until { s.flow.state.value.step == PairStep.Verify }
        assertEquals("its settings and the cloud once more, then the network off", listOf("bond", "Switch.SetConfig", "BLE.SetConfig", "Shelly.Reboot", "Cloud.SetConfig", "WiFi.SetConfig"), s.transport.methods.filter { it !in POLLS }.takeLast(6))
        assertEquals(SetUpTask.entries.toSet(), s.flow.state.value.tasks)
        assertEquals(EmulatedShelly.ADDRESS, s.store.pairing?.address)
        assertEquals(false, s.store.networkWhenPaired)
    }

    @Test
    fun `a locked plug - it connects and gives no reply in 5 s, and with no bond a power cycle is said (v1_26 M62)`() = runTest {
        val s = setup(Provision.Locked)
        s.flow.ready()
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
        bonded.flow.ready()
        settle()
        bonded.flow.choose(bonded.flow.state.value.found.single())
        advanceTimeBy(PlugClient.SILENT_MS)
        settle()
        assertEquals(PairFlow.UNREACHABLE, bonded.flow.state.value.problem)
    }

    // ---- v1.28 — M65: without the Shelly app ---------------------------------------------------------------------------

    @Test
    fun `old firmware - updated over the hotspot, cleared, bonded, given the hotspot again, complete, the network closed, then paired (v1_28 M65)`() = runTest {
        val s = setup(Provision.Pending, firmware = OLD)
        found(s)
        val update = s.flow.state.value
        assertEquals(PairStep.Update, update.step)
        assertEquals(OLD, update.firmwareUpdate?.from)
        assertEquals("no provision before 1.7.5", Provision.Unknown, update.info?.provision)
        assertNull(update.problem)

        s.flow.update(SSID, PASS)
        until { s.flow.state.value.step == PairStep.Complete }
        val waiting = s.flow.state.value
        assertEquals("every part of the update ticked", UpdateTask.entries.toSet(), waiting.firmwareUpdate?.tasks)
        assertEquals(EmulatedShelly.OFFERED, waiting.firmwareUpdate?.to)
        assertEquals(EmulatedShelly.OFFERED, s.shelly.firmware)
        assertTrue("bonded before complete", s.shelly.bonded(EmulatedShelly.TABLET))
        assertNull("nothing kept while it is on the hotspot", s.store.pairing)

        until { s.flow.state.value.verify?.finished == true }
        val checked = s.flow.state.value
        assertEquals(PairStep.Verify, checked.step)
        assertTrue("checked by itself, the piano answering", checked.verify!!.passed)
        assertFalse("off every network, its access point closed", s.shelly.networkUp())
        assertEquals("kept only once the network was off", false, s.store.networkWhenPaired)
        val pairing = s.store.pairing!!
        assertEquals(EmulatedShelly.OFFERED, pairing.firmware)
        assertEquals(EmulatedShelly.addressOf(EmulatedShelly.TABLET), pairing.bondEntry)
        assertFalse("its switch off until Finish", s.store.on)
        assertTrue("it reached complete", s.transport.seen.any { it.first == Provision.Complete })
        assertTrue("never complete without this tablet's bond", s.transport.seen.none { (state, bonded) -> state == Provision.Complete && !bonded })
        assertEquals(
            listOf(
                "Switch.Set", "Switch.SetConfig", "WiFi.SetConfig", "Shelly.CheckForUpdate", "Shelly.Update", // off, the hotspot, the update
                "Shelly.FactoryReset", "Switch.Set", // cleared, then `pending`, the 24 V off again
                "BLE.StartPairing", "bond", "WiFi.SetConfig", // the bond first, then the hotspot once more
                "Switch.SetConfig", "BLE.SetConfig", "Shelly.Reboot", "Cloud.SetConfig", // its settings, the cloud off
                "WiFi.SetConfig", "Switch.Set", // `complete`: the network off, then the check's 5 s
            ),
            s.transport.methods.filter { it !in POLLS },
        )
        val methods = s.transport.methods
        assertTrue("online before Shelly was asked", methods.indexOf("WiFi.GetStatus") in methods.indexOf("WiFi.SetConfig")..methods.indexOf("Shelly.CheckForUpdate"))

        s.flow.finish()
        settle()
        assertEquals(PairStep.Done, s.flow.state.value.step)
        assertTrue("the plug switches the rail from now on", s.store.on)
    }

    @Test
    fun `Shelly_Update refused over Bluetooth - the Shelly app's words, nothing reset and nothing paired (v1_28 M65)`() = runTest {
        val s = setup(Provision.Pending, firmware = OLD)
        s.shelly.refuseUpdate = true
        found(s)
        s.flow.update(SSID, PASS)
        until { s.flow.state.value.problem != null }
        val stopped = s.flow.state.value
        assertEquals(PairFlow.UPDATE_REFUSED, stopped.problem)
        assertEquals(PairStep.Update, stopped.step)
        assertEquals(setOf(UpdateTask.RailOff, UpdateTask.Online, UpdateTask.Offered), stopped.firmwareUpdate?.tasks)
        assertEquals(OLD, s.shelly.firmware)
        assertFalse("no reset", "Shelly.FactoryReset" in s.transport.methods)
        assertNull("nothing paired", s.store.pairing)
        assertFalse(s.store.on)
        assertFalse("the 24 V off", s.shelly.powered())
    }

    @Test
    fun `Shelly offers no update - said, with the Shelly app as the way round (v1_28 M65)`() = runTest {
        val s = setup(Provision.Pending, firmware = OLD)
        s.shelly.offered = null
        found(s)
        s.flow.update(SSID, PASS)
        until { s.flow.state.value.problem != null }
        assertEquals(PairFlow.NO_UPDATE, s.flow.state.value.problem)
        assertEquals(setOf(UpdateTask.RailOff, UpdateTask.Online), s.flow.state.value.firmwareUpdate?.tasks)
        assertFalse("Shelly.Update" in s.transport.methods)
        assertNull(s.store.pairing)
    }

    @Test
    fun `a new plug on 2_0_0 or later - set up from here by itself, never waiting for complete without a network (v1_28 M65)`() = runTest {
        val s = setup(Provision.Pending)
        val steps = mutableListOf<Pair<PairStep, Int>>()   // each step seen, with the requests sent by then
        backgroundScope.launch { s.flow.state.collect { steps += it.step to s.transport.methods.size } }
        found(s)
        settle(30_000)
        val waiting = s.flow.state.value
        assertEquals("the bond first, by itself", PairStep.SetUp, waiting.step)
        assertTrue(waiting.overBluetooth)
        assertEquals(setOf(SetUpTask.Window, SetUpTask.Bond, SetUpTask.Listed), waiting.tasks)
        assertTrue(s.shelly.bonded(EmulatedShelly.TABLET))
        assertFalse("waiting for the hotspot, not for complete", steps.any { it.first == PairStep.Complete })
        // Try again's setUp() on a pending plug goes the same way: no settings, no cloud, no wait for complete.
        s.flow.setUp()
        settle(30_000)
        assertFalse(steps.any { it.first == PairStep.Complete })
        assertFalse("Cloud.SetConfig" in s.transport.methods)
        assertFalse("WiFi.SetConfig" in s.transport.methods)

        s.flow.provision(SSID, PASS)
        until { s.flow.state.value.verify?.finished == true }
        val complete = steps.first { it.first == PairStep.Complete }.second
        assertTrue("the hotspot went before the wait for complete", "WiFi.SetConfig" in s.transport.methods.take(complete))
        assertTrue(s.flow.state.value.verify!!.passed)
        assertFalse(s.shelly.networkUp())
        assertEquals(false, s.store.networkWhenPaired)
        assertFalse("its switch off until Finish", s.store.on)
    }

    @Test
    fun `the hotspot - sent to the plug, held in memory while needed, in no store and no log line (v1_28 M65)`() = runTest {
        val s = setup(Provision.Pending, firmware = OLD)
        found(s)
        s.flow.update(SSID, PASS)
        until { s.flow.state.value.step == PairStep.Complete }
        val held = s.flow.state.value
        assertNotNull("held for the setup", held.hotspot)
        assertFalse("never in the state's words", PASS in held.toString())

        until { s.flow.state.value.verify?.finished == true }
        assertEquals("sent to the plug twice: the update's network, then its setup's", 2, s.transport.frames.count { PASS in it })
        assertNull("forgotten once the network closed", s.flow.state.value.hotspot)
        assertTrue(s.store.kept.isNotEmpty())
        for (secret in listOf(SSID, PASS)) {
            assertTrue("not in the store", s.store.kept.none { secret in it })
            assertTrue("not in a log line", s.logs.none { secret in it })
        }
        val frame = s.transport.frames.first { PASS in it }
        assertFalse("not in the emulator's log either", PASS in EmulatedShelly.redacted(frame) || SSID in EmulatedShelly.redacted(frame))
        s.flow.update(SSID, PASS)
        settle()
        assertNull("past the Update step, update() does nothing: the network stays off", s.flow.state.value.hotspot)
        assertFalse(s.shelly.networkUp())

        // Start over forgets it too.
        val again = setup(Provision.Pending, firmware = OLD)
        found(again)
        again.flow.update(SSID, PASS)
        settle()
        assertNotNull(again.flow.state.value.hotspot)
        again.flow.restart()
        assertNull(again.flow.state.value.hotspot)
    }

    // ---- v1.29 — M66: as it is -------------------------------------------------------------------------------------------

    @Test
    fun `as it is on 1_6_2 - no hotspot, its settings, the network closed and only then kept unlocked, then the check (v1_29 M66)`() = runTest {
        val s = setup(Provision.Pending, firmware = OLD)
        found(s)
        assertEquals(PairStep.Update, s.flow.state.value.step)
        s.flow.asIs()
        until { s.flow.state.value.verify?.finished == true }
        val done = s.flow.state.value
        assertTrue(done.asIs)
        assertFalse("no hotspot asked: no setup states before 1.7.5", done.hotspotWanted)
        assertTrue("checked by itself", done.verify!!.passed)
        assertFalse("off every network, its access point closed", s.shelly.networkUp())
        assertEquals("kept only once the network was off", false, s.store.networkWhenPaired)
        val pairing = s.store.pairing!!
        assertFalse("not locked", pairing.locked)
        assertNull("no bond to forget", pairing.bondEntry)
        assertFalse(s.shelly.bonded(EmulatedShelly.TABLET))
        assertEquals(OLD, s.shelly.firmware)
        assertEquals(
            listOf("Switch.Set", "Switch.SetConfig", "BLE.SetConfig", "Shelly.Reboot", "Cloud.SetConfig", "WiFi.SetConfig", "Switch.Set"),
            s.transport.methods.filter { it !in POLLS },
        )
        val settings = JSONObject(s.transport.frames.first { "Switch.SetConfig" in it }).getJSONObject("params").getJSONObject("config")
        assertEquals("off", settings.getString("initial_state"))
        assertTrue(settings.getBoolean("in_locked"))
        assertFalse(s.store.on)
        s.flow.finish()
        settle()
        assertEquals(PairStep.Done, s.flow.state.value.step)
        assertTrue(s.store.on)
    }

    @Test
    fun `as it is on 1_7_5 - the hotspot once, complete, still answering unpaired, then closed and kept unlocked, and refused after complete keeps nothing (v1_29 M66)`() = runTest {
        val s = setup(Provision.Pending, firmware = WITH_STATES)
        found(s)
        s.flow.asIs()
        until { s.flow.state.value.hotspotWanted }
        assertNull("nothing kept before the hotspot", s.store.pairing)
        s.flow.provision(SSID, PASS)
        until { s.flow.state.value.verify?.finished == true }
        assertTrue(s.transport.seen.any { it.first == Provision.Complete })
        assertFalse(s.shelly.networkUp())
        assertEquals(false, s.store.networkWhenPaired)
        assertEquals(false, s.store.pairing?.locked)
        assertNull("the hotspot forgotten", s.flow.state.value.hotspot)
        assertTrue(s.logs.none { PASS in it || SSID in it })

        // Firmware that lets only paired tablets in once set up: the words, nothing kept.
        val r = setup(Provision.Pending, firmware = WITH_STATES)
        r.shelly.refuseUnpaired = true
        found(r)
        r.flow.asIs()
        until { r.flow.state.value.hotspotWanted }
        r.flow.provision(SSID, PASS)
        until { r.flow.state.value.problem != null }
        assertEquals(PairFlow.AS_IS_REFUSED, r.flow.state.value.problem)
        assertNull("nothing kept", r.store.pairing)
        assertFalse(r.store.on)
    }

    @Test
    fun `the update refused - Use it as it is still offered from the Update step (v1_29 M66)`() = runTest {
        val s = setup(Provision.Pending, firmware = OLD)
        s.shelly.refuseUpdate = true
        found(s)
        s.flow.update(SSID, PASS)
        until { s.flow.state.value.problem != null }
        assertEquals(PairFlow.UPDATE_REFUSED, s.flow.state.value.problem)
        assertEquals(PairStep.Update, s.flow.state.value.step)
        s.flow.asIs()
        until { s.flow.state.value.verify?.finished == true }
        assertEquals(false, s.store.pairing?.locked)
        assertEquals(false, s.store.networkWhenPaired)
    }

    private companion object {
        const val BOARDS_UP_MS = 900L
        const val WITH_STATES = "1.7.5"
        const val SETTLE_MS = 1_000L
        const val OTHER_TABLET = "the old tablet"
        const val OLD = "1.6.2"
        const val SSID = "Steven's iPhone"
        const val PASS = "correct horse battery"

        /** The reads made while waiting: left out of the order. */
        val POLLS = setOf("Shelly.GetDeviceInfo", "BLE.ListPairedDevices", "WiFi.GetStatus")
    }
}
