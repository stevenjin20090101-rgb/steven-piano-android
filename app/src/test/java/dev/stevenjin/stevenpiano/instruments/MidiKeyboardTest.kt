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
import dev.stevenjin.stevenpiano.ble.ScanThrottle
import dev.stevenjin.stevenpiano.midi.KeyEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MIDI keyboard's state machine on virtual time (v1.11 — M29): connecting, hearing, letting go, coming back. */
class MidiKeyboardTest {
    private val executor = FakeLinkExecutor()
    private val ports = FakeMidiPorts()
    private val bluetooth = FakeMidiBluetooth()
    private val logged = mutableListOf<String>()
    private var piano: String? = "C8:2E:18:00:11:22"
    private val devices = MidiDevices(ports, bluetooth, executor, ScanThrottle(), log = { logged += it }, pianoAddress = { piano })
    private val remembered = mutableListOf<KeyboardState.Chosen?>()
    private val keyboard = MidiKeyboard(devices, bluetooth, executor, log = { logged += it }, remember = { remembered += it }, nanoTime = { executor.nowMs * 1_000_000L })

    /** What the keyboard's listeners heard, in words. */
    private val heard = mutableListOf<String>()

    init {
        keyboard.listen(object : KeyboardListener {
            override fun onKeys(events: KeyEvents, arrivalNanos: Long, stampNanos: Long) {
                for (i in 0 until events.size) {
                    heard += when (events.type(i)) {
                        KeyEvents.DOWN -> "down ${events.key(i)} ${events.value(i)}"
                        KeyEvents.UP -> "up ${events.key(i)}"
                        else -> "pedal ${events.key(i)} ${events.value(i)}"
                    }
                }
            }

            override fun onLetGo(reason: String) {
                heard += "let go"
            }
        })
        devices.start()
        executor.runDue()
    }

    private val fp30 = FakeMidiPorts.usb("FP-30X", outputs = 2)
    private val roland = "11:22:33:44:55:66"

    private fun state() = keyboard.state.value

    private fun usbChosen(device: MidiDeviceRef = fp30) = KeyboardState.Chosen(device.key, device.name, MidiTransport.USB)

    @Test
    fun `a USB keyboard remembered opens at start, every output port heard, its keys passed on and shown folded`() {
        ports.listed += fp30
        keyboard.restore(usbChosen())
        executor.runDue()
        assertEquals(KeyboardState.Phase.Connected, state().phase)
        assertEquals(setOf(0, 1), ports.device(fp30.key)!!.receivers.keys)
        val before = keyboard.changes.get()
        ports.play(fp30.key, "90 3C 64")
        assertEquals(listOf("down 60 100"), heard)
        assertTrue(keyboard.isHeld(60))
        assertTrue(keyboard.changes.get() > before)
        ports.play(fp30.key, "90 15 50 90 6C 50", port = 1)   // A0 and C8: drawn an octave in
        assertTrue(keyboard.isHeld(33))
        assertTrue(keyboard.isHeld(96))
        assertFalse(keyboard.isHeld(21))
        ports.play(fp30.key, "80 3C 00 B0 40 7F")
        assertFalse(keyboard.isHeld(60))
        assertEquals(listOf("down 60 100", "down 21 80", "down 108 80", "up 60", "pedal 64 127"), heard)
        assertTrue(logged.contains("Keyboard: \"FP-30X\" connected (USB, 2 ports)"))
    }

    @Test
    fun `a USB keyboard not plugged in waits, and connects when Android lists it`() {
        keyboard.restore(usbChosen())
        executor.runDue()
        assertEquals(KeyboardState.Phase.NotConnected, state().phase)
        assertEquals(0, ports.opens)
        ports.plug(fp30)
        executor.runDue()
        assertEquals(KeyboardState.Phase.Connected, state().phase)
    }

