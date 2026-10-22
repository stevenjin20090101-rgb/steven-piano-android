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
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dev.stevenjin.stevenpiano.ble.BlePermissions
import dev.stevenjin.stevenpiano.ble.ScanThrottle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * [PlugTransport] on Android Bluetooth (v1.26 — M60): a GATT client of its own beside the piano's link, one operation in
 * flight at a time (each awaited before the next), the RPC frames as [ShellyGatt] says. Its scans share the app's
 * [throttle] with the piano's link (Android ignores a sixth scan in 30 s). A revoked permission reads as a failure.
 */
@SuppressLint("MissingPermission")
class AndroidPlugTransport(private val context: Context, private val throttle: ScanThrottle) : PlugTransport {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile
    private var session: Session? = null

    @Volatile
    override var refused: Boolean = false
        private set

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
        blocker()?.let { return it }
        val scanner = adapter?.bluetoothLeScanner ?: return BLUETOOTH_OFF
        var wait = throttle.acquire(SystemClock.elapsedRealtime())
        while (wait > 0) {
            delay(wait)
            wait = throttle.acquire(SystemClock.elapsedRealtime())
        }
        val seen = ConcurrentHashMap.newKeySet<String>()
        val failed = CompletableDeferred<Int>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = report(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::report)

            override fun onScanFailed(errorCode: Int) {
                failed.complete(errorCode)
            }

