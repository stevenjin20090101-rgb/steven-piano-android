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
import dev.stevenjin.stevenpiano.diag.LinkLog
import dev.stevenjin.stevenpiano.midi.MidiBatch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The emulator has no piano: in debug builds there this link stands in. It "finds" the piano
 * after a moment, then logs every message it is given (`adb logcat -s PianoLink`), so the
 * stop sequence can be checked: `B0 40 00` then `B0 7B 00`.
 *
 * It has the piano's console too, answered by an [EmulatedConsole] a moment later, so the
 * Piano tab's settings work on the emulator; every line both ways is logged ("console > ledbright 40",
 * "console < ledbright=40"). The emulated piano keeps its values across connections, as a
 * powered piano does. [consoleMode] is asked on each connection: `adb shell setprop
 * debug.stevenpiano.console none` connects to a piano without a console (older firmware), `mute`
 * to one whose console never answers; anything else, the full console. Its connections (not the
 * MIDI it logs) also go to [LinkLog], so the diagnostics share has a link log on the emulator too.
 */
class LoggingPianoLink(private val consoleMode: () -> ConsoleMode = ::consoleModeFromProperty) : PianoLink {
    /** Which piano the emulator pretends to reach. */
    enum class ConsoleMode { Full, None, Mute }

    private val _state = MutableStateFlow<LinkState>(LinkState.Disconnected)
    override val state: StateFlow<LinkState> = _state.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val emulated = EmulatedConsole()
    private val replies = MutableSharedFlow<String>(extraBufferCapacity = REPLY_BUFFER_LINES)

    @Volatile
    private var mode = ConsoleMode.Full

    @Volatile
    override var console: ConsoleChannel? = null
        private set

    private val channel = object : ConsoleChannel {
        override val lines: SharedFlow<String> = replies.asSharedFlow()

        override fun sendLine(text: String) {
            if (console !== this || PianoConsole.encode(text) == null) return
            Log.d(TAG, "console > $text")
            if (mode == ConsoleMode.Mute) return
            val answer = synchronized(emulated) { emulated.handle(text) }
            main.postDelayed({
                if (console === this) {
                    answer.forEach { line ->
                        Log.d(TAG, "console < $line")
                        replies.tryEmit(line)
                    }
                }
            }, REPLY_MS)
        }
    }

    private val found = Runnable {
        mode = consoleMode()
        console = if (mode == ConsoleMode.None) null else channel
        Log.d(TAG, "Connected (emulated), console: ${mode.name.lowercase()}")
        LinkLog.shared.add("Connected to Steven Piano (emulated), console: ${mode.name.lowercase()}")
        _state.value = LinkState.Connected(PianoBluetooth.NAME, LOGGED_MTU)
    }

    override fun connect(address: String?) {
        if (_state.value is LinkState.Connected) return
        LinkLog.shared.add("Connect (emulated): ${address ?: "no piano remembered yet"}")
        _state.value = LinkState.Scanning
        main.postDelayed(found, SCAN_MS)
    }

    override fun disconnect() {
        main.removeCallbacks(found)
        if (_state.value != LinkState.Disconnected) LinkLog.shared.add("Disconnected (emulated)")
        console = null
        _state.value = LinkState.Disconnected
    }

    override fun send(batch: MidiBatch, dropPending: Boolean) {
        if (_state.value !is LinkState.Connected) return
        for (i in 0 until batch.size) {
            Log.d(TAG, "%02X %02X %02X".format(batch.status(i), batch.data1(i), batch.data2(i)))
        }
    }

    override fun flush(timeoutMs: Long): Boolean = true

    /** There is no piano to silence: the call is logged, so the crash path can be seen on the emulator. */
    override fun emergencySilence(timeoutMs: Long): Boolean {
        Log.d(TAG, "Emergency silence (${timeoutMs} ms): B0 40 00, B0 7B 00")
        return _state.value is LinkState.Connected
    }

    companion object {
        private const val TAG = "PianoLink"
        private const val LOGGED_MTU = 255
        private const val SCAN_MS = 1_500L
        private const val REPLY_MS = 40L
        private const val REPLY_BUFFER_LINES = 512
        private const val CONSOLE_PROPERTY = "debug.stevenpiano.console"

        /** Debug builds on an emulator, which has no piano to reach. */
        fun isWanted(): Boolean =
            BuildConfig.DEBUG && (Build.HARDWARE in setOf("goldfish", "ranchu") || Build.FINGERPRINT.startsWith("generic"))

        /** `debug.stevenpiano.console`: "none", "mute", or unset for the full console. Read with getprop (debug builds only). */
        private fun consoleModeFromProperty(): ConsoleMode {
            val value = runCatching {
                ProcessBuilder("getprop", CONSOLE_PROPERTY).start().inputStream.bufferedReader().use { it.readText().trim() }
            }.getOrDefault("")
            return when (value) {
                "none" -> ConsoleMode.None
                "mute" -> ConsoleMode.Mute
                else -> ConsoleMode.Full
            }
        }
    }
}
