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
import dev.stevenjin.stevenpiano.ble.ScanThrottle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The registry of MIDI devices (v1.11 — M29): the picker's search, foreign addresses, shared opens, and the picker's rows. */
class MidiDevicesTest {
    private val executor = FakeLinkExecutor()
    private val ports = FakeMidiPorts()
    private val bluetooth = FakeMidiBluetooth()
    private val throttle = ScanThrottle()
    private val logged = mutableListOf<String>()
    private val piano = "C8:2E:18:00:11:22"
    private val devices = MidiDevices(ports, bluetooth, executor, throttle, log = { logged += it }, pianoAddress = { piano })

    @Test
    fun `the search runs 12 s and lists named devices, never the piano, its address or a nameless one`() {
        devices.startScan()
        executor.runDue()
        assertEquals(MidiScan.Phase.Looking, devices.scan.value.phase)
        assertTrue(bluetooth.scanning)
        bluetooth.find("11:22:33:44:55:66", "Roland FP-30X")
        bluetooth.find("AA:BB:CC:00:00:01", "Steven Piano")
        bluetooth.find(piano.lowercase(), "Renamed")
        bluetooth.find("AA:BB:CC:00:00:02", null)
        bluetooth.find("AA:BB:CC:00:00:03", "  \u0007 ")
        bluetooth.find("11:22:33:44:55:66", "Roland FP-30X")
        bluetooth.find("22:22:33:44:55:66", "Yamaha\nP-125")
        executor.runDue()
        assertEquals(
            listOf(FoundMidi("11:22:33:44:55:66", "Roland FP-30X"), FoundMidi("22:22:33:44:55:66", "Yamaha P-125")),
            devices.scan.value.found,
        )
        assertTrue("seen named: never the piano", devices.isForeign("11:22:33:44:55:66"))
        assertFalse("nameless: perhaps the piano", devices.isForeign("AA:BB:CC:00:00:02"))
        assertFalse(devices.isForeign("AA:BB:CC:00:00:01"))
        executor.advance(12_000)
        assertEquals(MidiScan.Phase.Done, devices.scan.value.phase)
        assertFalse(bluetooth.scanning)
        assertEquals(2, devices.scan.value.found.size)
    }

    @Test
    fun `the search shares the app's scan budget, and waits when it is spent`() {
        repeat(5) { throttle.acquire(0L) }   // the piano's link scanned five times just now
        devices.startScan()
        executor.runDue()
        assertEquals(MidiScan.Phase.Waiting, devices.scan.value.phase)
        assertEquals(30_000L, devices.scan.value.waitMs)
        assertEquals(0, bluetooth.scans)
        executor.advance(30_000)
        assertEquals(MidiScan.Phase.Looking, devices.scan.value.phase)
        assertEquals(1, bluetooth.scans)
        devices.stopScan()
        executor.runDue()
        assertEquals(MidiScan.Phase.Idle, devices.scan.value.phase)
        assertFalse(bluetooth.scanning)
    }

    @Test
    fun `with Bluetooth off or a permission missing the search says why and does not scan`() {
        bluetooth.blocker = LinkError.BluetoothOff
        devices.startScan()
        executor.runDue()
        assertEquals(MidiScan(MidiScan.Phase.Blocked, problem = LinkError.BluetoothOff), devices.scan.value)
        bluetooth.blocker = LinkError.PermissionMissing
        devices.startScan()
        executor.runDue()
        assertEquals(LinkError.PermissionMissing, devices.scan.value.problem)
        assertEquals(0, bluetooth.scans)
    }

    @Test
    fun `one device opened for two holders, closed once both let go`() {
        val piano88 = FakeMidiPorts.usb("FP-30X", inputs = 1, outputs = 1)
        ports.listed += piano88
        devices.start()
        executor.runDue()
        var first: OpenMidiDevice? = null
        var second: OpenMidiDevice? = null
        val a = devices.open(MidiChoice.of(piano88), onOpened = { first = it }, onLost = {})
        executor.runDue()
        val b = devices.open(MidiChoice.of(piano88), onOpened = { second = it }, onLost = {})
        executor.runDue()
        assertEquals(1, ports.opens)
        assertSame(first, second)
        a.close()
        executor.runDue()
        assertFalse(ports.device(piano88.key)!!.closed)
        b.close()
        executor.runDue()
        assertTrue(ports.device(piano88.key)!!.closed)
    }