            private fun report(result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: return
                if (!name.startsWith(ShellyGatt.NAME_PREFIX)) return
                if (seen.add(result.device.address)) found(FoundPlug(result.device.address, name, result.rssi))
            }
        }
        return try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            withTimeoutOrNull(timeoutMs) { failed.await() }?.let { "Bluetooth couldn't search (code $it). Turn Bluetooth off and on, then try again." }
        } catch (e: SecurityException) {
            NO_PERMISSION
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    override suspend fun connect(address: String): Boolean {
        disconnect()
        refused = false
        val adapter = adapter ?: return false
        if (blocker() != null || !BluetoothAdapter.checkBluetoothAddress(address)) return false
        val next = Session()
        session = next
        val opened = withTimeoutOrNull(CONNECT_MS) { next.open(adapter.getRemoteDevice(address)) } == true
        if (!opened) {
            next.close()
            if (session === next) session = null
        }
        return opened
    }

    override suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray? {
        val current = session?.takeIf { it.ready } ?: return null
        val reply = withTimeoutOrNull(timeoutMs) { current.exchange(frame) }
        if (reply == null) refused = current.refused
        return reply
    }

    override fun disconnect() {
        session?.close()
        session = null
    }

    override suspend fun bond(address: String, timeoutMs: Long): Boolean {
        val adapter = adapter ?: return false
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return false
        return try {
            val device = adapter.getRemoteDevice(address)
            if (device.bondState == BluetoothDevice.BOND_BONDED) return true
            val done = CompletableDeferred<Boolean>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val changed = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    if (changed?.address != address) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                        BluetoothDevice.BOND_BONDED -> done.complete(true)
                        BluetoothDevice.BOND_NONE -> done.complete(false)
                    }
                }
            }
            ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) return false
                withTimeoutOrNull(timeoutMs) { done.await() } ?: (device.bondState == BluetoothDevice.BOND_BONDED)
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        } catch (e: SecurityException) {
            false
        }
    }

    override fun bonded(address: String): Boolean = try {
        BluetoothAdapter.checkBluetoothAddress(address) && adapter?.getRemoteDevice(address)?.bondState == BluetoothDevice.BOND_BONDED
    } catch (e: SecurityException) {
        false
    }

    /** One GATT connection: each operation is started, then awaited ([pending]) before the next. */
    private inner class Session : BluetoothGattCallback() {
        @Volatile
        var ready = false

        @Volatile
        var refused = false
        private var gatt: BluetoothGatt? = null
        private var mtu = ShellyGatt.MIN_MTU
        private var data: BluetoothGattCharacteristic? = null
        private var txCtl: BluetoothGattCharacteristic? = null
        private var rxCtl: BluetoothGattCharacteristic? = null

        @Volatile
        private var pending: CompletableDeferred<Result>? = null

        /** What one operation came back with: GATT's [status], and a read's [value]. */
        inner class Result(val status: Int, val value: ByteArray? = null) {
            val ok: Boolean get() = status == BluetoothGatt.GATT_SUCCESS
        }

        suspend fun open(device: BluetoothDevice): Boolean {
            val connectedOp = expect()
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE) ?: return false
            if (!connectedOp.await().ok) return false
            val mtuOp = expect()
            if (gatt?.requestMtu(WANTED_MTU) == true) withTimeoutOrNull(OP_MS) { mtuOp.await() }
            val discovered = expect()
            if (gatt?.discoverServices() != true || !discovered.await().ok) return false
            val service = gatt?.getService(ShellyGatt.SERVICE) ?: return false
            data = service.getCharacteristic(ShellyGatt.DATA)
            txCtl = service.getCharacteristic(ShellyGatt.TX_CTL)
            rxCtl = service.getCharacteristic(ShellyGatt.RX_CTL)
            ready = data != null && txCtl != null && rxCtl != null
            return ready
        }

        /** One request: its length to TX control, the frame to data in MTU-sized writes, then the reply's length and bytes. */
        suspend fun exchange(frame: ByteArray): ByteArray? {
            if (!write(txCtl, ShellyGatt.length(frame.size))) return null
            for (chunk in ShellyGatt.chunks(frame, mtu)) if (!write(data, chunk)) return null
            var length = 0
            while (length == 0) {
                length = ShellyGatt.lengthOf(read(rxCtl) ?: return null) ?: return null
                if (length == 0) delay(REPLY_POLL_MS)   // not ready yet
            }
            if (length < 0 || length > ShellyGatt.MAX_FRAME) return null
            val reply = ByteArrayOutputStream()
            while (reply.size() < length) {
                val chunk = read(data) ?: return null
                if (chunk.isEmpty()) return null
                reply.write(chunk)
            }
            return reply.toByteArray().copyOf(length)
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
                if (started) return judge(op.await())
                pending = null
                delay(BUSY_MS)
            }
            return false
        }

        private suspend fun read(characteristic: BluetoothGattCharacteristic?): ByteArray? {
            val gatt = gatt ?: return null
            characteristic ?: return null
            repeat(BUSY_TRIES) {
                val op = expect()
                if (gatt.readCharacteristic(characteristic)) {
                    val result = op.await()
                    return if (judge(result)) result.value ?: ByteArray(0) else null
                }
                pending = null
                delay(BUSY_MS)
            }
            return null
        }

        /** A plug that enforces pairing answers a stranger with an authentication or encryption error. */
        private fun judge(result: Result): Boolean {
            if (result.status == GATT_INSUFFICIENT_AUTHENTICATION || result.status == GATT_INSUFFICIENT_ENCRYPTION) refused = true
            return result.ok
        }

        private fun expect(): CompletableDeferred<Result> = CompletableDeferred<Result>().also { pending = it }

        private fun complete(result: Result) {
            pending?.complete(result)
        }

        fun close() {
            ready = false
            pending?.complete(Result(DISCONNECTED))
            runCatching {
                gatt?.disconnect()
                gatt?.close()
            }
            gatt = null
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                complete(Result(status))
            } else {
                ready = false
                complete(Result(if (status == BluetoothGatt.GATT_SUCCESS) DISCONNECTED else status))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this.mtu = mtu
            complete(Result(status))
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = complete(Result(status))

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = complete(Result(status))

        // API 33+: the value arrives with the callback. Not calling super keeps the old callback below silent.
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            complete(Result(status, value.copyOf()))

        // API 26-32: the value sits in the characteristic.
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            complete(Result(status, characteristic.value?.copyOf()))
    }

    companion object {
        /** The MTU asked for: the plug's frames are a few hundred bytes. */
        const val WANTED_MTU = 247
        const val CONNECT_MS = 10_000L
        const val OP_MS = 3_000L
        const val REPLY_POLL_MS = 30L
        const val BUSY_TRIES = 5
        const val BUSY_MS = 20L

        /** Not a GATT status: the connection went while an operation waited. */
        const val DISCONNECTED = -1

        /** GATT's statuses for a stranger to a plug that enforces pairing. */
        const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        const val GATT_INSUFFICIENT_ENCRYPTION = 15

        const val NO_BLUETOOTH = "This tablet has no Bluetooth."
        const val NO_PERMISSION = "Steven Piano needs the Nearby devices permission: allow it on the Instrument page first."
        const val BLUETOOTH_OFF = "Bluetooth is off. Turn it on, then try again."
    }
}
