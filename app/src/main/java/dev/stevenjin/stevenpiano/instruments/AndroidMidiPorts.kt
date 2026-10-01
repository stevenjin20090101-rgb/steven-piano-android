// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.instruments

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import dev.stevenjin.stevenpiano.ble.BlePermissions
import dev.stevenjin.stevenpiano.ble.FoundPiano
import dev.stevenjin.stevenpiano.ble.LinkError
import dev.stevenjin.stevenpiano.ble.PianoScanner
import java.io.Closeable
import java.util.concurrent.Executor

/**
 * [MidiPorts] on Android's MIDI service (v1.11 — M29). Its callbacks run on [handler]'s thread
 * ("steven-piano-midi"). Only byte-stream devices are listed (on Android 13 and newer a MIDI 2.0 device's
 * Universal MIDI Packet twin is left out); another app's virtual ports only when [allowVirtual] (debug
 * builds: the emulator's test device). A call that throws (a revoked permission, a service gone) never
 * crashes the app: it reads as nothing found, or a device that could not be opened.
 */
class AndroidMidiPorts(
    private val context: Context,
    private val handler: Handler,
    private val allowVirtual: Boolean,
) : MidiPorts {
    private val manager: MidiManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) context.getSystemService(MidiManager::class.java) else null

    override val available: Boolean get() = manager != null

    override fun devices(): List<MidiDeviceRef> {
        val manager = manager ?: return emptyList()
        val infos = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
            } else {
                @Suppress("DEPRECATION")
                manager.devices.toList()
            }
        } catch (e: RuntimeException) {
            return emptyList()
        }
        return infos.mapNotNull(::refOf)
    }

    override fun watch(listener: MidiPorts.Listener): Closeable {
        val manager = manager ?: return Closeable { }
        val callback = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) {
                refOf(device)?.let(listener::added)
            }

            override fun onDeviceRemoved(device: MidiDeviceInfo) {
                refOf(device)?.let(listener::removed)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, Executor { handler.post(it) }, callback)
            } else {
                @Suppress("DEPRECATION")
                manager.registerDeviceCallback(callback, handler)
            }
        } catch (e: RuntimeException) {
            return Closeable { }
        }
        return Closeable { runCatching { manager.unregisterDeviceCallback(callback) } }
    }

    override fun open(device: MidiDeviceRef, onOpened: (OpenMidiDevice?) -> Unit) {
        val manager = manager
        val info = device.handle as? MidiDeviceInfo
        if (manager == null || info == null) return onOpened(null)
        try {
            manager.openDevice(info, { opened -> onOpened(opened?.let { AndroidOpenDevice(it, refOf(it.info) ?: device) }) }, handler)
        } catch (e: RuntimeException) {
            onOpened(null)
        }
    }

    @SuppressLint("MissingPermission")
    override fun openBluetooth(address: String, name: String, onOpened: (OpenMidiDevice?) -> Unit) {
        val manager = manager
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (manager == null || adapter == null || !BluetoothAdapter.checkBluetoothAddress(address)) return onOpened(null)
        try {
            val remote = adapter.getRemoteDevice(address)
            manager.openBluetoothDevice(remote, { opened ->
                onOpened(
                    opened?.let {
                        val fallback = MidiDeviceRef(MidiNames.bluetoothKey(address), MidiNames.clean(name), MidiTransport.BLUETOOTH, 1, 1, address)
                        AndroidOpenDevice(it, refOf(it.info) ?: fallback)
                    },
                )
            }, handler)
        } catch (e: RuntimeException) {   // SecurityException for a revoked permission, IllegalStateException while Bluetooth turns off
            onOpened(null)
        }
    }

    /** What the app keeps of [info], or null for a device it never offers (another app's port in a release build). */
    private fun refOf(info: MidiDeviceInfo): MidiDeviceRef? {
        val properties: Bundle = info.properties
        val manufacturer = properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)
        val product = properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
        val label = properties.getString(MidiDeviceInfo.PROPERTY_NAME)
        val name = MidiNames.clean(label?.takeIf { it.isNotBlank() } ?: product?.takeIf { it.isNotBlank() } ?: manufacturer)
        return when (info.type) {
            MidiDeviceInfo.TYPE_USB -> MidiDeviceRef(
                MidiNames.usbKey(manufacturer, product, properties.getString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER)),
                name.ifEmpty { USB_DEVICE },
                MidiTransport.USB,
                info.inputPortCount,
                info.outputPortCount,
                handle = info,
            )
            MidiDeviceInfo.TYPE_BLUETOOTH -> {
                val remote = BundleCompat.getParcelable(properties, MidiDeviceInfo.PROPERTY_BLUETOOTH_DEVICE, BluetoothDevice::class.java)
                val address = remote?.address ?: return null
                MidiDeviceRef(MidiNames.bluetoothKey(address), name, MidiTransport.BLUETOOTH, info.inputPortCount, info.outputPortCount, address, info)
            }
            MidiDeviceInfo.TYPE_VIRTUAL -> if (!allowVirtual) {
                null
            } else {
                MidiDeviceRef(MidiNames.virtualKey(manufacturer, product, label), name, MidiTransport.VIRTUAL, info.inputPortCount, info.outputPortCount, handle = info)
            }
            else -> null
        }
    }

    /** An open device: its output ports heard through [MidiReceiver]s, its input ports written to. */
    private class AndroidOpenDevice(private val midi: MidiDevice, override val device: MidiDeviceRef) : OpenMidiDevice {
        override fun receive(port: Int, onBytes: MidiBytes): Closeable? {
            val output: MidiOutputPort = runCatching { midi.openOutputPort(port) }.getOrNull() ?: return null
            val receiver = object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) = onBytes.onBytes(msg, offset, count, timestamp)
            }
            output.connect(receiver)
            return Closeable {
                runCatching { output.disconnect(receiver) }
                runCatching { output.close() }
            }
        }

        override fun sender(port: Int): MidiOut? {
            val input: MidiInputPort = runCatching { midi.openInputPort(port) }.getOrNull() ?: return null
            return object : MidiOut {
                override fun send(data: ByteArray, offset: Int, count: Int) = input.send(data, offset, count)

                override fun close() {
                    runCatching { input.close() }
                }
            }
        }

        override fun close() {
            runCatching { midi.close() }
        }
    }

    private companion object {
        /** A USB device that names itself nothing. */
        const val USB_DEVICE = "USB MIDI device"
    }
}

