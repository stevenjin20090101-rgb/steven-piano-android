// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * [BleRadio] on Android Bluetooth. [blocker] checks permissions before anything else runs;
 * a permission revoked later surfaces as SecurityException, which [GattPianoLink] handles.
 */
@SuppressLint("MissingPermission")
class AndroidBleRadio(private val context: Context) : BleRadio {
    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val scanner = adapter?.let(::PianoScanner)

    override fun blocker(): LinkError? {
        val adapter = adapter
        return when {
            adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> LinkError.Unsupported
            BlePermissions.missing(context).isNotEmpty() -> LinkError.PermissionMissing
            !adapter.isEnabled -> LinkError.BluetoothOff
            BlePermissions.needsLocationServices(Build.VERSION.SDK_INT) && !locationServicesOn() -> LinkError.LocationOff
            else -> null
        }
    }

    override fun startScan(onFound: (FoundPiano) -> Unit, onFailed: (errorCode: Int) -> Unit) {
        scanner?.start(onFound, onFailed) ?: onFailed(PianoScanner.NO_ADAPTER)
    }

    override fun stopScan() {
        scanner?.stop()
    }

    override fun connect(address: String, autoConnect: Boolean, events: GattEvents): GattConnection? {
        val adapter = adapter ?: return null
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return null
        val connection = AndroidGattConnection(address)
        val callback = Callback(connection, events)
        connection.gatt = adapter.getRemoteDevice(address)
            .connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE) ?: return null
        return connection
    }

    override fun watchAdapter(onChange: (on: Boolean) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> onChange(true)
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onChange(false)
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun locationServicesOn(): Boolean =
        context.getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled) ?: false

    override fun isBonded(address: String): Boolean {
        val adapter = adapter ?: return false
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return false
        return try {
            adapter.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED
        } catch (e: SecurityException) {   // BLUETOOTH_CONNECT revoked: say "not paired", and let connecting report it
            false
        }
    }

    override fun connectedDevices(): List<FoundPiano> {
        val manager = manager ?: return emptyList()
        return try {
            (manager.getConnectedDevices(BluetoothProfile.GATT) + manager.getConnectedDevices(BluetoothProfile.GATT_SERVER))
                .distinctBy { it.address }
                .map { FoundPiano(it.address, it.name) }
        } catch (e: SecurityException) {   // BLUETOOTH_CONNECT revoked: nothing known, and the scan goes ahead
            emptyList()
        }
    }

    /** Forwards callbacks, tagged with their connection so stale ones can be told apart. */
    private class Callback(private val connection: GattConnection, private val events: GattEvents) : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = events.onConnectionChanged(
            connection,
            connected = status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED,
            status = status,
        )

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) =
            events.onMtuChanged(connection, mtu, status == BluetoothGatt.GATT_SUCCESS)

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
            events.onServicesDiscovered(connection, status == BluetoothGatt.GATT_SUCCESS)

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            events.onWriteDone(connection, status == BluetoothGatt.GATT_SUCCESS)

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            if (descriptor.characteristic?.uuid == PianoOta.CONTROL_UUID) {
                events.onOtaSubscribed(connection, success)
            } else {
                events.onConsoleSubscribed(connection, success)
            }
        }

        // API 33+: the value arrives with the callback. Not calling super keeps the old callback below silent.
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            when (characteristic.uuid) {
                PianoBluetooth.GAP_DEVICE_NAME_UUID -> events.onDeviceName(connection, deviceName(value, status), status)
                PianoOta.FIRMWARE_REVISION_UUID -> events.onFirmwareVersion(connection, if (status == BluetoothGatt.GATT_SUCCESS) value.copyOf() else null, status)
            }
        }

        // API 26-32: the value sits in the characteristic.
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            when (characteristic.uuid) {
                PianoBluetooth.GAP_DEVICE_NAME_UUID -> events.onDeviceName(connection, deviceName(characteristic.value, status), status)
                PianoOta.FIRMWARE_REVISION_UUID ->
                    events.onFirmwareVersion(connection, if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value?.copyOf() else null, status)
            }
        }

        /** The GAP Device Name is UTF-8, sometimes padded with NULs. */
        private fun deviceName(value: ByteArray?, status: Int): String? =
            if (status == BluetoothGatt.GATT_SUCCESS && value != null) String(value, Charsets.UTF_8).trimEnd('\u0000') else null

        // API 33+: the value arrives with the callback. Not calling super keeps the old callback below silent.
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            when (characteristic.uuid) {
                PianoConsole.TX_UUID -> events.onConsoleData(connection, value.copyOf())
                PianoOta.CONTROL_UUID -> events.onOtaNotified(connection, value.copyOf())
            }
        }

        // API 26-32: the value sits in the characteristic until the next notification overwrites it, so copy it now.
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            when (characteristic.uuid) {
                PianoConsole.TX_UUID -> events.onConsoleData(connection, value.copyOf())
                PianoOta.CONTROL_UUID -> events.onOtaNotified(connection, value.copyOf())
            }
        }
    }
}

