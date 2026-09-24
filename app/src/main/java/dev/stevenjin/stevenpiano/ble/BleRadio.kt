// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

/**
 * The seam between [GattPianoLink]'s state machine and Android Bluetooth, so the machine can
 * be tested without a radio. [AndroidBleRadio] is the real thing. Callbacks may arrive on
 * any thread; the link hands them to its own thread.
 */
interface BleRadio {
    /** Why a connection cannot start right now (off, no permission, ...), or null. */
    fun blocker(): LinkError?

    /** A filtered, low-latency scan for BLE-MIDI devices. */
    fun startScan(onFound: (FoundPiano) -> Unit, onFailed: (errorCode: Int) -> Unit)

    fun stopScan()

    /** `connectGatt(context, autoConnect, callback, TRANSPORT_LE)`; null when that fails outright. */
    fun connect(address: String, autoConnect: Boolean, events: GattEvents): GattConnection?

    /** Calls [onChange] as the Bluetooth adapter turns on (true) or off (false). */
    fun watchAdapter(onChange: (on: Boolean) -> Unit)
}

/** A scan result: [name] comes from the scan response and may be missing. */
class FoundPiano(val address: String, val name: String?)

/** One GATT client connection. Each call is one GATT operation; the link keeps one in flight. */
interface GattConnection {
    fun requestMtu(mtu: Int): Boolean

    fun discoverServices(): Boolean

    /** After discovery: true when the BLE-MIDI characteristic is there. */
    fun hasMidiCharacteristic(): Boolean

    /** After discovery: true when the piano's console (Nordic UART: RX, and TX with its CCCD) is there too. */
    fun hasConsole(): Boolean

    fun requestHighPriority(): Boolean

    /** A MIDI packet, written without response. */
    fun write(packet: ByteArray): WriteResult

    /** Switches on the console's TX notifications: the CCCD write, answered by [GattEvents.onConsoleSubscribed]. */
    fun subscribeConsole(): WriteResult

    /** A piece of a console line to RX, written without response. */
    fun writeConsole(chunk: ByteArray): WriteResult

    fun disconnect()

    fun close()
}

enum class WriteResult { Sent, Busy, Failed }

/** BluetoothGattCallback, reduced to what the link uses. */
interface GattEvents {
    fun onConnectionChanged(connection: GattConnection, connected: Boolean, status: Int)

    fun onMtuChanged(connection: GattConnection, mtu: Int, success: Boolean)

    fun onServicesDiscovered(connection: GattConnection, success: Boolean)

    /** A characteristic write (MIDI or console) is done: the next operation may go. */
    fun onWriteDone(connection: GattConnection, success: Boolean)

    /** The console's CCCD write is done. */
    fun onConsoleSubscribed(connection: GattConnection, success: Boolean)

    /** A notification from the console's TX; [data] is the caller's own copy. */
    fun onConsoleData(connection: GattConnection, data: ByteArray)
}

/** The link's own thread: runs actions in order, now or after a delay. */
interface LinkExecutor {
    fun execute(action: Runnable)

    /** Runs [action] after [delayMs]; scheduling it again first cancels the earlier run. */
    fun schedule(delayMs: Long, action: Runnable)

    fun cancel(action: Runnable)

    fun nanoTime(): Long
}
