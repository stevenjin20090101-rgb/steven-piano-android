// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import dev.stevenjin.stevenpiano.ble.BlePermissions
import dev.stevenjin.stevenpiano.ble.ScanThrottle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [PlugTransport] on Android Bluetooth (v1.26 — M60; the SwitchBot Plug Mini since v1.36 — M72): a GATT client of its own
 * beside the piano's link, one operation in flight at a time (each awaited before the next): a command written with a
 * response to [SwitchBot.RX], its reply the next notification on [SwitchBot.TX]. No pairing, no bond. Its scans share the
 * app's [throttle] with the piano's link (Android ignores a sixth scan in 30 s); the advert watch's is filtered to company
 * 2409 and the plug's MAC bytes, so it runs with the screen off, and starts again every [WATCH_RENEW_MS] (Android quietens
 * a scan that runs for half an hour). A revoked permission reads as a failure.
 */
@SuppressLint("MissingPermission")
class AndroidPlugTransport(private val context: Context, private val throttle: ScanThrottle) : PlugTransport {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile
    private var session: Session? = null

    override val connected: Boolean get() = session?.ready == true

    override fun blocker(): String? {
        val adapter = adapter ?: return NO_BLUETOOTH
        return when {
            BlePermissions.missing(context).isNotEmpty() -> NO_PERMISSION
            !adapter.isEnabled -> BLUETOOTH_OFF
            else -> null
        }
    }

    override suspend fun scan(timeoutMs: Long, found: (FoundPlug) -> Unit): String? {
        val filter = ScanFilter.Builder().setManufacturerData(SwitchBot.COMPANY, ByteArray(0)).build()
        return scanFor(filter, ScanSettings.SCAN_MODE_LOW_LATENCY, timeoutMs) { result ->
            val record = result.scanRecord ?: return@scanFor
            val model = SwitchBot.model(record.getServiceData(ParcelUuid(SwitchBot.SERVICE_DATA))) ?: return@scanFor
            val advert = SwitchBot.advert(record.getManufacturerSpecificData(SwitchBot.COMPANY)) ?: return@scanFor
            found(FoundPlug(result.device.address, advert.mac, model, result.rssi))
        }
    }

    override suspend fun watch(address: String, mac: String?, fast: Boolean, heard: (PlugAdvert) -> Unit): String {
        // The plug's MAC bytes at the front of company 2409's data: the hardware filter keeps every other device's out.
        val bytes = mac?.let(SwitchBot::macBytes)
        val filter = ScanFilter.Builder().apply {
            if (bytes != null) setManufacturerData(SwitchBot.COMPANY, bytes, ByteArray(bytes.size) { 0xFF.toByte() }) else setDeviceAddress(address)
        }.build()
        val mode = if (fast) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER
        while (true) {
            val why = scanFor(filter, mode, WATCH_RENEW_MS) { result ->
                val record = result.scanRecord ?: return@scanFor
                val advert = SwitchBot.advert(record.getManufacturerSpecificData(SwitchBot.COMPANY)) ?: return@scanFor
                if (SwitchBot.same(advert.mac, mac) || SwitchBot.same(result.device.address, address)) heard(advert)
            }
            if (why != null) return why
        }
    }

