// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.ble.LinkError
import dev.stevenjin.stevenpiano.instruments.KeyboardState
import dev.stevenjin.stevenpiano.instruments.MidiNames
import dev.stevenjin.stevenpiano.instruments.MidiTransport
import dev.stevenjin.stevenpiano.record.TakeEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What keyboards and instruments say (v1.11 — M29). */
class InstrumentCopyTest {
    private val roland = KeyboardState.Chosen(MidiNames.bluetoothKey("11:22:33:44:55:66"), "Roland FP-30X", MidiTransport.BLUETOOTH)
    private val usb = KeyboardState.Chosen(MidiNames.usbKey("Maker", "Keystation", "1"), "Keystation", MidiTransport.USB)

    @Test
    fun `the Keyboard row reads None, the name, or the name not connected`() {
        assertEquals("None", InstrumentCopy.keyboardValue(KeyboardState()))
        assertEquals("Roland FP-30X", InstrumentCopy.keyboardValue(KeyboardState(roland, KeyboardState.Phase.Connected)))
        assertEquals("Roland FP-30X · not connected", InstrumentCopy.keyboardValue(KeyboardState(roland, KeyboardState.Phase.NotConnected)))
        assertEquals("Roland FP-30X · not connected", InstrumentCopy.keyboardValue(KeyboardState(roland, KeyboardState.Phase.Connecting)))
    }

    @Test
    fun `the Keyboard page's state line says what to do`() {
        assertEquals("No keyboard yet. Choose one to play from it.", InstrumentCopy.keyboardLine(KeyboardState()))
        assertEquals("Roland FP-30X is connected.", InstrumentCopy.keyboardLine(KeyboardState(roland, KeyboardState.Phase.Connected)))
        assertEquals("Connecting to Roland FP-30X…", InstrumentCopy.keyboardLine(KeyboardState(roland, KeyboardState.Phase.Connecting)))
        assertEquals("Roland FP-30X isn't connected. Switch it on near the tablet.", InstrumentCopy.keyboardLine(KeyboardState(roland, KeyboardState.Phase.NotConnected)))
        assertEquals("Keystation isn't connected. Plug it into the tablet.", InstrumentCopy.keyboardLine(KeyboardState(usb, KeyboardState.Phase.NotConnected)))
        assertEquals("Roland FP-30X asks to pair.", InstrumentCopy.keyboardLine(KeyboardState(roland, KeyboardState.Phase.NeedsPairing)))
        assertEquals("This tablet has no MIDI, so it can't use a keyboard.", InstrumentCopy.keyboardLine(KeyboardState(usb, KeyboardState.Phase.Unavailable)))
        assertEquals("Bluetooth · Connected", InstrumentCopy.keyboardDetail(KeyboardState(roland, KeyboardState.Phase.Connected)))
        assertEquals("USB · Not connected", InstrumentCopy.keyboardDetail(KeyboardState(usb, KeyboardState.Phase.NotConnected)))
    }

    @Test
    fun `the Keys tab's eyebrow names the keyboard, with its state when not connected`() {
        assertNull(InstrumentCopy.keysEyebrow(KeyboardState()))
        assertEquals("Keyboard · Roland FP-30X", InstrumentCopy.keysEyebrow(KeyboardState(roland, KeyboardState.Phase.Connected)))
        assertEquals("Keyboard · Keystation · Not connected", InstrumentCopy.keysEyebrow(KeyboardState(usb, KeyboardState.Phase.NotConnected)))
        assertEquals("Keyboard · Roland FP-30X · Asks to pair", InstrumentCopy.keysEyebrow(KeyboardState(roland, KeyboardState.Phase.NeedsPairing)))
    }

    @Test
    fun `the picker's lines`() {
        assertEquals("Bluetooth needs a moment between searches. Looking again in 30 s.", InstrumentCopy.waitLine(29_001))
        assertEquals("Bluetooth needs a moment between searches. Looking again in 1 s.", InstrumentCopy.waitLine(0))
        assertEquals("Allow Nearby devices to find Bluetooth MIDI devices.", InstrumentCopy.blocked(LinkError.PermissionMissing))
        assertEquals("Bluetooth is off. Turn it on to find Bluetooth devices; USB ones are listed here.", InstrumentCopy.blocked(LinkError.BluetoothOff))
        assertEquals("Roland FP-30X, Bluetooth, chosen", InstrumentCopy.pickerRow("Roland FP-30X", MidiTransport.BLUETOOTH, current = true))
        assertEquals("Keystation, USB", InstrumentCopy.pickerRow("Keystation", MidiTransport.USB, current = false))
    }

    @Test
    fun `a device's name is cleaned before it is shown`() {
        assertEquals("Roland FP-30X", MidiNames.clean("  Roland\tFP-30X\u0000\n"))
        assertEquals(40, MidiNames.clean("x".repeat(500)).length)
        assertEquals("", MidiNames.clean(null))
        assertEquals("ble:11:22:33:44:55:66", MidiNames.bluetoothKey(" 11:22:33:44:55:66"))
        assertEquals("11:22:33:44:55:66", MidiNames.addressOf("ble:11:22:33:44:55:66"))
        assertNull(MidiNames.addressOf("usb:a|b|c"))
        assertEquals("usb:Roland|FP-30X|", MidiNames.usbKey("Roland", "FP-30X", null))
        assertEquals(MidiTransport.USB, MidiNames.transportOf("usb:a|b|c"))
        assertNull(MidiNames.transportOf("x:y"))
    }

    @Test
    fun `a take's line and why it stopped by itself`() {
        assertEquals("0:42 · 318 notes", InstrumentCopy.takeLine(42_400_000, 318))
        assertEquals("0:00 · 1 note", InstrumentCopy.takeLine(300_000, 1))
        assertEquals("1:00:00 · 12,345 notes", InstrumentCopy.takeLine(3_600_000_000, 12_345))
        assertNull(InstrumentCopy.takeEnded(TakeEnd.Stopped))
        assertEquals("It stopped by itself after an hour.", InstrumentCopy.takeEnded(TakeEnd.Longest))
        assertEquals("It stopped by itself after five minutes with nothing played.", InstrumentCopy.takeEnded(TakeEnd.Silence))
    }
}
