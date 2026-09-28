// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.schedule

import dev.stevenjin.stevenpiano.data.db.ScheduleEntity
import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * When schedules start and end: the days as bits (Monday 1 … Sunday 64), a week ahead at most,
 * runs that cross midnight, the clocks changing for summer and back, "until the end", and what is
 * due at one alarm's minute.
 */
class OccurrencesTest {
    private val york = ZoneId.of("America/New_York")

    /** 28 September 2026 is a Monday. */
    private fun at(date: String, time: String, zone: ZoneId = york): ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.parse("${date}T$time"), zone)

    private fun minute(time: String): Int = time.substringBefore(':').toInt() * 60 + time.substringAfter(':').toInt()

    private fun entry(id: Long, days: Int, start: String, end: String? = null, enabled: Boolean = true) = ScheduleEntity(
        id = id,
        days = days,
        startMinute = minute(start),
        kind = ScheduleKind.CHANNEL,
        target = "calm",
        endMinute = end?.let(::minute),
        enabled = enabled,
        createdAt = 0,
    )

    private val mon = Occurrences.dayBit(DayOfWeek.MONDAY)
    private val wed = Occurrences.dayBit(DayOfWeek.WEDNESDAY)
    private val fri = Occurrences.dayBit(DayOfWeek.FRIDAY)
    private val sun = Occurrences.dayBit(DayOfWeek.SUNDAY)

    @Test
    fun `the days are bits, Monday 1 to Sunday 64, and a schedule starts on its days only`() {
        assertEquals(listOf(1, 2, 4, 8, 16, 32, 64), DayOfWeek.entries.map(Occurrences::dayBit))
        assertEquals(31, Occurrences.WEEKDAYS)
        assertEquals(96, Occurrences.WEEKENDS)
        val mwf = entry(1, mon or wed or fri, "12:30")
        assertEquals(at("2026-09-28", "12:30"), Occurrences.startAfter(mwf, at("2026-09-28", "08:00"))!!.at)
        assertEquals("Monday's has gone: Wednesday's", at("2026-09-30", "12:30"), Occurrences.startAfter(mwf, at("2026-09-28", "13:00"))!!.at)
        assertEquals("strictly after: at its own minute the next one is Monday", at("2026-10-05", "12:30"), Occurrences.startAfter(mwf, at("2026-10-02", "12:30"))!!.at)
        assertEquals(at("2026-10-04", "09:00"), Occurrences.startAfter(entry(2, sun, "09:00"), at("2026-09-28", "08:00"))!!.at)
        assertNull("no day, no start", Occurrences.startAfter(entry(3, 0, "09:00"), at("2026-09-28", "08:00")))
    }

    @Test
    fun `the same weekday comes round a week later`() {
        val mondays = entry(1, mon, "12:30")
        assertEquals(at("2026-10-05", "12:30"), Occurrences.next(listOf(mondays), at("2026-09-28", "12:31"))!!.at)
        val next = Occurrences.next(listOf(mondays), at("2026-09-28", "12:29"))!!
        assertEquals(Occurrence(1, Edge.START, at("2026-09-28", "12:30")), next)
        assertEquals(at("2026-09-28", "12:30").toInstant().toEpochMilli(), next.atMillis)
    }

    @Test
    fun `while a run goes on, what comes next is its end`() {
        val calm = entry(1, Occurrences.WEEKDAYS, "12:30", "13:15")
        assertEquals(Occurrence(1, Edge.END, at("2026-09-28", "13:15")), Occurrences.next(listOf(calm), at("2026-09-28", "12:30")))
        assertEquals(Occurrence(1, Edge.END, at("2026-09-28", "13:15")), Occurrences.next(listOf(calm), at("2026-09-28", "13:00")))
        assertEquals("after its end, the next start", Occurrence(1, Edge.START, at("2026-09-29", "12:30")), Occurrences.next(listOf(calm), at("2026-09-28", "13:15")))
        assertEquals("on a Friday the next start is Monday", at("2026-10-05", "12:30"), Occurrences.next(listOf(calm), at("2026-10-02", "14:00"))!!.at)
        assertNull("not started yet", Occurrences.endOfRun(calm, at("2026-09-28", "12:29")))
    }

    @Test
    fun `an end not after the start is past midnight, and the run crosses it`() {
        val late = entry(1, fri, "23:30", "00:30")
        assertEquals(Occurrence(1, Edge.END, at("2026-10-03", "00:30")), Occurrences.next(listOf(late), at("2026-10-02", "23:45")))
        assertEquals("Saturday isn't one of its days, but Friday's run is still going", Occurrence(1, Edge.END, at("2026-10-03", "00:30")), Occurrences.next(listOf(late), at("2026-10-03", "00:10")))
        assertEquals(Occurrence(1, Edge.START, at("2026-10-09", "23:30")), Occurrences.next(listOf(late), at("2026-10-03", "00:31")))
        val allNight = entry(2, Occurrences.ALL_DAYS, "12:00", "11:59")
        assertEquals("yesterday's run ends a minute before today's starts", Occurrence(2, Edge.END, at("2026-09-29", "11:59")), Occurrences.next(listOf(allNight), at("2026-09-29", "11:00")))
        assertEquals(Occurrence(2, Edge.START, at("2026-09-29", "12:00")), Occurrences.next(listOf(allNight), at("2026-09-29", "11:59")))
    }

    @Test
    fun `until the end has no end`() {
        val open = entry(1, Occurrences.ALL_DAYS, "07:45")
        assertNull(Occurrences.endOfRun(open, at("2026-09-28", "08:00")))
        assertEquals(Occurrence(1, Edge.START, at("2026-09-29", "07:45")), Occurrences.next(listOf(open), at("2026-09-28", "08:00")))
    }

    @Test
    fun `in spring a start in the skipped hour plays that much later, and a run across it is an hour shorter`() {
        // 8 March 2026: New York's clocks go from 02:00 to 03:00.
        val early = entry(1, sun, "02:30")
        val start = Occurrences.next(listOf(early), at("2026-03-07", "12:00"))!!.at
        assertEquals(LocalDateTime.parse("2026-03-08T03:30"), start.toLocalDateTime())
        assertEquals(ZoneOffset.ofHours(-4), start.offset)
        val night = entry(2, sun, "01:00", "03:00")
        val end = Occurrences.next(listOf(night), at("2026-03-08", "01:30"))!!
        assertEquals(Edge.END, end.edge)
        assertEquals(ZonedDateTime.of(LocalDateTime.parse("2026-03-08T03:00"), york), end.at)
        assertEquals("one hour of real time", 3_600_000L, end.atMillis - at("2026-03-08", "01:00").toInstant().toEpochMilli())
    }

    @Test
    fun `in autumn a start in the repeated hour plays once, at the first of the two`() {
        // 1 November 2026: New York's clocks go from 02:00 back to 01:00.
        val twice = entry(1, sun, "01:30")
        val first = Occurrences.next(listOf(twice), at("2026-10-31", "12:00"))!!.at
        assertEquals(LocalDateTime.parse("2026-11-01T01:30"), first.toLocalDateTime())
        assertEquals("the first 01:30, still summer time", ZoneOffset.ofHours(-4), first.offset)
        val after = Occurrences.next(listOf(twice), first)!!.at
        assertEquals("not again at the second 01:30: next Sunday", LocalDateTime.parse("2026-11-08T01:30"), after.toLocalDateTime())
        val fromSecond = Occurrences.next(listOf(twice), first.withLaterOffsetAtOverlap())!!.at
        assertEquals(LocalDateTime.parse("2026-11-08T01:30"), fromSecond.toLocalDateTime())
    }

    @Test
    fun `the zone decides the local time`() {
        val noon = entry(1, Occurrences.ALL_DAYS, "12:00")
        val london = ZoneId.of("Europe/London")
        val instantInYork = Occurrences.next(listOf(noon), at("2026-09-28", "08:00"))!!.atMillis
        val instantInLondon = Occurrences.next(listOf(noon), at("2026-09-28", "08:00", london))!!.atMillis
        assertEquals("five hours apart", 5 * 3_600_000L, instantInYork - instantInLondon)
    }

    @Test
    fun `disabled and malformed schedules count for nothing`() {
        val off = entry(1, Occurrences.ALL_DAYS, "09:00", enabled = false)
        val sameTimes = entry(2, Occurrences.ALL_DAYS, "10:00", "10:00")
        val noDays = entry(3, 0, "11:00")
        val badMinute = entry(4, Occurrences.ALL_DAYS, "09:00").copy(startMinute = 1_440)
        val tooManyDays = entry(5, 200, "09:00")
        assertNull(Occurrences.next(listOf(off, sameTimes, noDays, badMinute, tooManyDays), at("2026-09-28", "08:00")))
        assertEquals(6L, Occurrences.next(listOf(off, entry(6, mon, "12:00")), at("2026-09-28", "08:00"))!!.scheduleId)
    }

    @Test
    fun `an end comes before a start at the same moment, and the next start ignores ends`() {
        val first = entry(1, Occurrences.WEEKDAYS, "12:30", "13:15")
        val second = entry(2, Occurrences.WEEKDAYS, "13:15", "14:00")
        assertEquals(Occurrence(1, Edge.END, at("2026-09-28", "13:15")), Occurrences.next(listOf(second, first), at("2026-09-28", "13:00")))
        assertEquals(Occurrence(2, Edge.START, at("2026-09-28", "13:15")), Occurrences.nextStart(listOf(second, first), at("2026-09-28", "13:00")))
        val overlapping = entry(3, Occurrences.WEEKDAYS, "13:00")
        assertEquals("a start inside another's run comes first", Occurrence(3, Edge.START, at("2026-09-28", "13:00")), Occurrences.next(listOf(first, overlapping), at("2026-09-28", "12:40")))
    }

    @Test
    fun `what is due at an alarm's minute, the ends and then the starts by schedule, and nothing else`() {
        val ending = entry(5, Occurrences.WEEKDAYS, "12:30", "13:15")
        val later = entry(9, Occurrences.WEEKDAYS, "13:15")
        val sooner = entry(7, Occurrences.WEEKDAYS, "13:15", "14:00")
        val other = entry(3, Occurrences.WEEKDAYS, "13:16")
        assertEquals(
            listOf(
                Occurrence(5, Edge.END, at("2026-09-28", "13:15")),
                Occurrence(7, Edge.START, at("2026-09-28", "13:15")),
                Occurrence(9, Edge.START, at("2026-09-28", "13:15")),
            ),
            Occurrences.dueAt(listOf(later, other, ending, sooner), at("2026-09-28", "13:15")),
        )
        assertEquals(emptyList<Occurrence>(), Occurrences.dueAt(listOf(ending, later), at("2026-09-28", "13:14")))
        assertEquals("a day the schedule isn't on", emptyList<Occurrence>(), Occurrences.dueAt(listOf(later), at("2026-10-03", "13:15")))
    }
}
