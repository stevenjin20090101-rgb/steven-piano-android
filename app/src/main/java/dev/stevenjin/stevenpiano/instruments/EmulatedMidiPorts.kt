// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.instruments

import android.os.Handler
import android.util.Log
import dev.stevenjin.stevenpiano.ble.LoggingPianoLink
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The emulator has no MIDI keyboard and no MIDI piano: in debug builds there (as [LoggingPianoLink]) these
 * stand in, beside whatever Android's MIDI service lists (v1.11 — M29). "Emulated keyboard" (USB, one
 * output port) plays the bytes [feed] is given, as a keyboard sends them: `adb shell am start -n
 * dev.stevenjin.stevenpiano/.MainActivity --es dev.stevenjin.stevenpiano.EMULATOR_MIDI "90 3C 64 80 3C 00"`
 * (malformed streams too: whatever hex arrives is fed as it is). [plug] unplugs it and plugs it in again,
 * as a USB cable would. "Emulated MIDI piano" (USB, one input port) logs every byte the app sends it
 * (`adb logcat -s MidiPiano`), so what reaches a standard MIDI piano can be read: its range, its pedal,
 * its stop sequence. Callbacks run on [handler]'s thread; a fed buffer reaches the keyboard's listeners on
 * the caller's (a port's own thread, as on a device).
 */
class EmulatedMidiPorts(private val handler: Handler) : MidiPorts {
    private val listeners = CopyOnWriteArrayList<MidiPorts.Listener>()
    private val receivers = CopyOnWriteArrayList<MidiBytes>()

    @Volatile
    private var keyboardPlugged = true

    override val available: Boolean = true

    override fun devices(): List<MidiDeviceRef> = listOfNotNull(KEYBOARD.takeIf { keyboardPlugged }, PIANO)

    override fun watch(listener: MidiPorts.Listener): Closeable {
        listeners += listener
        return Closeable { listeners -= listener }
    }

    override fun open(device: MidiDeviceRef, onOpened: (OpenMidiDevice?) -> Unit) {
        handler.post {
            onOpened(
                when (device.key) {
                    KEYBOARD.key -> if (keyboardPlugged) Opened(KEYBOARD) else null
                    PIANO.key -> Opened(PIANO)
                    else -> null
                },
            )
        }
    }

    /** No Bluetooth MIDI device on the emulator. */
    override fun openBluetooth(address: String, name: String, onOpened: (OpenMidiDevice?) -> Unit) {
        handler.post { onOpened(null) }
    }

    /** Plays [bytes] from the emulated keyboard, as one buffer. */
    fun feed(bytes: ByteArray) {
        val now = System.nanoTime()
        for (receiver in receivers) receiver.onBytes(bytes, 0, bytes.size, now)
    }

    /** The emulated keyboard's cable: [plugged] in or out, as Android's MIDI service reports a USB device. */
    fun plug(plugged: Boolean) {
        if (plugged == keyboardPlugged) return
        keyboardPlugged = plugged
        handler.post {
            for (listener in listeners) if (plugged) listener.added(KEYBOARD) else listener.removed(KEYBOARD)
        }
        if (!plugged) receivers.clear()
    }

    private inner class Opened(override val device: MidiDeviceRef) : OpenMidiDevice {
        override fun receive(port: Int, onBytes: MidiBytes): Closeable? {
            if (device !== KEYBOARD || port != 0) return null
            receivers += onBytes
            return Closeable { receivers -= onBytes }
        }

        override fun sender(port: Int): MidiOut? {
            if (device !== PIANO || port != 0) return null
            return object : MidiOut {
                override fun send(data: ByteArray, offset: Int, count: Int) {
                    Log.d(PIANO_TAG, (offset until offset + count).joinToString(" ") { "%02X".format(data[it].toInt() and 0xFF) })
                }

                override fun close() = Unit
            }
        }

        override fun close() = Unit
    }

    companion object {
        private const val PIANO_TAG = "MidiPiano"

        val KEYBOARD = MidiDeviceRef(MidiNames.usbKey("Steven Piano", "Emulated keyboard", "0001"), "Emulated keyboard", MidiTransport.USB, inputs = 0, outputs = 1)
        val PIANO = MidiDeviceRef(MidiNames.usbKey("Steven Piano", "Emulated MIDI piano", "0002"), "Emulated MIDI piano", MidiTransport.USB, inputs = 1, outputs = 0)

        /** Debug builds on an emulator, as [LoggingPianoLink]. */
        fun isWanted(): Boolean = LoggingPianoLink.isWanted()

        /** "90 3C 64", "903C64" or "90,3C,64": hex pairs, anything else between them ignored; at most [MAX_FED] bytes. */
        fun hex(text: String): ByteArray {
            val digits = text.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(MAX_FED * 2)
            return ByteArray(digits.length / 2) { ((Character.digit(digits[it * 2], 16) shl 4) or Character.digit(digits[it * 2 + 1], 16)).toByte() }
        }

        private const val MAX_FED = 4_096
    }
}

/** Two seams as one, the first's devices first: Android's MIDI service and the emulator's stand-ins (debug builds). */
class CombinedMidiPorts(private val first: MidiPorts, private val second: MidiPorts) : MidiPorts {
    override val available: Boolean get() = first.available || second.available

    override fun devices(): List<MidiDeviceRef> = first.devices() + second.devices()

    override fun watch(listener: MidiPorts.Listener): Closeable {
        val a = first.watch(listener)
        val b = second.watch(listener)
        return Closeable {
            a.close()
            b.close()
        }
    }

    override fun open(device: MidiDeviceRef, onOpened: (OpenMidiDevice?) -> Unit) =
        if (second.devices().any { it.key == device.key }) second.open(device, onOpened) else first.open(device, onOpened)

    override fun openBluetooth(address: String, name: String, onOpened: (OpenMidiDevice?) -> Unit) = first.openBluetooth(address, name, onOpened)
}
