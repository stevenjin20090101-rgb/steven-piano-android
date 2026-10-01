// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.instruments

import dev.stevenjin.stevenpiano.ble.FoundPiano
import dev.stevenjin.stevenpiano.ble.LinkError
import java.io.Closeable

/**
 * Android's MIDI service for tests (v1.11 — M29): the devices it lists ([listed]), [plug] and [unplug] as a
 * cable would, opens answered at once ([answerOpens]) or held for the test to answer ([pending]), and every
 * open device's ports: [play] sends bytes from a device's output ports, [sent] what the app wrote to its
 * input ports. Bluetooth opens succeed for [bluetoothHere] addresses.
 */
class FakeMidiPorts : MidiPorts {
    override var available: Boolean = true
    val listed = mutableListOf<MidiDeviceRef>()
    private var listener: MidiPorts.Listener? = null
    var answerOpens = true
    val pending = mutableListOf<Pair<MidiDeviceRef, (OpenMidiDevice?) -> Unit>>()
    val opened = mutableListOf<FakeDevice>()
    var opens = 0
        private set
    val bluetoothHere = mutableSetOf<String>()
    val bluetoothOpens = mutableListOf<String>()

    /** Input ports another app holds: [OpenMidiDevice.sender] answers null for them. */
    var inputBusy = false

    override fun devices(): List<MidiDeviceRef> = listed.toList()

    override fun watch(listener: MidiPorts.Listener): Closeable {
        this.listener = listener
        return Closeable { this.listener = null }
    }

    override fun open(device: MidiDeviceRef, onOpened: (OpenMidiDevice?) -> Unit) {
        opens++
        if (!answerOpens) {
            pending += device to onOpened
            return
        }
        onOpened(if (listed.any { it.key == device.key }) FakeDevice(device).also { opened += it } else null)
    }

    override fun openBluetooth(address: String, name: String, onOpened: (OpenMidiDevice?) -> Unit) {
        opens++
        bluetoothOpens += address
        val ref = MidiDeviceRef(MidiNames.bluetoothKey(address), name, MidiTransport.BLUETOOTH, inputs = 1, outputs = 1, address = address)
        if (!answerOpens) {
            pending += ref to onOpened
            return
        }
        onOpened(if (address.uppercase() in bluetoothHere) FakeDevice(ref).also { opened += it } else null)
    }

    fun plug(device: MidiDeviceRef) {
        listed += device
        listener?.added(device)
    }

    fun unplug(device: MidiDeviceRef) {
        listed.removeAll { it.key == device.key }
        opened.filter { it.device.key == device.key }.forEach { it.gone = true }
        listener?.removed(device)
    }

    /** The device open under [key] (the latest), or null. */
    fun device(key: String): FakeDevice? = opened.lastOrNull { it.device.key == key }

    /** Bytes from device [key]'s output [port], stamped [stamp]. */
    fun play(key: String, hex: String, port: Int = 0, stamp: Long = 0L) {
        val bytes = hex.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
        device(key)?.receivers?.get(port)?.onBytes(bytes, 0, bytes.size, stamp)
    }

    inner class FakeDevice(override val device: MidiDeviceRef) : OpenMidiDevice {
        val receivers = HashMap<Int, MidiBytes>()
        val sent = mutableListOf<String>()
        var closed = false
        var gone = false
        var outClosed = 0

        override fun receive(port: Int, onBytes: MidiBytes): Closeable? {
            if (port !in 0 until device.outputs) return null
            receivers[port] = onBytes
            return Closeable { receivers.remove(port) }
        }

        override fun sender(port: Int): MidiOut? {
            if (port !in 0 until device.inputs || inputBusy) return null
            return object : MidiOut {
                override fun send(data: ByteArray, offset: Int, count: Int) {
                    if (gone) throw java.io.IOException("gone")
                    synchronized(sent) { sent += (offset until offset + count).joinToString(" ") { "%02X".format(data[it].toInt() and 0xFF) } }
                }

                override fun close() {
                    outClosed++
                }
            }
        }

        override fun close() {
            closed = true
            receivers.clear()
        }
    }

    companion object {
        fun usb(name: String, serial: String = "1", inputs: Int = 0, outputs: Int = 1) =
            MidiDeviceRef(MidiNames.usbKey("Maker", name, serial), name, MidiTransport.USB, inputs, outputs)

        fun bluetooth(name: String, address: String, inputs: Int = 1, outputs: Int = 1) =
            MidiDeviceRef(MidiNames.bluetoothKey(address), name, MidiTransport.BLUETOOTH, inputs, outputs, address)
    }
}

/** Bluetooth for the MIDI side in tests: a scan the test drives, bonds it sets. */
class FakeMidiBluetooth : MidiBluetooth {
    var blocker: LinkError? = null
    var scanning = false
        private set
    var scans = 0
        private set
    val bonded = mutableSetOf<String>()
    val bondsAsked = mutableListOf<String>()
    private var onFound: ((FoundPiano) -> Unit)? = null
    private var onBonded: ((String) -> Unit)? = null

    override fun blocker(): LinkError? = blocker

    override fun startScan(onFound: (FoundPiano) -> Unit, onFailed: (errorCode: Int) -> Unit) {
        scanning = true
        scans++
        this.onFound = onFound
    }

    override fun stopScan() {
        scanning = false
    }

    override fun isBonded(address: String): Boolean = address.uppercase() in bonded

    override fun createBond(address: String): Boolean {
        bondsAsked += address
        return true
    }

    override fun watchBonds(onBonded: (address: String) -> Unit) {
        this.onBonded = onBonded
    }

    fun find(address: String, name: String?) = onFound?.invoke(FoundPiano(address, name, -60, true))

    /** The person accepted the pairing request. */
    fun bond(address: String) {
        bonded += address.uppercase()
        onBonded?.invoke(address)
    }
}
