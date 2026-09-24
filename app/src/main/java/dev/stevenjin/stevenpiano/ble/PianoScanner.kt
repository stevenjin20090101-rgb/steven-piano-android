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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid

/**
 * Finds BLE-MIDI devices with a hardware-filtered, low-latency scan. The piano's name is in its
 * scan response, which active scanning receives, so the filter is on the MIDI service UUID.
 * Permissions are checked by [AndroidBleRadio.blocker] before every scan.
 */
@SuppressLint("MissingPermission")
class PianoScanner(private val adapter: BluetoothAdapter) {
    private var callback: ScanCallback? = null

    fun start(onFound: (FoundPiano) -> Unit, onFailed: (errorCode: Int) -> Unit) {
        stop()
        val scanner = adapter.bluetoothLeScanner ?: return onFailed(NO_SCANNER)   // null while Bluetooth is off
        val scan = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onFound(result.toFound())

            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onFound(it.toFound()) }

            override fun onScanFailed(errorCode: Int) = onFailed(errorCode)
        }
        callback = scan
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(PianoBluetooth.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scan)
    }

    fun stop() {
        val scan = callback ?: return
        callback = null
        adapter.bluetoothLeScanner?.stopScan(scan)
    }

    private fun ScanResult.toFound() = FoundPiano(device.address, scanRecord?.deviceName ?: device.name)

    companion object {
        /** How long one search for the piano lasts. */
        const val SCAN_TIMEOUT_MS = 12_000L
        private const val NO_SCANNER = -1
    }
}

/** Android silently ignores apps that start more than 5 scans in 30 s; this keeps the link under that. */
class ScanThrottle(private val maxScans: Int = 5, private val windowMs: Long = 30_000L) {
    private val starts = ArrayDeque<Long>()

    /** 0 when a scan may start at [nowMs], else how long to wait. */
    fun delayBeforeNextScan(nowMs: Long): Long {
        while (starts.isNotEmpty() && starts.first() <= nowMs - windowMs) starts.removeFirst()
        return if (starts.size < maxScans) 0L else starts.first() + windowMs - nowMs
    }

    fun record(nowMs: Long) {
        starts.addLast(nowMs)
    }
}