@SuppressLint("MissingPermission")
private class AndroidGattConnection(override val address: String) : GattConnection {
    lateinit var gatt: BluetoothGatt
    private var midi: BluetoothGattCharacteristic? = null
    private var consoleRx: BluetoothGattCharacteristic? = null
    private var consoleCccd: BluetoothGattDescriptor? = null
    private var firmwareRevision: BluetoothGattCharacteristic? = null
    private var otaControl: BluetoothGattCharacteristic? = null
    private var otaCccd: BluetoothGattDescriptor? = null
    private var otaData: BluetoothGattCharacteristic? = null

    override fun requestMtu(mtu: Int): Boolean = gatt.requestMtu(mtu)

    override fun discoverServices(): Boolean = gatt.discoverServices()

    override fun hasMidiCharacteristic(): Boolean {
        midi = gatt.getService(PianoBluetooth.SERVICE_UUID)?.getCharacteristic(PianoBluetooth.CHARACTERISTIC_UUID)
        return midi != null
    }

    override fun hasConsole(): Boolean {
        val service = gatt.getService(PianoConsole.SERVICE_UUID)
        consoleRx = service?.getCharacteristic(PianoConsole.RX_UUID)
        consoleCccd = service?.getCharacteristic(PianoConsole.TX_UUID)?.getDescriptor(PianoConsole.CCCD_UUID)
        return consoleRx != null && consoleCccd != null
    }

    override fun readDeviceName(): Boolean {
        val name = gatt.getService(PianoBluetooth.GAP_SERVICE_UUID)?.getCharacteristic(PianoBluetooth.GAP_DEVICE_NAME_UUID) ?: return false
        return gatt.readCharacteristic(name)
    }

    override fun requestHighPriority(): Boolean = gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

    override fun write(packet: ByteArray): WriteResult = writeNoResponse(midi, packet)

    override fun writeConsole(chunk: ByteArray): WriteResult = writeNoResponse(consoleRx, chunk)

    override fun subscribeConsole(): WriteResult = subscribe(consoleCccd)

    override fun hasFirmwareVersion(): Boolean {
        firmwareRevision = gatt.getService(PianoOta.DEVICE_INFORMATION_UUID)?.getCharacteristic(PianoOta.FIRMWARE_REVISION_UUID)
        return firmwareRevision != null
    }

    override fun readFirmwareVersion(): Boolean = firmwareRevision?.let { gatt.readCharacteristic(it) } ?: false

    override fun hasOta(): Boolean {
        val service = gatt.getService(PianoOta.SERVICE_UUID)
        otaControl = service?.getCharacteristic(PianoOta.CONTROL_UUID)
        otaCccd = otaControl?.getDescriptor(PianoConsole.CCCD_UUID)
        otaData = service?.getCharacteristic(PianoOta.DATA_UUID)
        return otaControl != null && otaCccd != null && otaData != null
    }

    override fun subscribeOta(): WriteResult = subscribe(otaCccd)

    /** BEGIN, END and ABORT go with response: the piano's answer comes as a notification after it. Long frames are Android's long writes. */
    override fun writeOtaControl(frame: ByteArray): WriteResult = write(otaControl, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)

    override fun writeOtaData(frame: ByteArray): WriteResult = writeNoResponse(otaData, frame)

    /** Switches on [cccd]'s characteristic's notifications: the local flag, then the CCCD write. */
    private fun subscribe(cccd: BluetoothGattDescriptor?): WriteResult {
        if (cccd == null) return WriteResult.Failed
        if (!gatt.setCharacteristicNotification(cccd.characteristic, true)) return WriteResult.Failed
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return statusToResult(gatt.writeDescriptor(cccd, enable))
        }
        @Suppress("DEPRECATION")
        cccd.value = enable
        @Suppress("DEPRECATION")
        return if (gatt.writeDescriptor(cccd)) WriteResult.Sent else WriteResult.Busy
    }

    private fun writeNoResponse(characteristic: BluetoothGattCharacteristic?, bytes: ByteArray): WriteResult =
        write(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)

    private fun write(characteristic: BluetoothGattCharacteristic?, bytes: ByteArray, type: Int): WriteResult {
        if (characteristic == null) return WriteResult.Failed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return statusToResult(gatt.writeCharacteristic(characteristic, bytes, type))
        }
        characteristic.writeType = type
        @Suppress("DEPRECATION")
        characteristic.value = bytes
        @Suppress("DEPRECATION")
        return if (gatt.writeCharacteristic(characteristic)) WriteResult.Sent else WriteResult.Busy
    }

    private fun statusToResult(status: Int): WriteResult = when (status) {
        BluetoothStatusCodes.SUCCESS -> WriteResult.Sent
        BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> WriteResult.Busy
        else -> WriteResult.Failed
    }

    override fun disconnect() = gatt.disconnect()

    override fun close() = gatt.close()
}

/** [LinkExecutor] on a Looper thread. */
class HandlerLinkExecutor(looper: Looper) : LinkExecutor {
    private val handler = Handler(looper)

    override fun execute(action: Runnable) {
        handler.post(action)
    }

    override fun schedule(delayMs: Long, action: Runnable) {
        handler.removeCallbacks(action)
        handler.postDelayed(action, delayMs)
    }

    override fun cancel(action: Runnable) = handler.removeCallbacks(action)

    override fun nanoTime(): Long = SystemClock.elapsedRealtimeNanos()
}