    /** One scan with [filter] in [mode] for [timeoutMs], each result to [report]: null when it ran its time, else why not. */
    private suspend fun scanFor(filter: ScanFilter, mode: Int, timeoutMs: Long, report: (ScanResult) -> Unit): String? {
        blocker()?.let { return it }
        val scanner = adapter?.bluetoothLeScanner ?: return BLUETOOTH_OFF
        var wait = throttle.acquire(SystemClock.elapsedRealtime())
        while (wait > 0) {
            delay(wait)
            wait = throttle.acquire(SystemClock.elapsedRealtime())
        }
        val failed = CompletableDeferred<Int>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = report(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(report)

            override fun onScanFailed(errorCode: Int) {
                failed.complete(errorCode)
            }
        }
        return try {
            scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(mode).build(), callback)
            withTimeoutOrNull(timeoutMs) { failed.await() }?.let { "Bluetooth couldn't search (code $it). Turn Bluetooth off and on, then try again." }
        } catch (e: SecurityException) {
            NO_PERMISSION
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    override suspend fun connect(address: String): Boolean {
        disconnect()
        val adapter = adapter ?: return false
        if (blocker() != null || !BluetoothAdapter.checkBluetoothAddress(address)) return false
        val next = Session()
        session = next
        val opened = try {
            withTimeoutOrNull(CONNECT_MS) { next.open(adapter.getRemoteDevice(address)) } == true
        } catch (e: SecurityException) {
            false
        }
        if (!opened) {
            next.close()
            if (session === next) session = null
        }
        return opened
    }

    override suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray? {
        val current = session?.takeIf { it.ready } ?: return null
        return withTimeoutOrNull(timeoutMs) { current.exchange(frame) }
    }

    override fun disconnect() {
        session?.close()
        session = null
    }

    /** One GATT connection: each operation is started, then awaited ([pending]) before the next; replies come as notifications. */
    private inner class Session : BluetoothGattCallback() {
        @Volatile
        var ready = false
        private var gatt: BluetoothGatt? = null
        private var rx: BluetoothGattCharacteristic? = null
        private var tx: BluetoothGattCharacteristic? = null

        @Volatile
        private var pending: CompletableDeferred<Int>? = null

        @Volatile
        private var notified: CompletableDeferred<ByteArray>? = null

        suspend fun open(device: BluetoothDevice): Boolean {
            val connectedOp = expect()
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE) ?: return false
            if (connectedOp.await() != BluetoothGatt.GATT_SUCCESS) return false
            val discovered = expect()
            if (gatt?.discoverServices() != true || discovered.await() != BluetoothGatt.GATT_SUCCESS) return false
            // RX and TX wherever they sit (their service's UUID is SwitchBot's own, and isn't needed).
            val characteristics = gatt?.services.orEmpty().flatMap { it.characteristics }
            rx = characteristics.firstOrNull { it.uuid == SwitchBot.RX }
            tx = characteristics.firstOrNull { it.uuid == SwitchBot.TX }
            val tx = tx ?: return false
            if (rx == null || gatt?.setCharacteristicNotification(tx, true) != true) return false
            val cccd = tx.getDescriptor(SwitchBot.CCCD) ?: return false
            ready = write(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            return ready
        }

        /** One command written with a response to RX; its reply, the next notification on TX. */
        suspend fun exchange(frame: ByteArray): ByteArray? {
            val reply = CompletableDeferred<ByteArray>().also { notified = it }
            if (!write(rx, frame)) return null
            return reply.await()
        }

        private suspend fun write(characteristic: BluetoothGattCharacteristic?, bytes: ByteArray): Boolean {
            val gatt = gatt ?: return false
            characteristic ?: return false
            repeat(BUSY_TRIES) {
                val op = expect()
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    characteristic.value = bytes
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
                if (started) return op.await() == BluetoothGatt.GATT_SUCCESS
                pending = null
                delay(BUSY_MS)
            }
            return false
        }

        private suspend fun write(descriptor: BluetoothGattDescriptor, bytes: ByteArray): Boolean {
            val gatt = gatt ?: return false
            repeat(BUSY_TRIES) {
                val op = expect()
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, bytes) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = bytes
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                if (started) return withTimeoutOrNull(OP_MS) { op.await() } == BluetoothGatt.GATT_SUCCESS
                pending = null
                delay(BUSY_MS)
            }
            return false
        }

        private fun expect(): CompletableDeferred<Int> = CompletableDeferred<Int>().also { pending = it }

        private fun complete(status: Int) {
            pending?.complete(status)
        }

        fun close() {
            ready = false
            pending?.complete(DISCONNECTED)
            notified?.complete(ByteArray(0))   // no reply: read as none
            runCatching {
                gatt?.disconnect()
                gatt?.close()
            }
            gatt = null
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                complete(status)
            } else {
                ready = false
                complete(if (status == BluetoothGatt.GATT_SUCCESS) DISCONNECTED else status)
                notified?.complete(ByteArray(0))   // no reply: read as none
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = complete(status)

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = complete(status)

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = complete(status)

        // API 33+: the value arrives with the callback. Not calling super keeps the old callback below silent.
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == SwitchBot.TX) notified?.complete(value.copyOf())
        }

        // API 26-32: the value sits in the characteristic.
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == SwitchBot.TX) characteristic.value?.copyOf()?.let { notified?.complete(it) }
        }
    }

    companion object {
        const val CONNECT_MS = 10_000L
        const val OP_MS = 3_000L
        const val BUSY_TRIES = 5
        const val BUSY_MS = 20L

        /** The advert watch's scan starts again this often (Android quietens one that runs for 30 minutes). */
        const val WATCH_RENEW_MS = 10 * 60_000L

        /** Not a GATT status: the connection went while an operation waited. */
        const val DISCONNECTED = -1

        const val NO_BLUETOOTH = "This tablet has no Bluetooth."
        const val NO_PERMISSION = "Steven Piano needs the Nearby devices permission: allow it on the Instrument page first."
        const val BLUETOOTH_OFF = "Bluetooth is off. Turn it on, then try again."
    }
}