    @Test
    fun `an open that never answers fails after 15 s, and a late answer is closed`() {
        ports.answerOpens = false
        val address = "11:22:33:44:55:66"
        var heard: Any? = "nothing yet"
        devices.open(MidiChoice.bluetooth(FoundMidi(address, "Roland")), onOpened = { heard = it }, onLost = {})
        executor.runDue()
        assertTrue("claimed before Android's Bluetooth MIDI service connects", devices.isForeign(address))
        executor.advance(14_999)
        assertEquals("nothing yet", heard)
        executor.advance(1)
        assertNull(heard)
        val (ref, answer) = ports.pending.single()
        val late = FakeMidiPorts().FakeDevice(ref)
        answer(late)
        executor.runDue()
        assertTrue(late.closed)
    }

    @Test
    fun `a device removed tells its holders it is lost`() {
        val keys = FakeMidiPorts.usb("Keystation")
        ports.listed += keys
        devices.start()
        executor.runDue()
        var lost = 0
        devices.open(MidiChoice.of(keys), onOpened = {}, onLost = { lost++ })
        executor.runDue()
        ports.unplug(keys)
        executor.runDue()
        assertEquals(1, lost)
        assertTrue(ports.device(keys.key)!!.closed)
        assertTrue(logged.contains("MIDI: \"Keystation\" removed (USB)"))
    }

    @Test
    fun `Bluetooth MIDI devices Android has open are foreign to the piano's link`() {
        ports.listed += FakeMidiPorts.bluetooth("Casio", "33:22:33:44:55:66")
        devices.start()
        executor.runDue()
        assertTrue(devices.isForeign("33:22:33:44:55:66"))
        assertEquals(1, devices.devices.value.size)
    }

    @Test
    fun `a claim makes an address foreign at once and for good`() {
        devices.claim("44:22:33:44:55:66")
        assertTrue(devices.isForeign("44:22:33:44:55:66"))
        devices.unclaim("44:22:33:44:55:66")
        assertTrue("the app has known it as a MIDI device", devices.isForeign("44:22:33:44:55:66"))
        devices.claim(null)
        devices.claim("")
    }

    // ---- The picker's rows --------------------------------------------------------------------

    @Test
    fun `the picker lists USB first, then Bluetooth open, then Bluetooth found, each once, never the piano`() {
        val usbKeys = FakeMidiPorts.usb("Keystation", serial = "1")
        val usbOut = FakeMidiPorts.usb("MIDI interface", serial = "2", inputs = 1, outputs = 0)
        val test = MidiDeviceRef(MidiNames.virtualKey("Steven Piano", "Test MIDI device", null), "Test MIDI device", MidiTransport.VIRTUAL, 1, 1)
        val openBt = FakeMidiPorts.bluetooth("Roland FP-30X", "11:22:33:44:55:66")
        val pianoListed = FakeMidiPorts.bluetooth("Steven Piano", "AA:BB:CC:00:00:01")
        val listed = listOf(openBt, test, usbKeys, usbOut, pianoListed)
        val found = listOf(
            FoundMidi("11:22:33:44:55:66", "Roland FP-30X"),
            FoundMidi("22:22:33:44:55:66", "Yamaha P-125"),
            FoundMidi(piano, "Renamed piano"),
        )
        val keyboards = MidiPicker.rows(MidiPurpose.Keyboard, listed, found, piano)
        assertEquals(listOf("Keystation", "Test MIDI device", "Roland FP-30X", "Yamaha P-125"), keyboards.map { it.name })
        assertEquals(listOf(MidiTransport.USB, MidiTransport.VIRTUAL, MidiTransport.BLUETOOTH, MidiTransport.BLUETOOTH), keyboards.map { it.transport })
        assertSame(openBt, keyboards[2].listed)
        assertNull("found by the search: opened by its address", keyboards[3].listed)
        val instruments = MidiPicker.rows(MidiPurpose.Instrument, listed, found, piano)
        assertEquals(listOf("MIDI interface", "Test MIDI device", "Roland FP-30X", "Yamaha P-125"), instruments.map { it.name })
    }
}
