// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.admin

import dev.stevenjin.stevenpiano.web.LoginGuard

/**
 * The kiosk PIN's wrong tries (plan › M20): three are free, then each wrong one makes the next
 * wait 5 s, 10 s, 20 s… doubling to at most 5 min; the PIN sheet shows the wait. The schedule is the
 * web panel's [LoginGuard] with the kiosk's numbers and one key, the tablet's screen, so both PINs
 * are weighed by the same code: one try at a time, a try during a wait refused without being
 * counted, a right PIN starting the count again. [strikes] is what the settings keep after every
 * weighed try, and [restored] brings it back, so restarting the app, or the tablet, gives nobody
 * the free tries again. Thread-safe.
 */
class KioskPinGuard(private val clock: () -> Long = System::currentTimeMillis, restored: Strikes = Strikes.NONE) {
    /** Wrong tries in a row, and when the wait they earned ends (epoch ms; 0 while none runs). */
    data class Strikes(val count: Int, val lockedUntil: Long) {
        companion object {
            val NONE = Strikes(0, 0L)
        }
    }

    /** The time the guard sees while [restore] replays kept strikes; null the rest of the time. */
    @Volatile
    private var replayAt: Long? = null

    private val guard = schedule { replayAt ?: clock() }

    /** The count as it stands, for the settings to keep. */
    @Volatile
    var strikes: Strikes = Strikes.NONE
        private set

    init {
        restore(restored)
    }

    /** How long the screen must wait before a try is weighed, in milliseconds; 0 when it may try now. */
    fun waitMs(): Long = guard.waitMs(KEY)

    /** One try, weighed by [check] (the PIN's slow derivation) unless a wait runs. */
    @Synchronized
    fun attempt(check: () -> Boolean): LoginGuard.Attempt {
        val outcome = guard.attempt(KEY, check)
        when (outcome) {
            is LoginGuard.Attempt.Wrong -> strikes = Strikes(strikes.count + 1, if (outcome.waitMs > 0) clock() + outcome.waitMs else 0L)
            LoginGuard.Attempt.Right -> strikes = Strikes.NONE
            is LoginGuard.Attempt.Wait -> Unit
        }
        return outcome
    }

    /** A new PIN: the count starts again. */
    @Synchronized
    fun clear() {
        guard.succeeded(KEY)
        strikes = Strikes.NONE
    }

    /**
     * Puts [kept] back: the same number of wrong tries replayed into the guard, at the moment that
     * makes the last one's wait end when the kept one did (the wait each earned is the guard's own,
     * read from a scratch copy, so nothing here restates the schedule). A wait that ended while the
     * app was away is over, but the count stands: the next wrong try doubles on from it.
     */
    @Synchronized
    private fun restore(kept: Strikes) {
        val count = kept.count.coerceIn(0, MAX_KEPT)
        if (count == 0) return
        val scratch = schedule { 0L }
        var lastWait = 0L
        repeat(count) { lastWait = scratch.failed(KEY) }
        replayAt = if (lastWait > 0) kept.lockedUntil - lastWait else 0L
        try {
            repeat(count) { guard.failed(KEY) }
        } finally {
            replayAt = null
        }
        strikes = Strikes(count, if (lastWait > 0) kept.lockedUntil else 0L)
    }

    companion object {
        /** Wrong tries weighed at once before the first wait. */
        const val FREE_TRIES = 3

        /** The first wait, after the fourth wrong try in a row; each wrong try after it doubles the next. */
        const val FIRST_WAIT_MS = 5_000L

        /** The longest wait. */
        const val MAX_WAIT_MS = 5 * 60_000L

        /** Beyond this many wrong tries in a row the wait is long capped; a kept count is held to it. */
        private const val MAX_KEPT = 64

        /** The one key: whoever stands at the tablet. */
        private const val KEY = "screen"

        /** The kiosk's schedule on the shared guard: per key only (there is one), none of the web's gate for everyone. */
        private fun schedule(clock: () -> Long) = LoginGuard(
            clock = clock,
            threshold = FREE_TRIES + 1,
            firstLockMs = FIRST_WAIT_MS,
            maxLockMs = MAX_WAIT_MS,
            globalThreshold = Int.MAX_VALUE,
            globalFirstLockMs = FIRST_WAIT_MS,
            globalMaxLockMs = MAX_WAIT_MS,
            maxKeys = 1,
        )
    }
}
