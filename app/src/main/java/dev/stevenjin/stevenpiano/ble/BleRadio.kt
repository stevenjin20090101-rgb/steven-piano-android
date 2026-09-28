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

    /**
     * Whether Location Services are on. Android 11 and older need them for any scan ([blocker] says
     * so); some devices on Android 12 and newer still find nothing without them, which is why a
     * search that found nothing mentions them.
     */
    fun locationServicesOn(): Boolean

    /**
     * Whether this device is paired (bonded) with [address] in its Bluetooth settings. The piano
     * refuses encryption, so a bond left from pairing makes every connection to it fail. False when
     * it cannot be told (no permission, no adapter).
     */
    fun isBonded(address: String): Boolean

    /**
     * Devices some app on this device is connected to right now (GATT client or server), with their
     * names as Android knows them. The piano stops advertising while connected, so a piano another
     * app holds is found here, never by a scan. Empty when it cannot be told.
     */
    fun connectedDevices(): List<FoundPiano>
}

/**
 * A device the link may connect to: a scan result, with its [rssi] and whether its advertisement
 * carried the BLE-MIDI service ([advertisesMidi]), or a device already connected ([rssi] null).
 * [name] comes from the scan response and may be missing (the piano puts its name only there).
 */
class FoundPiano(val address: String, val name: String?, val rssi: Int? = null, val advertisesMidi: Boolean = false)

/** One GATT client connection. Each call is one GATT operation; the link keeps one in flight. */
interface GattConnection {
    /** The piano's Bluetooth address, for the log. */
    val address: String

    fun requestMtu(mtu: Int): Boolean

    fun discoverServices(): Boolean

    /** After discovery: true when the BLE-MIDI characteristic is there. */
    fun hasMidiCharacteristic(): Boolean

    /** After discovery: true when the piano's console (Nordic UART: RX, and TX with its CCCD) is there too. */
    fun hasConsole(): Boolean

    /**
     * After discovery: reads the GAP Device Name (service 0x1800, characteristic 0x2A00), answered by
     * [GattEvents.onDeviceName]. False when the device has none, or the read could not start.
     */
    fun readDeviceName(): Boolean

    fun requestHighPriority(): Boolean

    /** A MIDI packet, written without response. */
    fun write(packet: ByteArray): WriteResult

    /** Switches on the console's TX notifications: the CCCD write, answered by [GattEvents.onConsoleSubscribed]. */
    fun subscribeConsole(): WriteResult

    /** A piece of a console line to RX, written without response. */
    fun writeConsole(chunk: ByteArray): WriteResult

    /**
     * After discovery: whether Device Information's Firmware Revision String (0x180A/0x2A26) is
     * there (firmware 2.0.0 and later, BLE_OTA.md › 2).
     */
    fun hasFirmwareVersion(): Boolean = false

    /** Reads the Firmware Revision String, answered by [GattEvents.onFirmwareVersion]; false when the read could not start. */
    fun readFirmwareVersion(): Boolean = false

    /** After discovery: whether the update service (Control with its CCCD, and Data) is there (BLE_OTA.md › 3). */
    fun hasOta(): Boolean = false

    /** Switches on Control's notifications: the CCCD write, answered by [GattEvents.onOtaSubscribed]. */
    fun subscribeOta(): WriteResult = WriteResult.Failed

    /** A frame to Control, written with response: answered by [GattEvents.onWriteDone]. */
    fun writeOtaControl(frame: ByteArray): WriteResult = WriteResult.Failed

    /** A frame to Data, written without response. */
    fun writeOtaData(frame: ByteArray): WriteResult = WriteResult.Failed

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

    /** The GAP Device Name read is done: [name] as UTF-8 when [status] is 0 (GATT_SUCCESS), else null. */
    fun onDeviceName(connection: GattConnection, name: String?, status: Int)

    /** The Firmware Revision String read is done: its [value] when [status] is 0, else null. */
    fun onFirmwareVersion(connection: GattConnection, value: ByteArray?, status: Int)

    /** Control's CCCD write is done. */
    fun onOtaSubscribed(connection: GattConnection, success: Boolean)

    /** A notification from Control; [data] is the caller's own copy. */
    fun onOtaNotified(connection: GattConnection, data: ByteArray)
}

/** The link's own thread: runs actions in order, now or after a delay. */
interface LinkExecutor {
    fun execute(action: Runnable)

    /** Runs [action] after [delayMs]; scheduling it again first cancels the earlier run. */
    fun schedule(delayMs: Long, action: Runnable)

    fun cancel(action: Runnable)

    fun nanoTime(): Long
}