    @Test
    fun `unplugged with keys and the pedal down, everything lets go first, and plugged in again it comes back`() {
        ports.listed += fp30
        keyboard.restore(usbChosen())
        executor.runDue()
        ports.play(fp30.key, "90 3C 64 90 40 50 B0 40 7F")
        heard.clear()
        ports.unplug(fp30)
        executor.runDue()
        assertEquals(listOf("up 60", "up 64", "pedal 64 0", "let go"), heard)
        assertFalse(keyboard.isHeld(60))
        assertEquals(KeyboardState.Phase.NotConnected, state().phase)
        assertTrue(ports.device(fp30.key)!!.closed)
        executor.advance(60_000)
        assertEquals("USB waits for the cable, never polls", 1, ports.opens)
        ports.plug(fp30)
        executor.runDue()
        assertEquals(KeyboardState.Phase.Connected, state().phase)
        ports.play(fp30.key, "80 3C 00")   // a release from before the cable came out: nothing is held
        assertEquals(listOf("up 60", "up 64", "pedal 64 0", "let go"), heard)
    }

    @Test
    fun `a keyboard that sent Active Sensing and falls silent lets go after a second`() {
        ports.listed += fp30
        keyboard.restore(usbChosen())
        executor.runDue()
        ports.play(fp30.key, "FE 90 3C 64")
        executor.advance(1_000)
        assertTrue(keyboard.isHeld(60))
        executor.advance(300)
        assertFalse(keyboard.isHeld(60))
        assertEquals(listOf("down 60 100", "up 60", "let go"), heard)
        assertTrue(logged.contains("Keyboard: let go of everything it held (its Active Sensing stopped)"))
        assertEquals("still connected", KeyboardState.Phase.Connected, state().phase)
    }

    @Test
    fun `keys or a pedal down with no byte for a minute let go`() {
        ports.listed += fp30
        keyboard.restore(usbChosen())
        executor.runDue()
        ports.play(fp30.key, "B0 40 7F")
        executor.advance(59_900)
        assertEquals(listOf("pedal 64 127"), heard)
        executor.advance(400)
        assertEquals(listOf("pedal 64 127", "pedal 64 0", "let go"), heard)
    }

    @Test
    fun `a Bluetooth keyboard that won't open while not paired is asked to pair once, and opens once paired`() {
        keyboard.choose(MidiChoice.bluetooth(FoundMidi(roland, "Roland FP-30X")))
        executor.runDue()
        assertEquals(KeyboardState.Phase.NeedsPairing, state().phase)
        assertEquals(listOf(roland), bluetooth.bondsAsked)
        assertTrue(devices.isForeign(roland))
        ports.bluetoothHere += roland
        bluetooth.bond(roland)
        executor.runDue()
        assertEquals(KeyboardState.Phase.Connected, state().phase)
        assertEquals(listOf(KeyboardState.Chosen(MidiNames.bluetoothKey(roland), "Roland FP-30X", MidiTransport.BLUETOOTH)), remembered)
    }

    @Test
    fun `a Bluetooth keyboard lost is opened again with the piano link's backoff`() {
        bluetooth.bonded += roland
        ports.bluetoothHere += roland
        keyboard.choose(MidiChoice.bluetooth(FoundMidi(roland, "Roland FP-30X")))
        executor.runDue()
        assertEquals(KeyboardState.Phase.Connected, state().phase)
        ports.bluetoothHere.clear()
        val device = ports.device(MidiNames.bluetoothKey(roland))!!.device
        ports.unplug(device)
        executor.runDue()
        assertEquals(KeyboardState.Phase.NotConnected, state().phase)
        assertTrue("paired already: no request", bluetooth.bondsAsked.isEmpty())
        val opens = ports.bluetoothOpens.size
        executor.advance(1_000)
        assertEquals(opens + 1, ports.bluetoothOpens.size)
        executor.advance(2_000)
        assertEquals(opens + 2, ports.bluetoothOpens.size)
        executor.advance(4_000)
        assertEquals(opens + 3, ports.bluetoothOpens.size)
        ports.bluetoothHere += roland
        executor.advance(8_000)
        assertEquals(KeyboardState.Phase.Connected, state().phase)
    }

    @Test
    fun `forgetting lets go, remembers none, and leaves the address foreign to the piano's link`() {
        bluetooth.bonded += roland
        ports.bluetoothHere += roland
        keyboard.choose(MidiChoice.bluetooth(FoundMidi(roland, "Roland FP-30X")))
        executor.runDue()
        ports.play(MidiNames.bluetoothKey(roland), "90 3C 64")
        heard.clear()
        keyboard.forget()
        executor.runDue()
        assertEquals(listOf("up 60", "let go"), heard)
        assertEquals(KeyboardState(), state())
        assertNull(remembered.last())
        assertTrue(devices.isForeign(roland))
        assertTrue(ports.device(MidiNames.bluetoothKey(roland))!!.closed)
    }

