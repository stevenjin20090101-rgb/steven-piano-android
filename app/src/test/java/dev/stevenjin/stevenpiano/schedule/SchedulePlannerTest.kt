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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The one alarm: set for what comes next, set again after every change and every alarm, cancelled
 * when nothing is ahead or exact alarms are refused, and moved when the time zone changes.
 */
class SchedulePlannerTest {
    private var zone: ZoneId = ZoneId.of("America/New_York")
    private var now: ZonedDateTime = at("2026-09-28", "08:00")   // a Monday
    private val entries = mutableListOf<ScheduleEntity>()
    private val alarms = FakeAlarmScheduler()
    private val planner = SchedulePlanner({ entries.toList() }, alarms, { zone }, { now.toInstant().toEpochMilli() })

    private fun at(date: String, time: String): ZonedDateTime = ZonedDateTime.of(LocalDateTime.parse("${date}T$time"), zone)

    private fun entry(id: Long, days: Int, start: Int, end: Int? = null, enabled: Boolean = true) =
        ScheduleEntity(id, days, start, ScheduleKind.CHANNEL, "calm", end, 70, enabled, createdAt = 0)

    @Test
    fun `the one alarm is set for the next start`() = runBlocking {
        entries += entry(1, Occurrences.WEEKDAYS, 750, 795)
        val planned = planner.replan()
        assertEquals(Occurrence(1, Edge.START, at("2026-09-28", "12:30")), planned)
        assertEquals(at("2026-09-28", "12:30").toInstant().toEpochMilli() to planned, alarms.alarm)
        assertEquals(planned, planner.planned.value)
        assertTrue(planner.exactAllowed.value)
    }

    @Test
    fun `while a schedule runs the alarm is its end, and after the end the next start`() = runBlocking {
        entries += entry(1, Occurrences.WEEKDAYS, 750, 795)
        now = at("2026-09-28", "12:30")
        assertEquals(Occurrence(1, Edge.END, at("2026-09-28", "13:15")), planner.replan())
        val end = at("2026-09-28", "13:15").toInstant().toEpochMilli()
        assertEquals("planned from the alarm's own minute", Occurrence(1, Edge.START, at("2026-09-29", "12:30")), planner.replan(afterMillis = end))
    }

    @Test
    fun `every change plans again, and with nothing ahead there is no alarm`() = runBlocking {
        entries += entry(1, Occurrences.WEEKDAYS, 750)
        planner.replan()
        entries += entry(2, Occurrences.ALL_DAYS, 600)
        assertEquals("a sooner one takes the alarm", 2L, planner.replan()!!.scheduleId)
        entries[1] = entries[1].copy(enabled = false)
        assertEquals("turned off, it lets it go", 1L, planner.replan()!!.scheduleId)
        entries.clear()
        assertNull(planner.replan())
        assertNull(alarms.alarm)
        assertNull(planner.planned.value)
        assertEquals("cancel", alarms.calls.last())
    }

    @Test
    fun `after an alarm the next is planned from its minute, so a start sharing it is not planned twice`() = runBlocking {
        entries += entry(1, Occurrences.WEEKDAYS, 750)
        entries += entry(2, Occurrences.WEEKDAYS, 750, 800)
        val noon = at("2026-09-28", "12:30")
        assertEquals(listOf(1L, 2L), Occurrences.dueAt(entries, noon).map { it.scheduleId })
        now = noon.plusSeconds(2)   // the alarm is handled a moment after its minute
        assertEquals("schedule 2's end, not a start at 12:30 again", Occurrence(2, Edge.END, at("2026-09-28", "13:20")), planner.replan(afterMillis = noon.toInstant().toEpochMilli()))
    }

    @Test
    fun `without exact alarms nothing is planned and the page is told, and allowed again the alarm comes back`() = runBlocking {
        entries += entry(1, Occurrences.WEEKDAYS, 750)
        planner.replan()
        alarms.exact = false
        assertNull(planner.replan())
        assertNull(alarms.alarm)
        assertFalse(planner.exactAllowed.value)
        alarms.exact = true
        planner.recheckExact()
        assertTrue(planner.exactAllowed.value)
        assertEquals(1L, alarms.alarm!!.second.scheduleId)
        val calls = alarms.calls.size
        planner.recheckExact()
        assertEquals("nothing changed: nothing planned again", calls, alarms.calls.size)
    }

    @Test
    fun `a new time zone moves the alarm to the same local time there`() = runBlocking {
        entries += entry(1, Occurrences.ALL_DAYS, 720)
        val inNewYork = planner.replan()!!.atMillis
        zone = ZoneId.of("Europe/London")
        now = ZonedDateTime.ofInstant(now.toInstant(), zone)   // 13:00 in London: today's noon has gone
        val inLondon = planner.replan()!!
        assertEquals(12, inLondon.at.hour)
        assertEquals(LocalDateTime.parse("2026-09-29T12:00"), inLondon.at.toLocalDateTime())
        assertEquals("tomorrow's noon in London comes 19 hours after today's in New York", 19 * 3_600_000L, inLondon.atMillis - inNewYork)
    }

    /** The alarm clock as the planner sees it: one alarm at most, every call written down. */
    private class FakeAlarmScheduler : AlarmScheduler {
        var exact = true
        var alarm: Pair<Long, Occurrence>? = null
        val calls = mutableListOf<String>()

        override fun canScheduleExact(): Boolean = exact

        override fun schedule(atMillis: Long, occurrence: Occurrence) {
            alarm = atMillis to occurrence
            calls += "schedule ${occurrence.scheduleId} ${occurrence.edge} $atMillis"
        }

        override fun cancel() {
            alarm = null
            calls += "cancel"
        }
    }
}
