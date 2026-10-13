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
import java.time.format.TextStyle
import java.util.Locale

/**
 * How days read (DESIGN.md › v1.6.2 — M19), on the tablet and in the web panel alike: "Weekdays", "Mon, Wed, Fri";
 * and the web panel's wire time, "08:40". Quiet times' own words are [QuietCopy]'s. The timed plays' lines went with
 * them in 1.20 (M54). Pure.
 */
object ScheduleCopy {
    /** "Every day", "Weekdays", "Weekends", "Wednesdays" for one day, else the days in week order: "Mon, Wed, Fri". */
    fun days(mask: Int): String {
        when (mask and QuietTimes.ALL_DAYS) {
            QuietTimes.ALL_DAYS -> return "Every day"
            QuietTimes.WEEKDAYS -> return "Weekdays"
            QuietTimes.WEEKENDS -> return "Weekends"
            0 -> return "No days"
        }
        val chosen = DayOfWeek.entries.filter { mask and QuietTimes.dayBit(it) != 0 }
        return if (chosen.size == 1) full(chosen.single()) + "s" else chosen.joinToString(", ") { short(it) }
    }

    /** "07:45", "12:30", "23:05": the web panel's quiet times on the wire. */
    fun clock(minute: Int): String = "%02d:%02d".format(Locale.ROOT, minute / QuietTimes.MINUTES_PER_HOUR, minute % QuietTimes.MINUTES_PER_HOUR)

    /** "Wednesday". */
    fun full(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    /** "Wed". */
    fun short(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
}