    @Test
    fun `choosing another keyboard lets go of the first`() {
        ports.listed += fp30
        keyboard.restore(usbChosen())
        executor.runDue()
        ports.play(fp30.key, "90 3C 64")
        val other = FakeMidiPorts.usb("Keystation", serial = "9")
        ports.listed += other
        keyboard.choose(MidiChoice.of(other))
        executor.runDue()
        assertEquals(listOf("down 60 100", "up 60", "let go"), heard)
        assertTrue(ports.device(fp30.key)!!.closed)
        assertEquals("Keystation", state().chosen?.name)
        assertEquals(KeyboardState.Phase.Connected, state().phase)
    }

    @Test
    fun `a remembered keyboard's address is foreign to the piano's link at once, before anything runs`() {
        keyboard.restore(KeyboardState.Chosen(MidiNames.bluetoothKey(roland), "Roland", MidiTransport.BLUETOOTH))
        assertTrue(devices.isForeign(roland.lowercase()))
    }

    @Test
    fun `Steven Piano is never a keyboard, by its name or the remembered piano's address, and never asked to pair`() {
        keyboard.choose(MidiChoice.bluetooth(FoundMidi("AA:BB:CC:00:00:01", "Steven Piano")))
        keyboard.choose(MidiChoice.bluetooth(FoundMidi(piano!!, "Some name")))
        executor.runDue()
        assertNull(state().chosen)
        assertEquals(0, ports.opens)
        assertTrue(bluetooth.bondsAsked.isEmpty())

        // A keyboard remembered at the address the piano is now remembered at: forgotten, never opened.
        keyboard.restore(KeyboardState.Chosen(MidiNames.bluetoothKey(piano!!), "Old keys", MidiTransport.BLUETOOTH))
        executor.runDue()
        assertNull(state().chosen)
        assertNull(remembered.last())
        assertEquals(0, ports.opens)
    }

    @Test
    fun `a device that turns out to be Steven Piano once open is closed, forgotten, never opened again nor paired with`() {
        val disguised = "AA:BB:CC:00:00:04"
        ports.answerOpens = false
        keyboard.choose(MidiChoice(MidiNames.bluetoothKey(disguised), "Practice keys", MidiTransport.BLUETOOTH, disguised))
        executor.runDue()
        val (ref, answer) = ports.pending.single()
        var closed = false
        answer(object : OpenMidiDevice {
            override val device = ref.copy(name = "Steven Piano")

            override fun receive(port: Int, onBytes: MidiBytes): java.io.Closeable? = null

            override fun sender(port: Int): MidiOut? = null

            override fun close() {
                closed = true
            }
        })
        executor.runDue()
        assertTrue(closed)
        assertTrue(devices.isPiano(disguised, null))
        assertTrue("never asked to pair", bluetooth.bondsAsked.isEmpty())
        assertNull(state().chosen)
        assertNull(remembered.last())
        executor.advance(120_000)
        assertEquals("never opened again", 1, ports.opens)
        assertTrue(logged.any { it.contains("is Steven Piano: closed at once") })
    }

    @Test
    fun `malformed bytes are counted in the trail now and then`() {
        ports.listed += fp30
        keyboard.restore(usbChosen())
        executor.runDue()
        ports.play(fp30.key, "3C 3C 3C")
        executor.advance(250)
        assertTrue(logged.contains("Keyboard: 3 malformed bytes dropped"))
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `with no MIDI on the tablet the keyboard says so`() {
        ports.available = false
        keyboard.restore(usbChosen())
        executor.runDue()
        assertEquals(KeyboardState.Phase.Unavailable, state().phase)
    }

    @Test
    fun `a remembered keyboard reads back from the settings, and one this version can't read is none`() {
        assertEquals(KeyboardState.Chosen(fp30.key, "FP-30X", MidiTransport.USB), KeyboardState.Chosen.saved(fp30.key, "FP-30X"))
        assertEquals("USB keyboard", KeyboardState.Chosen.saved(fp30.key, "\u0000 ")?.name)
        assertNull(KeyboardState.Chosen.saved("midi2:x", "X"))
        assertNull(KeyboardState.Chosen.saved(null, "X"))
    }
}
