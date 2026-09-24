// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.stevenjin.stevenpiano.BuildConfig
import dev.stevenjin.stevenpiano.midi.MidiBatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The emulator has no piano: in debug builds there this link stands in. It "finds" the piano
 * after a moment, then logs every message it is given (`adb logcat -s PianoLink`), so the
 * stop sequence can be checked: `B0 40 00` then `B0 7B 00`.
 */
class LoggingPianoLink : PianoLink {
    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val found = Runnable { _state.value = LinkState.Connected(PianoBluetooth.NAME, LOGGED_MTU) }

    override fun connect(address: String?) {
        if (_state.value is LinkState.Connected) return
        _state.value = LinkState.Scanning
        main.postDelayed(found, SCAN_MS)
    }

    override fun disconnect() {
        main.removeCallbacks(found)
        _state.value = LinkState.Disconnected
    }

    override fun send(batch: MidiBatch, dropPending: Boolean) {
        if (_state.value !is LinkState.Connected) return
        for (i in 0 until batch.size) {
            Log.d(TAG, "%02X %02X %02X".format(batch.status(i), batch.data1(i), batch.data2(i)))
        }
    }

    override fun flush(timeoutMs: Long): Boolean = true

    companion object {
        private const val TAG = "PianoLink"
        private const val LOGGED_MTU = 255
        private const val SCAN_MS = 1_500L

        /** Debug builds on an emulator, which has no piano to reach. */
        fun isWanted(): Boolean =
            BuildConfig.DEBUG && (Build.HARDWARE in setOf("goldfish", "ranchu") || Build.FINGERPRINT.startsWith("generic"))
    }
}
