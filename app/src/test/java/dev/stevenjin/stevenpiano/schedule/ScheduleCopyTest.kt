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
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** What the schedules say, on the tablet and in the web panel: the design's own lines. */
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
    fun `a row reads its days and start over what it plays, until when and how loud`() {
        assertEquals("Weekdays 12:30", ScheduleCopy.whenLine(31, 750))
        assertEquals("Every day 07:05", ScheduleCopy.whenLine(127, 425))
        assertEquals("Calm channel · until 13:15 · 70%", ScheduleCopy.whatLine(ScheduleKind.CHANNEL, "Calm", 795, 70))
        assertEquals("Evening · until the end", ScheduleCopy.whatLine(ScheduleKind.PLAYLIST, "Evening", null, null))
        assertEquals("Clair de lune · until 00:30 · 0%", ScheduleCopy.whatLine(ScheduleKind.PIECE, "Clair de lune", 30, 0))
    }

    @Test
    fun `the next start, the hub's value, and what the last one did`() {
        assertEquals("Next: Wednesday 12:30, Calm", ScheduleCopy.next(wednesday, "Calm"))
        assertEquals("Next Wed 12:30", ScheduleCopy.hub(wednesday))
        assertEquals("None", ScheduleCopy.hub(null))
        assertEquals("Missed: Wednesday 12:30 (piano not connected)", ScheduleCopy.missed(wednesday, ScheduleCopy.NO_PIANO))
        assertEquals("Last: Wednesday 12:30, Calm channel", ScheduleCopy.played(wednesday, ScheduleKind.CHANNEL, "Calm"))
        assertEquals("Last: Wednesday 12:30, Evening", ScheduleCopy.played(wednesday, ScheduleKind.PLAYLIST, "Evening"))
    }

    @Test
    fun `times are the tablet's, on the 24-hour clock`() {
        assertEquals("00:00", ScheduleCopy.clock(0))
        assertEquals("09:05", ScheduleCopy.clock(545))
        assertEquals("23:59", ScheduleCopy.clock(1_439))
        assertEquals("12:30", ScheduleCopy.clock(wednesday))
        assertEquals("Calm", ScheduleCopy.channelFallback("calm"))
    }
}
