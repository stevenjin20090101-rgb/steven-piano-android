// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.instruments

import dev.stevenjin.stevenpiano.ble.FakeConsole
import dev.stevenjin.stevenpiano.ble.FakeLinkExecutor
import dev.stevenjin.stevenpiano.ble.FakePianoLink
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.ScanThrottle
import dev.stevenjin.stevenpiano.midi.InstrumentProfile
import dev.stevenjin.stevenpiano.midi.MidiBatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The instrument that plays (v1.11 — M29): everything goes to the chosen link, and only Steven Piano has a console. */
@OptIn(ExperimentalCoroutinesApi::class)
class InstrumentSwitchTest {
    private val scope = TestScope(StandardTestDispatcher())
    private val executor = FakeLinkExecutor()
    private val ports = FakeMidiPorts()
    private val bluetooth = FakeMidiBluetooth()
    private val devices = MidiDevices(ports, bluetooth, executor, ScanThrottle(), log = {})
    private val midi = MidiPortLink(devices, bluetooth, executor, log = {}, nanoTime = { executor.nowMs * 1_000_000L }, startThread = {})
    private val steven = FakePianoLink().apply { connectedWith(FakeConsole()) }
    private val switch = InstrumentSwitch(steven, midi, scope)
    private val fp30 = FakeMidiPorts.usb("FP-30X", inputs = 1, outputs = 0)

    private fun note(key: Int) = MidiBatch().apply { add(0x90, key, 80) }

    @Test
    fun `Steven Piano at first, with its console, its state and its rules`() {
        scope.runCurrent()
        assertEquals(InstrumentKind.StevenPiano, switch.kind.value)
        assertSame(InstrumentProfile.StevenPiano, switch.profile.value)
        assertEquals(LinkState.Connected("Steven Piano", 255), switch.state.value)
        assertNotNull(switch.console)
        switch.send(note(60), dropPending = false)
        switch.sendLive(note(62))
        assertEquals(listOf("90 3C 50", "90 3E 50"), steven.messages)
        assertEquals(listOf(false, true), steven.sent.map { it.live })
    }

    @Test
    fun `a MIDI piano chosen has its state, its rules, no console, and everything sent goes to it`() {
        devices.start()
        ports.listed += fp30
        midi.choose(MidiChoice.of(fp30))
        switch.select(InstrumentKind.MidiPiano)
        switch.connect(null)
        executor.runDue()
        scope.runCurrent()
        assertEquals(LinkState.Connected("FP-30X", 0, 1), switch.state.value)
        assertSame(InstrumentProfile.StandardPiano, switch.profile.value)
        assertNull("the console is Steven Piano's", switch.console)
        assertNull(switch.ota)
        switch.send(note(21), dropPending = false)
        midi.drain()
        assertEquals(listOf("90 15 50"), ports.device(fp30.key)!!.sent)
        assertTrue("nothing reached Steven Piano", steven.messages.isEmpty())
        assertTrue(switch.emergencySilence(100))
        assertEquals(emptyList<Long>(), steven.emergencySilences)
        switch.select(InstrumentKind.StevenPiano)
        scope.runCurrent()
        assertEquals(LinkState.Connected("Steven Piano", 255), switch.state.value)
    }
}
