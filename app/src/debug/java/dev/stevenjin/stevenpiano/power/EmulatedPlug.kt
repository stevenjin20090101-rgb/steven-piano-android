// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import android.os.SystemClock
import android.util.Log
import dev.stevenjin.stevenpiano.SystemProperty
import dev.stevenjin.stevenpiano.diag.LinkLog
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * The emulator's power plug (v1.26 — M60; the SwitchBot Plug Mini since v1.36 — M72; debug builds only), beside the
 * emulated piano: one [EmulatedSwitchBot] for the process, chosen by `adb shell setprop debug.stevenpiano.plug <state>` as
 * the emulated piano's lock is by `debug.stevenpiano.lock`, read when the app first asks:
 *
 * - `on` (or anything else: plugged in, it comes on by itself, as the real one did), `off`; unset or `none`, no plug in
 *   range at all, and the emulated piano's boards always answer, as before the plug.
 * - With a plug, the 24 V supply hangs on it: the emulated piano's `!boards` reads MISSING while the relay is open and OK
 *   0.9 s after it closes (firmware 2.1.0's re-probe); `debug.stevenpiano.trunk one-missing` keeps board 5 (MIDI 72–83)
 *   missing, `dead` every board.
 * - adb's `--es dev.stevenjin.stevenpiano.EMULATOR_PLUG "…"` ([command]): `away` (out of range: no adverts, no GATT) and
 *   `back`, `reset` (a power cut: back on by itself), `outside on` (switched on with the SwitchBot app), `draw <W>` (the
 *   power watch's debug path: the supply draws that much more while up; 12 is a coil stuck on), `silent on|off` (adverts,
 *   but its GATT answers nothing), `trunk ok|one-missing|dead`, `weld on|off` (the relay stuck closed), `boardsup <ms>`
 *   (how long the boards take after the supply comes up).
 *
 * Every command and reply is logged (`adb logcat -s Plug`), and the link log says what changed.
 */
object EmulatedPlug {
    @Volatile
    private var made = false

    @Volatile
    private var plug: EmulatedSwitchBot? = null

    @Volatile
    private var trunk = ""

    @Volatile
    var away = false
        private set

    /** How long the boards take to answer after the supply comes up (`boardsup <ms>`: 5000 is firmware 2.0.1's re-probe). */
    @Volatile
    private var boardsUpMs = BOARDS_UP_MS

    /** The fake plug, made at the first ask from `debug.stevenpiano.plug`; null with none. */
    fun switchBot(): EmulatedSwitchBot? {
        if (!made) {
            synchronized(this) {
                if (!made) {
                    plug = create(SystemProperty.read(PLUG_PROPERTY))
                    trunk = SystemProperty.read(TRUNK_PROPERTY)
                    made = true
                }
            }
        }
        return plug
    }

    /** The plug's Bluetooth as the app's [PlugClient] and [PlugAdverts] use it. */
    fun transport(): PlugTransport = EmulatedPlugTransport()

    /**
     * The emulated piano's `!boards` while a fake plug feeds its supply; null without one (the piano's own fact stands).
     * Board 5 answers never with the trunk `one-missing`; none with `dead`.
     */
    fun boards(now: Long = SystemClock.elapsedRealtime()): String? {
        val plug = switchBot() ?: return null
        val since = plug.poweredSince()
        val up = since != null && now - since >= boardsUpMs
        return (0 until BOARDS).joinToString(",") { i -> if (up && answers(i)) "OK" else "MISSING" }
    }

    private fun answers(board: Int): Boolean = when (trunk) {
        "dead" -> false
        "one-missing" -> board != MISSING_BOARD
        else -> true
    }

    /** adb's EMULATOR_PLUG commands. */
    fun command(line: String) {
        val word = line.trim().substringBefore(' ').lowercase(Locale.ROOT)
        val argument = line.trim().substringAfter(' ', "").trim().lowercase(Locale.ROOT)
        when (word) {
            "away" -> away = true
            "back" -> away = false
            "reset" -> switchBot()?.powerCut()
            "trunk" -> trunk = argument
            "boardsup" -> boardsUpMs = argument.toLongOrNull()?.coerceIn(0L, MAX_BOARDS_UP_MS) ?: BOARDS_UP_MS
            "weld" -> switchBot()?.weld = argument == "on"
            "outside" -> if (argument == "on") switchBot()?.switchOnFromOutside()
            "draw" -> switchBot()?.extraWatts = argument.toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.0, MAX_EXTRA_WATTS) ?: 0.0
            "silent" -> switchBot()?.silent = argument == "on"
            else -> Log.w(TAG, "Unknown plug command: $line")
        }
        LinkLog.shared.add("The emulated power plug: $line")
    }

    private fun create(word: String): EmulatedSwitchBot? {
        val clock = SystemClock::elapsedRealtime
        return when (word.trim().lowercase(Locale.ROOT)) {
            "", "none" -> null
            "off" -> EmulatedSwitchBot(clock, on = false)
            else -> EmulatedSwitchBot(clock)
        }
    }

    /** [PlugTransport] to the fake plug: a scan and the advert watch find it (unless away), connections and commands take a moment. */
    private class EmulatedPlugTransport : PlugTransport {
        @Volatile
        private var up = false

        override fun blocker(): String? = null

        private fun reachable(): EmulatedSwitchBot? = switchBot()?.takeIf { !away }

        override suspend fun scan(timeoutMs: Long, found: (FoundPlug) -> Unit): String? {
            delay(FIND_MS)
            reachable()?.let { plug ->
                val model = SwitchBot.model(plug.serviceData()) ?: return@let
                SwitchBot.advert(plug.manufacturer())?.let { found(FoundPlug(EmulatedSwitchBot.ADDRESS, it.mac, model, RSSI)) }
            }
            delay((minOf(timeoutMs, SCAN_MS) - FIND_MS).coerceAtLeast(0))
            return null
        }

        override suspend fun watch(address: String, mac: String?, fast: Boolean, heard: (PlugAdvert) -> Unit): String {
            while (true) {
                delay(if (fast) FAST_ADVERT_MS else SLOW_ADVERT_MS)
                val plug = reachable() ?: continue
                if (!address.equals(EmulatedSwitchBot.ADDRESS, ignoreCase = true)) continue
                SwitchBot.advert(plug.manufacturer())?.let(heard)
            }
        }

        override suspend fun connect(address: String): Boolean {
            delay(CONNECT_MS)
            up = reachable() != null && address.equals(EmulatedSwitchBot.ADDRESS, ignoreCase = true)
            if (!up) Log.d(TAG, "connect $address: not reachable")
            return up
        }

        override val connected: Boolean get() = up && reachable() != null

        override suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray? {
            delay(GATT_MS)
            val plug = reachable()
            if (!up || plug == null) {
                up = false
                return null
            }
            val reply = plug.handle(frame)
            Log.d(TAG, "> ${hex(frame)}  < " + (reply?.let(::hex) ?: "nothing"))
            if (reply == null) delay((timeoutMs - GATT_MS).coerceAtLeast(0))
            return reply
        }

        override fun disconnect() {
            up = false
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF) }
    }

    private const val TAG = "Plug"
    private const val PLUG_PROPERTY = "debug.stevenpiano.plug"
    private const val TRUNK_PROPERTY = "debug.stevenpiano.trunk"
    private const val BOARDS = 7
    private const val MISSING_BOARD = 4
    private const val MAX_EXTRA_WATTS = 500.0

    /** Firmware 2.1.0 re-probes a missing board every second: the boards answer about this long after the supply comes up. */
    const val BOARDS_UP_MS = 900L
    private const val MAX_BOARDS_UP_MS = 30_000L
    private const val FIND_MS = 800L
    private const val SCAN_MS = 2_500L
    private const val CONNECT_MS = 250L
    private const val GATT_MS = 40L

    /** About 3 adverts a second, as the real plug sends them; the low-power scan catches about one in five seconds. */
    private const val FAST_ADVERT_MS = 333L
    private const val SLOW_ADVERT_MS = 2_000L
    private const val RSSI = -58
}
