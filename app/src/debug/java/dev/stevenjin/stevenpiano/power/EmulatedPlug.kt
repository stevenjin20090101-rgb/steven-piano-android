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
 * The emulator's power plug (v1.26 — M60; debug builds only), beside the emulated piano: one [EmulatedShelly] for the
 * process, chosen by `adb shell setprop debug.stevenpiano.plug <state>` as the emulated piano's lock is by
 * `debug.stevenpiano.lock`, read when the app first asks:
 *
 * - `pending` (new from its box), `confirmed` (set up with the Shelly app: the normal Pair screen), `complete` (finished its
 *   setup without this tablet: the fallback), `locked` (its setup window ran out offline), `paired` (paired with this
 *   tablet, set up, off every network), `old` (firmware 1.7.5: no pairing); unset or `none`, no plug in range at all, and
 *   the emulated piano's boards always answer, as before the plug.
 * - With a plug, the 24 V supply hangs on it: the emulated piano's `!boards` reads MISSING while the relay is open and OK
 *   0.9 s after it closes (firmware 2.1.0's re-probe); `debug.stevenpiano.trunk one-missing` keeps board 5 (MIDI 72–83)
 *   missing, `dead` every board.
 * - adb's `--es dev.stevenjin.stevenpiano.EMULATOR_PLUG "…"` ([command]): `away` (out of range) and `back`, `reset <state>`
 *   (a power cycle), `trunk ok|one-missing|dead`, `weld on|off` (the relay stuck closed), `boardsup <ms>` (how long the
 *   boards take after the supply comes up: 5000 for firmware 2.0.1's re-probe).
 *
 * Every request and answer is logged (`adb logcat -s Plug`), and the link log says what changed.
 */
object EmulatedPlug {
    @Volatile
    private var made = false

    @Volatile
    private var plug: EmulatedShelly? = null

    @Volatile
    private var trunk = ""

    @Volatile
    var away = false
        private set

    /** How long the boards take to answer after the supply comes up (`boardsup <ms>`: 5000 is firmware 2.0.1's re-probe). */
    @Volatile
    private var boardsUpMs = BOARDS_UP_MS

    /** The fake plug, made at the first ask from `debug.stevenpiano.plug`; null with none. */
    fun shelly(): EmulatedShelly? {
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

    /** The plug's Bluetooth as the app's [PlugClient] uses it. */
    fun transport(): PlugTransport = EmulatedPlugTransport()

    /**
     * The emulated piano's `!boards` while a fake plug feeds its supply; null without one (the piano's own fact stands).
     * Board 5 answers never with the trunk `one-missing`; none with `dead`.
     */
    fun boards(now: Long = SystemClock.elapsedRealtime()): String? {
        val shelly = shelly() ?: return null
        val since = shelly.poweredSince()
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
            "reset" -> {
                synchronized(this) {
                    plug = create(argument)
                    made = true
                }
            }
            "trunk" -> trunk = argument
            "boardsup" -> boardsUpMs = argument.toLongOrNull()?.coerceIn(0L, MAX_BOARDS_UP_MS) ?: BOARDS_UP_MS
            "weld" -> shelly()?.weld = argument == "on"
            else -> Log.w(TAG, "Unknown plug command: $line")
        }
        LinkLog.shared.add("The emulated power plug: $line")
    }

    private fun create(word: String): EmulatedShelly? {
        val clock = SystemClock::elapsedRealtime
        return when (word.trim().lowercase(Locale.ROOT)) {
            "", "none" -> null
            "pending" -> EmulatedShelly(Provision.Pending, clock)
            "complete" -> EmulatedShelly(Provision.Complete, clock)
            "locked" -> EmulatedShelly(Provision.Locked, clock)
            "old" -> EmulatedShelly(Provision.Confirmed, clock, firmware = "1.7.5")
            "paired" -> EmulatedShelly(Provision.Complete, clock).also { it.reset(Provision.Complete, bonded = EmulatedShelly.TABLET) }
            else -> EmulatedShelly(Provision.Confirmed, clock)
        }
    }

    /** [PlugTransport] to the fake plug: a scan finds it (unless away or restarting), connections and requests take a moment. */
    private class EmulatedPlugTransport : PlugTransport {
        @Volatile
        private var up = false

        @Volatile
        override var refused: Boolean = false
            private set

        override fun blocker(): String? = null

        private fun reachable(): EmulatedShelly? = shelly()?.takeIf { !away && !it.rebooting() }

        override suspend fun scan(timeoutMs: Long, found: (FoundPlug) -> Unit): String? {
            delay(FIND_MS)
            if (reachable() != null) found(FoundPlug(EmulatedShelly.ADDRESS, EmulatedShelly.NAME, RSSI))
            delay((minOf(timeoutMs, SCAN_MS) - FIND_MS).coerceAtLeast(0))
            return null
        }

        override suspend fun connect(address: String): Boolean {
            delay(CONNECT_MS)
            refused = false
            up = reachable() != null && address.equals(EmulatedShelly.ADDRESS, ignoreCase = true)
            if (!up) Log.d(TAG, "connect $address: not reachable")
            return up
        }

        override val connected: Boolean get() = up && reachable() != null

        override suspend fun exchange(frame: ByteArray, timeoutMs: Long): ByteArray? {
            delay(RPC_MS)
            val shelly = reachable()
            if (!up || shelly == null) {
                up = false
                return null
            }
            val text = String(frame, Charsets.UTF_8)
            Log.d(TAG, "> $text")
            return when (val answer = shelly.handle(EmulatedShelly.TABLET, text)) {
                is EmulatedShelly.Answer.Reply -> {
                    Log.d(TAG, "< ${answer.json}")
                    answer.json.toByteArray(Charsets.UTF_8)
                }
                EmulatedShelly.Answer.Refused -> {
                    Log.d(TAG, "< refused: insufficient authentication")
                    refused = true
                    null
                }
            }
        }

        override fun disconnect() {
            up = false
        }

        override suspend fun bond(address: String, timeoutMs: Long): Boolean {
            delay(BOND_MS)   // Android's pairing request, accepted
            return reachable()?.bond(EmulatedShelly.TABLET) == true
        }

        override fun bonded(address: String): Boolean = shelly()?.bonded(EmulatedShelly.TABLET) == true
    }

    private const val TAG = "Plug"
    private const val PLUG_PROPERTY = "debug.stevenpiano.plug"
    private const val TRUNK_PROPERTY = "debug.stevenpiano.trunk"
    private const val BOARDS = 7
    private const val MISSING_BOARD = 4

    /** Firmware 2.1.0 re-probes a missing board every second: the boards answer about this long after the supply comes up. */
    const val BOARDS_UP_MS = 900L
    private const val MAX_BOARDS_UP_MS = 30_000L
    private const val FIND_MS = 800L
    private const val SCAN_MS = 2_500L
    private const val CONNECT_MS = 250L
    private const val RPC_MS = 40L
    private const val BOND_MS = 700L
    private const val RSSI = -58
}
