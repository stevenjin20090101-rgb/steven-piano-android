// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.instruments

import dev.stevenjin.stevenpiano.ble.FakeLinkExecutor
import dev.stevenjin.stevenpiano.ble.LinkError
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.ble.ScanThrottle
import dev.stevenjin.stevenpiano.midi.MidiBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A MIDI piano as the app's output (v1.11 — M29), its writer run by hand on virtual time. */
class MidiPortLinkTest {
    private val executor = FakeLinkExecutor()
    private val ports = FakeMidiPorts()
    private val bluetooth = FakeMidiBluetooth()
    private val logged = mutableListOf<String>()
    private val devices = MidiDevices(ports, bluetooth, executor, ScanThrottle(), log = { logged += it }, pianoAddress = { "C8:2E:18:00:11:22" })
    private val link = MidiPortLink(devices, bluetooth, executor, log = { logged += it }, nanoTime = { executor.nowMs * 1_000_000L }, startThread = {})
    private val fp30 = FakeMidiPorts.usb("FP-30X", inputs = 1, outputs = 1)

    init {
        devices.start()
        executor.runDue()
    }

    private fun state() = link.state.value

    private fun batch(vararg messages: Triple<Int, Int, Int>) = MidiBatch().apply { messages.forEach { (s, d1, d2) -> add(s, d1, d2) } }

    /** What the piano was sent, message by message. */
    private fun sent(key: String = fp30.key): List<String> =
        ports.device(key)!!.sent.flatMap { line -> line.split(' ').chunked(3).map { it.joinToString(" ") } }

    private fun connected(): FakeMidiPorts.FakeDevice {
        ports.listed += fp30
        link.choose(MidiChoice.of(fp30))
        link.connect(null)
        executor.runDue()
        return ports.device(fp30.key)!!
    }

    @Test
    fun `a USB MIDI piano connects, and each connection is a new epoch`() {
        connected()
        assertEquals(LinkState.Connected("FP-30X", 0, 1), state())
        assertNull("no console: that is Steven Piano's", link.console)
        link.disconnect()
        executor.runDue()
        assertEquals(LinkState.Disconnected, state())
        link.connect(null)
        executor.runDue()
        assertEquals(LinkState.Connected("FP-30X", 0, 2), state())
    }

    @Test
    fun `messages go whole, never running status, at the piano link's pace, the live lane first`() {
        connected()
        link.send(batch(*Array(30) { Triple(0x90, 30 + it, 64) }), dropPending = false)
        link.sendLive(batch(Triple(0x90, 100, 90)))
        assertEquals(0L, link.drain())
        val first = sent()
        assertEquals(20, first.size)
        assertEquals("the live key first", "90 64 5A", first.first())
        assertEquals("90 1E 40", first[1])
        assertTrue("whole messages: three bytes each", first.all { it.split(' ').size == 3 })
        assertTrue("then it waits for the pace", link.drain() > 0)
        executor.advance(5)
        link.drain()
        assertEquals(25, sent().size)
        executor.advance(100)
        link.drain()
        assertEquals(31, sent().size)
        assertTrue(link.flush(10))
    }

    @Test
    fun `the stop sequence replaces what waits`() {
        connected()
        link.send(batch(*Array(30) { Triple(0x90, 30 + it, 64) }), dropPending = false)
        link.send(batch(Triple(0xB0, 64, 0), Triple(0xB0, 123, 0)), dropPending = true)
        link.drain()
        assertEquals(listOf("B0 40 00", "B0 7B 00"), sent())
    }

    @Test
    fun `disconnect lets go of every key it sent, every pedal, then All Sound Off and All Notes Off`() {
        val piano = connected()
        link.send(batch(Triple(0x90, 60, 80), Triple(0x90, 108, 80), Triple(0x90, 21, 80), Triple(0x80, 21, 0)), dropPending = false)
        link.drain()
        piano.sent.clear()
        link.disconnect()
        executor.runDue()
        assertEquals(listOf("80 3C 00", "80 6C 00", "B0 40 00", "B0 42 00", "B0 43 00", "B0 78 00", "B0 7B 00"), sent())
        assertTrue(piano.closed)
        assertEquals(1, piano.outClosed)
    }

