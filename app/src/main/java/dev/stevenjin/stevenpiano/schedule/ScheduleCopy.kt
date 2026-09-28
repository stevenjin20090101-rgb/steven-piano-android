// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import dev.stevenjin.stevenpiano.ui.Format
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * What the schedules say (DESIGN.md › v1.5.2 — M19), on the tablet and in the web panel alike, so
 * both read the same: "Weekdays 12:30" over "Calm channel · until 13:15 · 70%", "Next: Wednesday
 * 12:30, Calm", the hub's "Next Wed 12:30", and what the last one did ("Missed: Wednesday 12:30
 * (piano not connected)"). Times are the tablet's, on the 24-hour clock with two-digit hours, as the
 * time pickers show them. Pure.
 */
object ScheduleCopy {
    /** The hub's Schedule row with nothing ahead. */
    const val NONE = "None"

    /** What a missed start says when the piano never came. */
    const val NO_PIANO = "piano not connected"

    /** The words after "until" for a schedule without an end time. */
    const val UNTIL_THE_END = "until the end"

    /** "Every day", "Weekdays", "Weekends", "Wednesdays" for one day, else the days in week order: "Mon, Wed, Fri". */
    fun days(mask: Int): String {
        when (mask and Occurrences.ALL_DAYS) {
            Occurrences.ALL_DAYS -> return "Every day"
            Occurrences.WEEKDAYS -> return "Weekdays"
            Occurrences.WEEKENDS -> return "Weekends"
            0 -> return "No days"
        }
        val chosen = DayOfWeek.entries.filter { mask and Occurrences.dayBit(it) != 0 }
        return if (chosen.size == 1) full(chosen.single()) + "s" else chosen.joinToString(", ") { short(it) }
    }

    /** "07:45", "12:30", "23:05". */
    fun clock(minute: Int): String = "%02d:%02d".format(Locale.ROOT, minute / Occurrences.MINUTES_PER_HOUR, minute % Occurrences.MINUTES_PER_HOUR)

    fun clock(at: ZonedDateTime): String = "%02d:%02d".format(Locale.ROOT, at.hour, at.minute)

    /** "Wednesday". */
    fun full(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    /** "Wed". */
    fun short(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

    /** "Wednesday 12:30". */
    fun moment(at: ZonedDateTime): String = "${full(at.dayOfWeek)} ${clock(at)}"

    /** A row's first line: its days and start, "Weekdays 12:30". */
    fun whenLine(days: Int, startMinute: Int): String = "${days(days)} ${clock(startMinute)}"

    /** What a schedule plays: "Calm channel", or the playlist's name, or the piece's title. */
    fun target(kind: ScheduleKind, name: String): String = if (kind == ScheduleKind.CHANNEL) "$name channel" else name

    /** A row's second line: what it plays, until when, and how loud: "Calm channel · until 13:15 · 70%". */
    fun whatLine(kind: ScheduleKind, name: String, endMinute: Int?, volumePct: Int?): String =
        listOfNotNull(
            target(kind, name),
            endMinute?.let { "until ${clock(it)}" } ?: UNTIL_THE_END,
            volumePct?.let(Format::percent),
        ).joinToString(" · ")

    /** The next start, at the top of the Schedule page and on Now playing with nothing loaded: "Next: Wednesday 12:30, Calm". */
    fun next(at: ZonedDateTime, name: String): String = "Next: ${moment(at)}, $name"

    /** The hub's value: "Next Wed 12:30", or [NONE]. */
    fun hub(at: ZonedDateTime?): String = at?.let { "Next ${short(it.dayOfWeek)} ${clock(it)}" } ?: NONE

    /** A start that could not play, for the link's trail and the page's last line: "Missed: Wednesday 12:30 (piano not connected)". */
    fun missed(at: ZonedDateTime, reason: String): String = "Missed: ${moment(at)} ($reason)"

    /** A start that played, for the page's last line: "Last: Wednesday 12:30, Calm channel". */
    fun played(at: ZonedDateTime, kind: ScheduleKind, name: String): String = "Last: ${moment(at)}, ${target(kind, name)}"

    /**
     * The last line as the page shows it: [line], recorded at [atMillis], while it is less than
     * [LAST_SHOWN_MS] old at [nowMillis], so its weekday ("Missed: Monday 10:32 …") can only mean the
     * last one; null after that, or when there is none.
     */
    fun recent(line: String?, atMillis: Long?, nowMillis: Long): String? =
        line?.takeIf { atMillis != null && nowMillis - atMillis in 0 until LAST_SHOWN_MS }

    /** Six days: a weekday named in the last line is always the last such day. */
    const val LAST_SHOWN_MS = 6 * 24 * 60 * 60 * 1_000L

    /** A channel's name where the channels are not known (yet): its key, capitalised ("calm" → "Calm"). */
    fun channelFallback(key: String): String = key.replaceFirstChar { it.titlecase(Locale.ENGLISH) }

    /** A schedule's playlist that has since been deleted. */
    const val GONE_PLAYLIST = "A deleted playlist"

    /** A schedule's piece that has since been deleted. */
    const val GONE_PIECE = "A deleted piece"
}
