// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.safety.Deferral
import dev.stevenjin.stevenpiano.safety.LastCheck
import dev.stevenjin.stevenpiano.safety.Mark
import dev.stevenjin.stevenpiano.safety.SafetyLock
import dev.stevenjin.stevenpiano.safety.SafetyStep
import dev.stevenjin.stevenpiano.safety.StepLine
import dev.stevenjin.stevenpiano.safety.Why
import dev.stevenjin.stevenpiano.ui.screens.keys.KeyboardGeometry
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The safety check in words (v1.26 — M61; DESIGN.md › v1.26 — M61): the card, the banner, the System page's Listen and
 * test card, Play's refusals on every path, and the web panel's capsule (which shows the words the state sends). Pure.
 */
object SafetyCopy {
    // ---- The card ---------------------------------------------------------------------------------------------------
    const val TITLE = "Safety check"
    const val LINE = "After every 6 pieces the piano checks its keys."
    const val PASSED = "Safety check passed"
    const val FAILED = "Safety check failed"
    const val STOPPED = "Safety check stopped"
    const val AGAIN = "It runs again before the next piece."
    const val OK = "OK"

    /** "Next: Clair de lune". */
    fun next(title: String): String = "Next: $title"

    // ---- The steps, as each line says it --------------------------------------------------------------------------------
    const val RELEASED = "Keys released"
    const val COILS = "No coil on"
    const val BOARDS = "Boards answering"
    const val KEYS = "Testing each key"
    const val KEYS_AGAIN = "Testing each key again"
    const val RESTART = "Restarting the piano"
    const val REPOWER = "Power off and on"

    // ---- Why a line was left out or failed, after its words ("Restarting the piano · not on this firmware") -----------
    const val NOT_REPORTED = "not reported by this piano"
    const val NO_ANSWER = "no answer"
    const val COIL_ON = "a key is still on"
    const val MICROPHONE_OFF = "microphone off"
    const val NOT_OFFERED = "not on this firmware"
    const val OTA_PENDING = "the new firmware hasn't confirmed itself yet"
    const val NOT_BACK = "the piano didn't come back"
    const val NO_PLUG = "no power plug"
    const val NOT_CONFIRMED = "not confirmed"

    // ---- Play locked ------------------------------------------------------------------------------------------------
    const val ASK = "Ask whoever looks after the piano to check these keys."
    const val ASK_COIL = "The power to the keys was cut. Ask whoever looks after the piano to check it."
    const val RUN = "Run the safety check"
    const val NOT_NOW = "Not now"
    const val SHOW = "Show"

    /** The capsule while the locked card is put aside: on every screen, until it is shown again. */
    const val PLAY_LOCKED = "Play is locked · safety check failed"

    // ---- The banner ---------------------------------------------------------------------------------------------------
    const val BANNER = "Microphone on · safety check"

    // ---- Piano › System › Listen and test ---------------------------------------------------------------------------
    const val SWITCH = "Automatic safety check (uses the microphone)"
    const val SWITCH_NOTE = "Every 6 pieces the piano strikes each key once while the tablet listens. Nothing is recorded or sent."
    const val NO_PERMISSION = "Android hasn't allowed the microphone, so the safety check runs without listening. Turn this on to allow it."
    const val NEVER = "No safety check yet"
    const val DUE = "A safety check runs before the next piece."

    /** "Safety check failed: C2, F♯4", or "Safety check failed: a key is still on": Play's refusal, everywhere. */
    fun locked(lock: SafetyLock): String = when (lock) {
        SafetyLock.CoilOn -> "$FAILED: $COIL_ON"
        is SafetyLock.KeysSilent -> if (lock.keys.isEmpty()) FAILED else "$FAILED: ${names(lock.keys)}"
    }

    /** Why a check can't run now, as Run the safety check and the panel's Play say it. */
    fun deferral(why: Deferral): String = when (why) {
        Deferral.UPDATING -> "The piano's firmware is being updated. The safety check runs once it has finished."
        Deferral.QUIET -> "It's a quiet time. The safety check runs once it ends."
        Deferral.AWAY -> "A safety check runs before the next piece. Open Steven Piano on the tablet."
        Deferral.GUEST_PLAY -> "Guest play is on. The safety check runs once it ends."
        Deferral.NO_PIANO -> "The piano isn't connected. Connect it, then run the safety check."
    }