    @Test
    fun `the crash handler's stop goes straight to the port`() {
        connected()
        link.send(batch(Triple(0x90, 62, 80)), dropPending = false)
        link.drain()
        ports.device(fp30.key)!!.sent.clear()
        assertTrue(link.emergencySilence(200))
        assertEquals(listOf("80 3E 00", "B0 40 00", "B0 42 00", "B0 43 00", "B0 78 00", "B0 7B 00"), sent())
    }

    @Test
    fun `an input another app holds is said so`() {
        ports.inputBusy = true
        connected()
        assertEquals(LinkError.InstrumentBusy("FP-30X").toState(), state())
        assertTrue((state() as LinkState.Error).message.startsWith("Another app is using FP-30X."))
    }

    @Test
    fun `unplugged it waits for the cable, and comes back with a new epoch`() {
        connected()
        ports.unplug(fp30)
        executor.runDue()
        assertEquals(LinkError.InstrumentGone("FP-30X").toState(), state())
        link.send(batch(Triple(0x90, 60, 80)), dropPending = false)   // while it is away: dropped, never sent later
        link.drain()
        ports.plug(fp30)
        executor.runDue()
        assertEquals(LinkState.Connected("FP-30X", 0, 2), state())
        link.drain()
        assertEquals(emptyList<String>(), sent())
    }

    @Test
    fun `a USB piano not plugged in says so and connects when it is`() {
        link.choose(MidiChoice.of(fp30))
        link.connect(null)
        executor.runDue()
        assertEquals(LinkError.InstrumentGone("FP-30X").toState(), state())
        ports.plug(fp30)
        executor.runDue()
        assertTrue(state() is LinkState.Connected)
    }

    @Test
    fun `a Bluetooth piano not paired is asked once, opened once paired, and retried with backoff when lost`() {
        val address = "11:22:33:44:55:66"
        link.choose(MidiChoice.bluetooth(FoundMidi(address, "Roland FP-30X")))
        link.connect(null)
        executor.runDue()
        assertEquals(LinkError.InstrumentPairing("Roland FP-30X").toState(), state())
        assertEquals(listOf(address), bluetooth.bondsAsked)
        ports.bluetoothHere += address
        bluetooth.bond(address)
        executor.runDue()
        assertTrue(state() is LinkState.Connected)
        ports.bluetoothHere.clear()
        ports.unplug(ports.device(MidiNames.bluetoothKey(address))!!.device)
        executor.runDue()
        assertEquals(LinkState.Reconnecting(1), state())
        val opens = ports.bluetoothOpens.size
        executor.advance(1_000)
        assertEquals(opens + 1, ports.bluetoothOpens.size)
        ports.bluetoothHere += address
        executor.advance(2_000)
        assertTrue(state() is LinkState.Connected)
        assertEquals(listOf(address), bluetooth.bondsAsked)
    }

    @Test
    fun `Steven Piano is never chosen nor opened as a MIDI piano, nor asked to pair, and its address stays its own`() {
        link.choose(MidiChoice.bluetooth(FoundMidi("C8:2E:18:00:11:22", "Old name")))
        link.choose(MidiChoice.bluetooth(FoundMidi("AA:BB:CC:00:00:09", "Steven Piano")))
        link.connect(null)
        executor.runDue()
        assertNull(link.choice)
        assertEquals(0, ports.opens)
        assertTrue(bluetooth.bondsAsked.isEmpty())
        assertFalse("the piano's link still reaches it", devices.isForeign("C8:2E:18:00:11:22"))
        devices.claim("C8:2E:18:00:11:22")
        assertFalse("whatever was claimed", devices.isForeign("c8:2e:18:00:11:22"))
    }

    @Test
    fun `choosing another piano lets go of the first`() {
        val first = connected()
        link.send(batch(Triple(0x90, 60, 80)), dropPending = false)
        link.drain()
        val other = FakeMidiPorts.usb("P-125", serial = "7", inputs = 1, outputs = 0)
        ports.listed += other
        link.choose(MidiChoice.of(other))
        executor.runDue()
        assertTrue(first.closed)
        assertEquals(listOf("90 3C 50", "80 3C 00", "B0 40 00", "B0 42 00", "B0 43 00", "B0 78 00", "B0 7B 00"), sent())
        assertEquals(LinkState.Disconnected, state())
        link.connect(null)
        executor.runDue()
        assertEquals("P-125", (state() as LinkState.Connected).name)
        assertFalse(ports.device(other.key)!!.closed)
    }
}
