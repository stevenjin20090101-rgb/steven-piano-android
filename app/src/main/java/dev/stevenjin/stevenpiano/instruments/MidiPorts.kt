// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.instruments

import java.io.Closeable
import java.io.IOException

/** How a MIDI device reaches the tablet. */
enum class MidiTransport(val label: String) {
    USB("USB"),
    BLUETOOTH("Bluetooth"),

    /** Another app's MIDI port: offered in debug builds only (the emulator's test device). */
    VIRTUAL("Virtual"),
}

/**
 * A MIDI device as the app knows it (v1.11 — M29). [key] is what is remembered of it, stable across
 * plugging in again (a USB device's id is not): `ble:<address>` for Bluetooth, `usb:<manufacturer>|
 * <product>|<serial>` for USB, `virtual:…` for another app's port. [name] is shown, cleaned
 * ([MidiNames.clean]); never a file's name, never a command. [inputs]: ports the app may send to (an
 * instrument); [outputs]: ports it may hear (a keyboard). [handle] is the platform's own description of
 * it ([android.media.midi.MidiDeviceInfo]), for opening.
 */
data class MidiDeviceRef(
    val key: String,
    val name: String,
    val transport: MidiTransport,
    val inputs: Int,
    val outputs: Int,
    val address: String? = null,
    val handle: Any? = null,
) {
    /** It can be played from: it has a port the app hears. */
    val canBeKeyboard: Boolean get() = outputs > 0

    /** It can play: it has a port the app sends to. */
    val canBeInstrument: Boolean get() = inputs > 0
}

/** Where a device's identity comes from, and its name made safe to show. */
object MidiNames {
    /** The longest name shown. */
    const val MAX_NAME = 40

    /** Bluetooth: its address, upper case. */
    fun bluetoothKey(address: String): String = "ble:" + address.trim().uppercase()

    /** USB: manufacturer, product and serial (Android's own id for it changes with each plug). */
    fun usbKey(manufacturer: String?, product: String?, serial: String?): String =
        "usb:" + listOf(manufacturer, product, serial).joinToString("|") { clean(it, MAX_KEY_PART) }

    /** Another app's port, by what it says it is. */
    fun virtualKey(manufacturer: String?, product: String?, name: String?): String =
        "virtual:" + listOf(manufacturer, product, name).joinToString("|") { clean(it, MAX_KEY_PART) }

    /** The Bluetooth address in a [bluetoothKey], or null. */
    fun addressOf(key: String?): String? = key?.takeIf { it.startsWith("ble:") }?.removePrefix("ble:")?.takeIf { it.isNotEmpty() }

    /** The transport a remembered [key] names, or null. */
    fun transportOf(key: String?): MidiTransport? = when {
        key == null -> null
        key.startsWith("ble:") -> MidiTransport.BLUETOOTH
        key.startsWith("usb:") -> MidiTransport.USB
        key.startsWith("virtual:") -> MidiTransport.VIRTUAL
        else -> null
    }

    /**
     * [raw] as it may be shown: control and format characters gone, spaces collapsed, cut to [max]
     * characters (a device chooses its own name, and a long or hostile one must not take over a row).
     */
    fun clean(raw: String?, max: Int = MAX_NAME): String {
        if (raw == null) return ""
        val out = StringBuilder()
        var space = false
        for (ch in raw) {
            when {
                ch.isWhitespace() || Character.getType(ch) == Character.CONTROL.toInt() || Character.getType(ch) == Character.FORMAT.toInt() -> space = out.isNotEmpty()
                else -> {
                    if (space) out.append(' ')
                    space = false
                    out.append(ch)
                }
            }
            if (out.length >= max) break
        }
        return out.toString().take(max).trim()
    }

    private const val MAX_KEY_PART = 64
}

/**
 * The seam over Android's MIDI service (`android.media.midi`), as [dev.stevenjin.stevenpiano.ble.BleRadio]
 * is over GATT, so the keyboard's and the instrument's state machines test on the JVM. Steven Piano never
 * goes through here: it keeps its own Bluetooth link. [AndroidMidiPorts] is the real one,
 * [EmulatedMidiPorts] the emulator's (debug builds). Callbacks other than [OpenMidiDevice.receive]'s come
 * on the thread the implementation was given; [OpenMidiDevice.receive]'s on the port's own.
 */
interface MidiPorts {
    /** Android's MIDI service is here (the tablet has the MIDI feature). */
    val available: Boolean

    /** The devices the service knows now: USB, Bluetooth devices already open, and (debug builds) test ports. */
    fun devices(): List<MidiDeviceRef>

    /** Calls [listener] as devices come and go, until the returned handle is closed. */
    fun watch(listener: Listener): Closeable

    /** Opens [device] (one of [devices]); [onOpened] gets it, or null when it could not be opened. */
    fun open(device: MidiDeviceRef, onOpened: (OpenMidiDevice?) -> Unit)

    /**
     * Opens the Bluetooth MIDI device at [address] through Android's own Bluetooth MIDI service, which may
     * ask the person to pair; [onOpened] gets it, or null. [name] is what the scan saw it called.
     */
    fun openBluetooth(address: String, name: String, onOpened: (OpenMidiDevice?) -> Unit)

    /** Devices coming and going. */
    interface Listener {
        fun added(device: MidiDeviceRef)

        fun removed(device: MidiDeviceRef)
    }
}

/** An open MIDI device. Close it to let it go; its ports close with it. */
interface OpenMidiDevice : Closeable {
    val device: MidiDeviceRef

    /**
     * Hears the device's output port [port]: [onBytes] runs on that port's own thread with each buffer
     * (the bytes are the caller's only during the call). Null when the port can't be opened. Close the
     * returned handle to stop hearing it.
     */
    fun receive(port: Int, onBytes: MidiBytes): Closeable?

    /** Sends to the device's input port [port]; null when it can't be opened (another app holds it). */
    fun sender(port: Int): MidiOut?
}

/** Bytes from a device: [data] from [offset], [count] of them, stamped [timestampNanos] (`System.nanoTime`'s clock) by the sender. */
fun interface MidiBytes {
    fun onBytes(data: ByteArray, offset: Int, count: Int, timestampNanos: Long)
}

/** A device's input port, as the app sends to it. [send] may block; it throws [IOException] once the device is gone. */
interface MidiOut : Closeable {
    @Throws(IOException::class)
    fun send(data: ByteArray, offset: Int, count: Int)
}
