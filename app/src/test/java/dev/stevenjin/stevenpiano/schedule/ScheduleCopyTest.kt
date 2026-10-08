// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** How days and times read, on the tablet and in the web panel: the design's own lines. */
class ScheduleCopyTest {
    private val wednesday = ZonedDateTime.of(LocalDateTime.parse("2026-09-30T12:30"), ZoneId.of("America/New_York"))

    @Test
    fun `the days read as the design writes them`() {
        assertEquals("Every day", ScheduleCopy.days(127))
        assertEquals("Weekdays", ScheduleCopy.days(31))
        assertEquals("Weekends", ScheduleCopy.days(96))
        assertEquals("Wednesdays", ScheduleCopy.days(Occurrences.dayBit(DayOfWeek.WEDNESDAY)))
        assertEquals("Mon, Wed, Fri", ScheduleCopy.days(1 or 4 or 16))
        assertEquals("in week order, Monday first", "Mon, Sun", ScheduleCopy.days(64 or 1))
        assertEquals("No days", ScheduleCopy.days(0))
    }

    @Test
    fun `times are the tablet's, on the 24-hour clock`() {
        assertEquals("00:00", ScheduleCopy.clock(0))
        assertEquals("09:05", ScheduleCopy.clock(545))
        assertEquals("23:59", ScheduleCopy.clock(1_439))
        assertEquals("12:30", ScheduleCopy.clock(wednesday))
        assertEquals("Wednesday 12:30", ScheduleCopy.moment(wednesday))
    }
}