/**
 * The Bluetooth side of finding a MIDI device (v1.11 — M29): the picker's own scan for BLE-MIDI devices
 * (its own [PianoScanner], so it never disturbs the piano link's), and pairing, which some keyboards ask for.
 */
interface MidiBluetooth {
    /** Why a scan can't start now (Bluetooth off, a permission missing), or null. */
    fun blocker(): LinkError?

    fun startScan(onFound: (FoundPiano) -> Unit, onFailed: (errorCode: Int) -> Unit)

    fun stopScan()

    /** Whether this tablet is paired with [address]. */
    fun isBonded(address: String): Boolean

    /** Asks Android to pair with [address] (it shows the person the request); false when it could not ask. */
    fun createBond(address: String): Boolean

    /** Calls [onBonded] whenever a device finishes pairing. */
    fun watchBonds(onBonded: (address: String) -> Unit)
}

/** [MidiBluetooth] on Android Bluetooth; permissions are checked by [blocker] before every scan. */
@SuppressLint("MissingPermission")
class AndroidMidiBluetooth(private val context: Context) : MidiBluetooth {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val scanner = adapter?.let(::PianoScanner)

    override fun blocker(): LinkError? {
        val adapter = adapter
        return when {
            adapter == null || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) -> LinkError.Unsupported
            BlePermissions.missing(context).isNotEmpty() -> LinkError.PermissionMissing
            !adapter.isEnabled -> LinkError.BluetoothOff
            else -> null
        }
    }

    override fun startScan(onFound: (FoundPiano) -> Unit, onFailed: (errorCode: Int) -> Unit) {
        scanner?.start(onFound, onFailed) ?: onFailed(PianoScanner.NO_ADAPTER)
    }

    override fun stopScan() {
        scanner?.stop()
    }

    override fun isBonded(address: String): Boolean {
        val adapter = adapter ?: return false
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return false
        return try {
            adapter.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED
        } catch (e: SecurityException) {
            false
        }
    }

    override fun createBond(address: String): Boolean {
        val adapter = adapter ?: return false
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return false
        return try {
            adapter.getRemoteDevice(address).createBond()
        } catch (e: SecurityException) {
            false
        }
    }

    override fun watchBonds(onBonded: (address: String) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR) != BluetoothDevice.BOND_BONDED) return
                val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                onBonded(device.address)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
