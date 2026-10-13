// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import dev.stevenjin.stevenpiano.midi.MidiBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the piano's Bluetooth lock lets a connection do (v1.25 — M58; `firmware/docs/BLE_LOCK.md`). */
sealed interface Trust {
    /** Everything goes: the piano has no lock (firmware before 2.1.0), it is open, or this tablet proved its key. */
    data object Open : Trust

    /** Being decided (the settings' dump, then the claim or the proof: 3 s at most): only releasing goes. */
    data object Pending : Trust

    /** The tablet may only read the piano ([reason]: locked to another device, or not accepted): only releasing goes. */
    data class Refused(val reason: LinkError) : Trust
}

/**
 * The lock's word for one connection: [connection] is [LinkState.Connected.connection] (null: none); [trust] null while
 * the piano's settings haven't said yet (their dump is still coming).
 */
data class LinkTrust(val connection: Long? = null, val trust: Trust? = null)

/**
 * Steven Piano's link as everything that plays sees it (v1.25 — M58, the app side of the piano's Bluetooth lock): the
 * transport [link], which the piano's settings and its updater keep reading, with what the lock allows on top.
 *
 * - **Notes wait for the lock.** A connection's [LinkTrust] comes from the piano's settings, which read the lock from
 *   the dump and claim or prove the tablet's key. Until it says so for this very connection, a piano whose firmware has
 *   the lock (2.1.0 and later, from its Device Information) is [Trust.Pending]; older firmware is [Trust.Open], exactly
 *   as before the lock. While the trust is not Open the [state] holds Connected back (Connecting while it is decided;
 *   [LinkError.LockedOut] or [LinkError.NotAccepted] when refused), so the player starts nothing and pauses what
 *   plays, as on a drop, and the connection card says why with Retry; and [send] passes only what releases.
 * - **Releasing is never locked.** The stop sequence (CC64 = 0, CC123), every Note Off, All Sound Off and Reset All
 *   Controllers always go ([releasing]: the piano honours them untrusted), and [emergencySilence], the crash handler's
 *   stop, goes straight through whatever the lock says.
 * - **Retry reconnects.** Refused, the transport stays connected (the piano's settings stay readable); [connect] then
 *   drops and connects again, so a pairing window opened on the piano's screen is used at once.
 *
 * The console, the firmware's version and its update service are the transport's own. Thread-safe.
 */
class LockGate(
    private val link: PianoLink,
    private val trust: StateFlow<LinkTrust>,
    scope: CoroutineScope,
) : PianoLink {
    override val state: StateFlow<LinkState> = combine(link.state, link.firmwareVersion, trust) { state, _, word -> gated(state, word) }
        .stateIn(scope, SharingStarted.Eagerly, gated(link.state.value, trust.value))

    override val console: ConsoleChannel? get() = link.console

    override val firmwareVersion: StateFlow<String?> get() = link.firmwareVersion

    override val ota: OtaChannel? get() = link.ota

    override val address: String? get() = link.address

    /** What the lock lets the connection there is now do. */
    fun trustNow(): Trust = trustOf(link.state.value, trust.value)

    override fun connect(address: String?) {
        if (trustNow() is Trust.Refused) link.disconnect()   // Retry while refused: a new connection, a new claim or proof
        link.connect(address)
    }

    override fun disconnect() = link.disconnect()

    override fun send(batch: MidiBatch, dropPending: Boolean) {
        if (trustNow() == Trust.Open) return link.send(batch, dropPending)
        releasing(batch)?.let { link.send(it, dropPending) }
    }

    override fun sendLive(batch: MidiBatch) {
        if (trustNow() == Trust.Open) return link.sendLive(batch)
        releasing(batch)?.let(link::sendLive)
    }

    override fun flush(timeoutMs: Long): Boolean = link.flush(timeoutMs)

    /** The crash handler's stop: always, trusted or not (the piano honours it untrusted). */
    override fun emergencySilence(timeoutMs: Long): Boolean = link.emergencySilence(timeoutMs)

    override fun expectRestart(withinMs: Long) = link.expectRestart(withinMs)

    private fun trustOf(state: LinkState, word: LinkTrust): Trust {
        if (state !is LinkState.Connected) return Trust.Open   // nothing to hold: nothing goes out anyway
        if (word.connection == state.connection) word.trust?.let { return it }
        return if (mayLock(link.firmwareVersion.value)) Trust.Pending else Trust.Open
    }

    private fun gated(state: LinkState, word: LinkTrust): LinkState = when (val trust = trustOf(state, word)) {
        Trust.Open -> state
        Trust.Pending -> LinkState.Connecting
        is Trust.Refused -> trust.reason.toState()
    }

    companion object {
        /** MAJOR.MINOR at the start of a firmware version ("2.1.0+a1b2c3d"). */
        private val MAJOR_MINOR = Regex("""^\s*(\d{1,9})\.(\d{1,9})\.""")

        /**
         * Whether firmware [version] (Device Information's, read before Connected) may have the lock: 2.1.0 or later.
         * No version, or one that doesn't read, may not: such a piano is Open until its dump says otherwise.
         */
        fun mayLock(version: String?): Boolean {
            val match = MAJOR_MINOR.find(version ?: return false) ?: return false
            val major = match.groupValues[1].toIntOrNull() ?: return false
            val minor = match.groupValues[2].toIntOrNull() ?: return false
            return major > 2 || (major == 2 && minor >= 1)
        }

        /**
         * [batch]'s messages that release (BLE_LOCK.md: what an untrusted connection may send): Note Off and Note On at
         * velocity 0, All Sound Off (CC120), Reset All Controllers (CC121), All Notes Off (CC123) and the pedal going up
         * (CC64 below 64), in order; null when none does. Everything else (a Note On, the pedal down, any other
         * controller) is left out, as the piano would drop it.
         */
        fun releasing(batch: MidiBatch): MidiBatch? {
            var out: MidiBatch? = null
            for (i in 0 until batch.size) {
                val status = batch.status(i)
                val data1 = batch.data1(i)
                val data2 = batch.data2(i)
                if (!releases(status, data1, data2)) continue
                val into = out ?: MidiBatch()
                into.add(status, data1, data2)
                out = into
            }
            return out
        }

        /** Whether one message releases ([releasing]'s rule): what a piano that doesn't trust the connection still plays. */
        fun releases(status: Int, data1: Int, data2: Int): Boolean = when (status and 0xF0) {
            NOTE_OFF -> true
            NOTE_ON -> data2 == 0
            CONTROL -> data1 == ALL_SOUND_OFF || data1 == RESET_CONTROLLERS || data1 == ALL_NOTES_OFF || (data1 == SUSTAIN && data2 < PEDAL_DOWN)
            else -> false
        }

        private const val NOTE_OFF = 0x80
        private const val NOTE_ON = 0x90
        private const val CONTROL = 0xB0
        private const val SUSTAIN = 64
        private const val PEDAL_DOWN = 64
        private const val ALL_SOUND_OFF = 120
        private const val RESET_CONTROLLERS = 121
        private const val ALL_NOTES_OFF = 123
    }
}
