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
 * The Pair screen's work (v1.26 — M60; the SwitchBot Plug Mini since v1.36 — M72) against the emulator's fake plug
 * ([EmulatedSwitchBot]), on a fake clock: find (strongest first), tap, the on-and-off check, then the address kept with the
 * switch on; a plug that doesn't answer says so and nothing is kept.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairFlowTest {
    /** The pairing as kept, and whether the plug was on at that moment. */
    private class Store(private val plug: EmulatedSwitchBot) : PairingStore {
        var pairing: PlugPairing? = null
        var on = false
        var plugOnWhenPaired: Boolean? = null

        override suspend fun pair(pairing: PlugPairing, on: Boolean) {
            plugOnWhenPaired = plug.output()
            this.pairing = pairing
            this.on = on
        }

        override suspend fun setOn(on: Boolean) {
            this.on = on
        }
    }

    private class Setup(val plug: EmulatedSwitchBot, val transport: FakePlugTransport, val store: Store, val flow: PairFlow, val logs: List<String>)

    private fun TestScope.setup(): Setup {
        val plug = EmulatedSwitchBot({ currentTime })   // plugged in, it came on by itself
        val transport = FakePlugTransport(plug)
        val logs = mutableListOf<String>()
        val client = PlugClient(transport, backgroundScope, address = { null }, io = StandardTestDispatcher(testScheduler), log = { logs += it })
        val store = Store(plug)
        return Setup(plug, transport, store, PairFlow(client, store, backgroundScope, log = { logs += it }), logs)
    }

    private fun TestScope.settle(ms: Long = 1_000) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `find, tap, the on-and-off check, then the address kept and the switch on`() = runTest {
        val s = setup()
        s.transport.others += FoundPlug("11:22:33:44:55:66", "11:22:33:44:55:66", "Plug Mini (JP)", rssi = -80)
        s.flow.search()
        settle()
        val found = s.flow.state.value.found
        assertEquals("the strongest first", listOf(EmulatedSwitchBot.ADDRESS, "11:22:33:44:55:66"), found.map { it.address })
        assertEquals("47CA", SwitchBot.lastFour(found.first().mac))
        assertNull(s.flow.state.value.problem)

        s.flow.choose(found.first())
        settle()
        val state = s.flow.state.value
        assertEquals(PairStep.Done, state.step)
        assertTrue("it clicked on and off", state.clicked)
        assertNull(state.problem)
        assertEquals(listOf("off", "status", "on", "status", "off"), s.plug.commands)
        assertEquals(listOf("570f50010100", "570f5101", "570f50010180", "570f5101", "570f50010100"), s.transport.frames)
        assertFalse("left off", s.plug.output())
        assertEquals(PlugPairing(EmulatedSwitchBot.ADDRESS, EmulatedSwitchBot.MAC), s.store.pairing)
        assertTrue("the plug switches the rail from now", s.store.on)
        assertEquals("kept only once it was off again", false, s.store.plugOnWhenPaired)
    }

    @Test
    fun `a plug that doesn't answer - a clear message, nothing kept`() = runTest {
        val s = setup()
        s.flow.search()
        settle()
        val plug = s.flow.state.value.found.single()
        s.plug.silent = true
        s.flow.choose(plug)
        settle(20_000)
        assertEquals(PairStep.Check, s.flow.state.value.step)
        assertEquals(PairFlow.NO_ANSWER, s.flow.state.value.problem)
        assertFalse(s.flow.state.value.clicked)
        assertNull("nothing kept", s.store.pairing)

        // Answering again: the same tap pairs it.
        s.plug.silent = false
        s.flow.choose(plug)
        settle()
        assertEquals(PairStep.Done, s.flow.state.value.step)
        assertEquals(EmulatedSwitchBot.ADDRESS, s.store.pairing?.address)
    }

    @Test
    fun `no plug in range - said, and nothing kept`() = runTest {
        val s = setup()
        s.transport.away = true
        s.flow.search()
        settle()
        assertEquals(PairFlow.NOT_FOUND, s.flow.state.value.problem)
        assertTrue(s.flow.state.value.found.isEmpty())
        assertNull(s.store.pairing)
    }
}
