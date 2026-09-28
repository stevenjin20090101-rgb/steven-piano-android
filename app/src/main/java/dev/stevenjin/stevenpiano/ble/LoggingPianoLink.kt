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
import dev.stevenjin.stevenpiano.firmware.FakeOta
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
 * to one whose console never answers; anything else, the full console. `debug.stevenpiano.piano
 * away` is a piano switched off: the scan ends unfound after the real one's 12 s (v1.5.2, for a
 * schedule's missed start). Its connections (not the
 * MIDI it logs) also go to [LinkLog], so the diagnostics share has a link log on the emulator too.
 * The firmware (v1.6 — M21): with `debug.stevenpiano.fakeota` naming a [FakeOta] scenario, the
 * emulated piano reports its version and has the update service ([EmulatedOta]), and after an
 * update's OK it restarts: it drops for [BOOT_MS] and comes back as the scenario says, pending its
 * self-test and confirmed [CONFIRM_MS] later. Without the property it has neither, as firmware
 * older than 2.0.0.
 */
class LoggingPianoLink(
    private val consoleMode: () -> ConsoleMode = ::consoleModeFromProperty,
    private val fakeOta: () -> FakeOta? = FakeOta::fromProperty,
) : PianoLink {
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

    private val version = MutableStateFlow<String?>(null)
    override val firmwareVersion: StateFlow<String?> = version.asStateFlow()

    @Volatile
    override var ota: OtaChannel? = null
        private set

    /** The fake update scenario this connection plays, if any. */
    private var scenario: FakeOta? = null

    private val found = Runnable {
        mode = consoleMode()
        console = if (mode == ConsoleMode.None) null else channel
        val fake = fakeOta()
        scenario = fake
        val running = fake?.running
        if (fake != null) {
            synchronized(emulated) {
                emulated.setFact("fw", running ?: "emulator")
                if (running != null) emulated.setFact("ota", "none")
            }
        }
        comeUp(running)
        Log.d(TAG, "Connected (emulated), console: ${mode.name.lowercase()}, firmware: ${running ?: "no version"}")
        LinkLog.shared.add("Connected to Steven Piano (emulated), console: ${mode.name.lowercase()}" + (fake?.let { ", firmware update scenario ${it.key}" } ?: ""))
        _state.value = LinkState.Connected(PianoBluetooth.NAME, LOGGED_MTU)
    }

    /** The piano's version and update service for this connection: firmware 2.0.0 and later have both. */
    private fun comeUp(running: String?) {
        val fake = scenario
        version.value = running
        ota = if (running != null && fake != null) EmulatedOta(main, fake.script, onDrop = ::droppedMidUpdate, onRestart = ::restartAfterUpdate) else null
    }

    /** The scenario's disconnect: the piano goes, and is back [DROP_BACK_MS] later on the firmware it had. */
    private fun droppedMidUpdate() {
        goAway("The connection dropped mid-update (emulated)")
        main.postDelayed({ comeBack(scenario?.running, "none") }, DROP_BACK_MS)
    }

    /** After OK: the piano drops in [inMs], restarts, and is back [BOOT_MS] later as the scenario says. */
    private fun restartAfterUpdate(inMs: Int) {
        main.postDelayed({
            goAway("The piano restarts after its update (emulated)")
            val after = scenario?.afterRestart ?: return@postDelayed
            main.postDelayed({ comeBack(after.first, after.second) }, BOOT_MS)
        }, inMs.toLong())
    }

    private fun goAway(line: String) {
        LinkLog.shared.add(line)
        console = null
        ota = null
        version.value = null
        _state.value = LinkState.Reconnecting(1)
    }

    /** Back, running [running] with `!ota` [otaFact]; a pending image confirms itself [CONFIRM_MS] later. */
    private fun comeBack(running: String?, otaFact: String) {
        if (_state.value !is LinkState.Reconnecting) return
        synchronized(emulated) {
            emulated.setFact("fw", running ?: "emulator")
            emulated.setFact("ota", otaFact)
        }
        if (otaFact == "pending") {
            main.postDelayed({ synchronized(emulated) { emulated.setFact("ota", "confirmed") } }, CONFIRM_MS)
        }
        console = if (mode == ConsoleMode.None) null else channel
        comeUp(running)
        LinkLog.shared.add("Connected to Steven Piano (emulated) again, firmware ${running ?: "no version"}")
        _state.value = LinkState.Connected(PianoBluetooth.NAME, LOGGED_MTU)
    }

    override fun expectRestart(withinMs: Long) {
        LinkLog.shared.add("The piano restarts after its update (emulated): a drop in the next ${withinMs / 1000} s is expected")
    }

    override fun connect(address: String?) {
        if (_state.value is LinkState.Connected) return
        LinkLog.shared.add("Connect (emulated): ${address ?: "no piano remembered yet"}")
        _state.value = LinkState.Scanning
        main.removeCallbacks(found)
        main.removeCallbacks(notFound)
        // `debug.stevenpiano.piano away`: the piano is switched off; the scan ends unfound, as the real one's timeout.
        main.postDelayed(if (pianoAway()) notFound else found, if (pianoAway()) SCAN_TIMEOUT_MS else SCAN_MS)
    }

    private val notFound = Runnable {
        LinkLog.shared.add("Not found (emulated): debug.stevenpiano.piano is away")
        _state.value = LinkError.NotFound().toState()
    }

    override fun disconnect() {
        main.removeCallbacks(found)
        main.removeCallbacks(notFound)
        if (_state.value != LinkState.Disconnected) LinkLog.shared.add("Disconnected (emulated)")
        console = null
        ota = null
        version.value = null
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
        private const val PIANO_PROPERTY = "debug.stevenpiano.piano"

        /** The real scan's timeout (PianoScanner), for the piano that stays away. */
        private const val SCAN_TIMEOUT_MS = 12_000L

        /** `debug.stevenpiano.piano away`: no piano to find (a schedule's missed start, on the emulator). Read with getprop each time. */
        private fun pianoAway(): Boolean = runCatching {
            ProcessBuilder("getprop", PIANO_PROPERTY).start().inputStream.bufferedReader().use { it.readText().trim() } == "away"
        }.getOrDefault(false)

        /** How long the emulated piano is away while it restarts after an update. */
        private const val BOOT_MS = 4_000L

        /** How long after coming back a pending image confirms itself (the firmware's self-test: 30 s from boot). */
        private const val CONFIRM_MS = 25_000L

        /** How long the emulated piano is away after the scenario's disconnect. */
        private const val DROP_BACK_MS = 3_000L

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
