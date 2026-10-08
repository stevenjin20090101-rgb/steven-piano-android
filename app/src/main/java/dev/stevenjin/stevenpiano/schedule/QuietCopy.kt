// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * What quiet times say (DESIGN.md › v1.20 — M54), on the tablet and in the web panel's answers alike: "Quiet until
 * 9:30", "Quiet now · until 9:30", "Next quiet time 9:40" (a weekday before the time when it isn't today: "Next quiet
 * time Mon 8:40"), a block "8:40–9:30", the hub's "Quiet until 9:30" or "Next Mon 8:40", and the panel's refusal
 * "Quiet until 9:30. Use Play anyway." Times are the tablet's, on the 24-hour clock, the hour without a leading zero.
 * Pure.
 */
object QuietCopy {
    const val TITLE = "Quiet times"

    /** The Play anyway button, on Now playing, the resting screen, the Quiet times page and the panel. */
    const val PLAY_ANYWAY = "Play anyway"

    /** The hub's row, the Quiet times page's line and the System page's row with no quiet time set. */
    const val NONE = "None"
    const val NONE_SET = "No quiet times set"

    /** What the Quiet times page says of what quiet does, under its sections. */
    const val NOTE = "During a quiet time the piano stays silent: anything playing stops as a block begins, and nothing starts " +
        "until it ends. Play anyway lifts it until the block ends."

    /** "9:30", "13:05", "0:40". */
    fun clock(minute: Int): String = "%d:%02d".format(Locale.ROOT, minute / Occurrences.MINUTES_PER_HOUR, minute % Occurrences.MINUTES_PER_HOUR)

    fun clock(at: ZonedDateTime): String = "%d:%02d".format(Locale.ROOT, at.hour, at.minute)

    /** A block, "8:40–9:30". */
    fun range(block: QuietBlock): String = "${clock(block.start)}–${clock(block.end)}"

    /** When the quiet ends, "9:30"; a short weekday before it when that is a day or more away ("Tue 7:00"). */
    fun until(until: ZonedDateTime, now: ZonedDateTime): String =
        if (ChronoUnit.HOURS.between(now, until) >= HOURS_PER_DAY) "${ScheduleCopy.short(until.dayOfWeek)} ${clock(until)}" else clock(until)

    /** When the next block starts, "9:40" today, else with its short weekday, "Mon 8:40". */
    fun next(next: ZonedDateTime, now: ZonedDateTime): String =
        if (next.toLocalDate() == now.toLocalDate()) clock(next) else "${ScheduleCopy.short(next.dayOfWeek)} ${clock(next)}"

    /** The capsule on Now playing and the resting screen, "Quiet until 9:30". */
    fun capsule(until: String): String = "Quiet until $until"

    /**
     * The Quiet times page's line: "Quiet now · until 9:30", "Lifted until 9:30" while Play anyway holds, "Next quiet time
     * 9:40", or [NONE_SET].
     */
    fun status(quiet: QuietNow, now: ZonedDateTime): String {
        val until = quiet.until
        if (quiet.now && until != null) {
            val shown = until(at(until, now.zone), now)
            return if (quiet.overridden) "Lifted until $shown" else "Quiet now · until $shown"
        }
        val next = quiet.next ?: return NONE_SET
        return "Next quiet time ${next(at(next, now.zone), now)}"
    }

    /** The hub's row: "Quiet until 9:30" while quiet (lifted or not), "Next Mon 8:40" (or "Next 9:40" today), else [NONE]. */
    fun hub(quiet: QuietNow, now: ZonedDateTime): String {
        val until = quiet.until
        if (quiet.now && until != null) return capsule(until(at(until, now.zone), now))
        val next = quiet.next ?: return NONE
        return "Next ${next(at(next, now.zone), now)}"
    }

    /** The panel's answer to a play while quiet: "Quiet until 9:30. Use Play anyway." */
    fun refusal(until: String): String = "Quiet until $until. Use $PLAY_ANYWAY."

    /** The tablet's question when a play is asked for during a quiet time, under "Quiet until 9:30". */
    fun choice(until: String): String = "The piano is resting for a quiet time. $PLAY_ANYWAY lifts it until $until."

    /** An epoch-ms moment in [zone]. */
    fun at(millis: Long, zone: ZoneId): ZonedDateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    private const val HOURS_PER_DAY = 24L
}
