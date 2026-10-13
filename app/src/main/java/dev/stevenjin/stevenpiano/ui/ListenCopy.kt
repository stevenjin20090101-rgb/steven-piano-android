// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.audio.KeyResult
import dev.stevenjin.stevenpiano.ui.screens.keys.KeyboardGeometry
import java.util.Locale

/**
 * Listening and the key test in words (v1.23 — M55; DESIGN.md › v1.23 — M55): the tablet's System page, its banner, the
 * routes' refusals, and the web panel's `system.js`, which says the same (WebAssetsTest). Pure.
 */
object ListenCopy {
    const val TITLE = "Listen and test"
    const val LINE = "The tablet's microphone"

    // ---- The switch (Piano › System) ------------------------------------------------------------------------------
    const val SWITCH = "Listening from the panel"
    const val SWITCH_NOTE = "The web panel may hear this room live through the tablet's microphone. Off by default; nothing is recorded."
    const val NOT_ALLOWED = "Android didn't allow the microphone. Allow it for Steven Piano in Android's settings, then turn this on."

    // ---- The banner, on every screen while the microphone is on --------------------------------------------------------
    const val BANNER_PANEL = "Microphone on · listening from the web panel"
    const val BANNER_TEST = "Microphone on · testing every key"
    const val STOP = "Stop"

    // ---- The key test ---------------------------------------------------------------------------------------------------
    const val TEST = "Test every key"
    const val TEST_NOTE = "The piano strikes its 84 keys in turn, low to high, about 40 s; the microphone hears which ones didn't sound."
    const val STARTING = "Listening to the room…"
    const val CHECKING = "Checking the last keys…"
    const val NOT_TESTED = "Not tested yet"

    // ---- Refusals (the routes' 409s, the tablet's line) ---------------------------------------------------------------
    const val OFF = "Listening is off on the tablet."
    const val AWAY = "Open Steven Piano on the tablet to listen."
    const val BUSY = "Someone is listening already."
    const val MICROPHONE = "The tablet's microphone couldn't start."
    const val NOT_CONNECTED = "The piano isn't connected."
    const val PLAYING = "Stop the music first."
    const val UPDATING = "The piano is being updated. Try again once it has finished."
    const val TESTING = "Every key is being tested already."

    /** The panel's card while listening is off on the tablet: what it is, and where to turn it on. */
    const val OFF_HOW = "Listening is off on the tablet. To hear the room, turn on Listening from the panel on the tablet: Piano › System."

    // ---- How a test ended before its end --------------------------------------------------------------------------------
    const val STOPPED = "Stopped before the end."
    const val STOPPED_DISCONNECTED = "Stopped: the piano disconnected."
    const val STOPPED_PLAYING = "Stopped: a piece started playing."
    const val STOPPED_QUIET = "Stopped: a quiet time began."
    const val STOPPED_UPDATING = "Stopped: the piano's firmware is being updated."
    const val STOPPED_MICROPHONE = "Stopped: the tablet's microphone stopped."

    /** "Key 37 of 84". */
    fun progress(done: Int, total: Int): String = "Key $done of $total"

    /** "All 84 keys sounded", or "3 keys didn't sound: C2, F♯4, A6" ("1 key didn't sound: C2"). */
    fun result(results: List<KeyResult>): String {
        val missing = results.filterNot { it.heard }.sortedBy { it.key }
        if (missing.isEmpty()) return "All ${results.size} keys sounded"
        val names = missing.joinToString(", ") { KeyboardGeometry.name(it.key) }
        return if (missing.size == 1) "1 key didn't sound: $names" else "${missing.size} keys didn't sound: $names"
    }

    /** "4:12 left" of a hearing, from [ms] left. */
    fun left(ms: Long): String {
        val seconds = ((ms.coerceAtLeast(0) + 999) / 1000)
        return String.format(Locale.ROOT, "%d:%02d left", seconds / 60, seconds % 60)
    }
}