    /** Why a check under way had to stop. */
    fun interrupted(why: Deferral): String = when (why) {
        Deferral.UPDATING -> "The piano's firmware began updating."
        Deferral.QUIET -> "A quiet time began."
        Deferral.AWAY -> "Steven Piano left the screen."
        Deferral.GUEST_PLAY -> "Guest play began."
        Deferral.NO_PIANO -> NOT_CONNECTED
    }

    const val NOT_CONNECTED = "The piano isn't connected."

    /** "C2, F♯4". */
    fun names(keys: List<Int>): String = keys.sorted().joinToString(", ") { KeyboardGeometry.name(it) }

    /**
     * What a line of the card says: its step's words, the figures while they count ("Testing each key (37 of 84)",
     * "Boards answering (5 of 7)"), and why it was left out or failed ("Restarting the piano · not on this firmware",
     * "Testing each key · C2, F♯4 didn't sound").
     */
    fun line(line: StepLine): String {
        val figures = when (line.step) {
            SafetyStep.BOARDS -> if (line.of > 0) " (${line.done} of ${line.of})" else ""
            SafetyStep.SWEEP, SafetyStep.SWEEP_AGAIN -> if (line.of > 0 && line.mark == Mark.RUNNING) " (${line.done} of ${line.of})" else ""
            else -> ""
        }
        val base = when (line.step) {
            SafetyStep.RELEASE -> RELEASED
            SafetyStep.COILS -> COILS
            SafetyStep.BOARDS -> BOARDS
            SafetyStep.SWEEP -> KEYS
            SafetyStep.SWEEP_AGAIN -> KEYS_AGAIN
            SafetyStep.RESTART -> RESTART
            SafetyStep.REPOWER -> REPOWER
        } + figures
        val why = when (line.why) {
            null -> null
            Why.NOT_REPORTED -> NOT_REPORTED
            Why.NO_ANSWER -> NO_ANSWER
            Why.COIL_ON -> COIL_ON
            Why.BOARDS_MISSING -> null   // the figures say it
            Why.MICROPHONE_OFF -> MICROPHONE_OFF
            Why.KEYS_SILENT -> silent(line.keys)
            Why.NOT_OFFERED -> NOT_OFFERED
            Why.OTA_PENDING -> OTA_PENDING
            Why.REFUSED -> line.said ?: "refused"
            Why.NOT_BACK -> NOT_BACK
            Why.NO_PLUG -> NO_PLUG
            Why.NOT_CONFIRMED -> NOT_CONFIRMED
            Why.NO_PIANO -> NOT_CONNECTED.removeSuffix(".").replaceFirstChar { it.lowercase() }
            Why.STOPPED -> line.said?.removeSuffix(".")?.replaceFirstChar { it.lowercase() }
        }
        return if (why == null) base else "$base · $why"
    }

    /** "C2 didn't sound", "C2, F♯4 didn't sound". */
    fun silent(keys: List<Int>): String = if (keys.isEmpty()) "keys didn't sound" else "${names(keys)} didn't sound"

    /**
     * The System page's line: "Last safety check: today 14:20 · all 84 keys", "… · keys not tested (microphone off)",
     * "… · C2, F♯4 didn't sound", "… · a key was still on", with "after a restart" when one was needed; [now] and [zone]
     * say what today is.
     */
    fun last(last: LastCheck, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val result = when {
            last.coilOn -> "a key was still on"
            !last.passed -> silent(last.silent)
            last.keys == null -> "keys not tested (microphone off)"
            else -> "all ${last.keys} keys"
        }
        val boards = if (last.boardsOk != null && last.boardsOf != null && last.boardsOk < last.boardsOf) ", ${last.boardsOk} of ${last.boardsOf} boards answering" else ""
        val after = if (last.recovered && last.passed) " after a restart" else ""
        return "Last safety check: ${whenSaid(last.at, now, zone)} · $result$boards$after"
    }

    /** "today 14:20", "yesterday 9:05", "Mon 14:20" within the week, else "21 Oct 14:20". */
    fun whenSaid(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val then = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone)
        val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), today)
        val time = then.format(TIME)
        return when (days) {
            0L -> "today $time"
            1L -> "yesterday $time"
            in 2L..6L -> then.format(WEEKDAY) + " " + time
            else -> then.format(DAY) + " " + time
        }
    }

    private val TIME = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT)
    private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
}
