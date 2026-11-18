// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.audio.StepsResult
import dev.stevenjin.stevenpiano.ui.screens.keys.KeyboardGeometry

/**
 * The loudness steps in words (M83, piano firmware 2.1.5): Piano › Sound and touch's section, its confirm, the full-screen
 * card, the microphone's banner, and every refusal and stop. Plain and short. Pure.
 */
object StepsCopy {
    // ---- The section -------------------------------------------------------------------------------------------------
    const val SECTION = "Loudness steps"
    const val STATUS = "Calibration"
    const val FIND = "Find the loudness steps"
    const val NOT_CALIBRATED = "Not calibrated yet"
    const val FIND_NOTE = "The tablet listens while the piano strikes each key, then gives each key its own steps."

    /** While Full power is on, the steps don't play: it strikes every note at full strength. */
    const val FULL_POWER_ON = "Full power is on, so every note strikes at full strength and the steps aren't heard."

    /** "5 steps · 82 of 84 keys calibrated", "Smooth · 82 of 84 keys calibrated", or "Not calibrated yet". */
    fun status(velsteps: Int?, calibrated: Int?, of: Int): String {
        if (calibrated == null || calibrated <= 0) return NOT_CALIBRATED
        val steps = when (velsteps) {
            null -> null
            0 -> "Smooth"
            else -> "$velsteps steps"
        }
        return listOfNotNull(steps, "${calibrated.coerceAtMost(of)} of $of keys calibrated").joinToString(" · ")
    }

    // ---- The confirm --------------------------------------------------------------------------------------------------
    const val CONFIRM_TITLE = "Find the loudness steps?"
    const val CONFIRM_TEXT =
        "It needs a quiet room and takes 10 to 20 minutes. The piano strikes every key many times, softly to hard, while " +
            "the tablet listens. Then each key gets its own steps, and Full power goes off."
    const val START = "Start"
    const val NOT_NOW = "Not now"

    // ---- The card -----------------------------------------------------------------------------------------------------
    const val TITLE = "Finding the loudness steps"
    const val LINE = "Keep the room quiet. The piano strikes each key softly to hard while the tablet listens."
    const val RAIL = "Turning the piano's power on…"
    const val ROOM = "Listening to the room…"
    const val WRITING = "Writing the steps to the piano…"
    const val STOP = "Stop"
    const val OK = "OK"
    const val FOUND = "Loudness steps found"
    const val STOPPED_TITLE = "Loudness steps stopped"
    const val NOTHING_WRITTEN = "Nothing was written. The piano plays as before."

    /** The banner while the microphone hears for it. */
    const val BANNER = "Microphone on · finding the loudness steps"

    /** "C4 · key 37 of 84". */
    fun key(key: Int, number: Int, of: Int): String = "${KeyboardGeometry.name(key)} · key $number of $of"

    /** "About 6 min left", "Under a minute left". */
    fun left(ms: Long): String {
        val minutes = (ms + MINUTE_MS - 1) / MINUTE_MS
        return if (ms < MINUTE_MS) "Under a minute left" else "About $minutes min left"
    }

    /** "80 keys: 5 steps · 2 keys: 4 steps", most steps first; each part kept on one line (no-break spaces). */
    fun summary(result: StepsResult): String =
        result.counts.entries.sortedByDescending { it.key }.joinToString(" · ") { (steps, keys) -> "${keys(keys)}: $steps steps".replace(' ', NO_BREAK) }

    /**
     * "Not calibrated: C1, F♯7. They spread their steps between the floor and the ceiling." and (M83b) which of them the
     * room was too loud for: "The room was too loud for F♯7."
     */
    fun uncalibrated(keys: List<Int>, tooLoud: List<Int> = emptyList()): String? {
        if (keys.isEmpty()) return null
        val loud = tooLoud.filter { it in keys }
        return "Not calibrated: " + names(keys) + ". " +
            (if (keys.size == 1) "It spreads its" else "They spread their") + " steps between the floor and the ceiling." +
            (if (loud.isEmpty()) "" else " The room was too loud for " + names(loud) + ".")
    }

    private fun names(keys: List<Int>): String = keys.joinToString(", ") { KeyboardGeometry.name(it) }

    /** Stopped while writing: [written] keys had changed already, and Full power had gone off ([fullPowerOff]) or not. */
    fun partly(written: Int, fullPowerOff: Boolean): String =
        "${keys(written)} had changed when it stopped. " + if (fullPowerOff) "Full power is off." else "Full power was left as it was."

    private fun keys(n: Int): String = if (n == 1) "1 key" else "$n keys"

    // ---- Refused at the start -----------------------------------------------------------------------------------------
    const val NOT_CONNECTED = "The piano isn't connected."
    const val NO_STEPS = "This piano's firmware has no loudness steps. It needs 2.1.5 or later."
    const val READ_ONLY = "The piano doesn't let this tablet change it."
    const val PLAYING = "Pause the music first."
    const val GUEST = "Guest play is on. End it first."
    const val UPDATING = "The piano is being updated. Try again once it has finished."
    const val AWAY = "Open Steven Piano on the tablet first: the microphone listens only then."
    const val RUNNING = "The loudness steps are being found already."

    /** The key test or the safety check has the microphone. */
    const val BUSY = "Every key is being tested. Try again once it has finished."

    // ---- Why it stopped -----------------------------------------------------------------------------------------------
    const val STOPPED = "Stopped before the end."
    const val STOPPED_DISCONNECTED = "Stopped: the piano disconnected."
    const val STOPPED_PLAYING = "Stopped: a piece started playing."
    const val STOPPED_QUIET = "Stopped: a quiet time began."
    const val STOPPED_GUEST = "Stopped: guest play began."
    const val STOPPED_UPDATING = "Stopped: the piano's firmware is being updated."
    const val STOPPED_AWAY = "Stopped: the app left the screen."
    const val STOPPED_MICROPHONE = "Stopped: the tablet's microphone stopped."
    const val STOPPED_PLUG = "Stopped: the power plug can't be trusted now."
    const val STOPPED_RAIL = "Stopped: the 24 V isn't on."
    const val STOPPED_SILENT = "Stopped: the piano didn't answer."
    const val STOPPED_TOO_SOON = "Stopped: the piano kept saying the strikes came too soon."
    const val TOO_LOUD = "The room is too loud to tell the strikes apart. Try again when it's quiet."
    const val NONE_FOUND = "No key could be calibrated: the microphone didn't hear clear steps. Nothing was written."

    /** The piano answered something the spec doesn't say: its words. */
    fun refused(text: String): String = "Stopped: the piano said “$text”."

    private const val MINUTE_MS = 60_000L
    private const val NO_BREAK = '\u00A0'
}
