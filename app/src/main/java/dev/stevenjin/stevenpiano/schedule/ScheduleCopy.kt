// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * How days and times read (DESIGN.md › v1.6.2 — M19), on the tablet and in the web panel alike: "Weekdays",
 * "Mon, Wed, Fri", "Wednesday 12:30". Times here are on the 24-hour clock with two-digit hours, as the logs keep them;
 * quiet times' own words are [QuietCopy]'s. The timed plays' lines went with them in 1.20 (M54). Pure.
 */
object ScheduleCopy {
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
}
