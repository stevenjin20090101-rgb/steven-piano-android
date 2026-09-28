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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Which moment of a schedule's run: its start, or its end (a schedule with an end time). */
enum class Edge { START, END }

/**
 * One moment a schedule acts: schedule [scheduleId]'s [edge] at [at], a local time in the zone it
 * was worked out in. [atMillis] is the same instant for the alarm clock.
 */
data class Occurrence(val scheduleId: Long, val edge: Edge, val at: ZonedDateTime) {
    val atMillis: Long get() = at.toInstant().toEpochMilli()
}

/**
 * When schedules start and end (DESIGN.md › v1.5.2 — M19). Pure: every answer comes from the
 * schedules and a time given, in that time's zone, so the tests set the clock and the zone.
 *
 * - A schedule starts at [ScheduleEntity.startMinute] on each day its [ScheduleEntity.days] bit
 *   is set (Monday 1, Tuesday 2 … Sunday 64). An end time not after the start
 *   ([ScheduleEntity.endMinute]) is past midnight: the run ends the next day. No end time ("until
 *   the end") has no end here: what it started plays out.
 * - Local times go through [ZonedDateTime]: a start that falls in the hour the clocks skip in
 *   spring comes that much later (02:30 plays at 03:30), and one in the hour autumn repeats plays
 *   once, at the first of the two.
 * - Only enabled, well-formed schedules count ([ScheduleRules.problem]).
 */
object Occurrences {
    /** At the same instant an end comes before a start (a run ending as another begins), then by schedule. */
    private val ORDER = compareBy<Occurrence>({ it.at.toInstant() }, { if (it.edge == Edge.END) 0 else 1 }, { it.scheduleId })

    /** The day's bit in [ScheduleEntity.days]: Monday 1 … Sunday 64. */
    fun dayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    /**
     * What any enabled schedule does next, strictly after [now]: a start, or the end of a run going
     * on at [now]. Null when nothing will (no schedules, or none enabled). The planner's one alarm
     * is set for it.
     */
    fun next(entries: List<ScheduleEntity>, now: ZonedDateTime): Occurrence? =
        entries.asSequence()
            .filter(::counts)
            .flatMap { sequenceOf(startAfter(it, now), endOfRun(it, now)) }
            .filterNotNull()
            .minWithOrNull(ORDER)

    /** The next start of any enabled schedule strictly after [now]: the "Next: Wednesday 12:30, Calm" line. */
    fun nextStart(entries: List<ScheduleEntity>, now: ZonedDateTime): Occurrence? =
        entries.asSequence().filter(::counts).mapNotNull { startAfter(it, now) }.minWithOrNull(ORDER)

    /**
     * Everything due at exactly [at] (an alarm's time): the ends first, then the starts, each by
     * schedule. Two schedules may share a minute; the runner plays the first start and says so of the
     * others.
     */
    fun dueAt(entries: List<ScheduleEntity>, at: ZonedDateTime): List<Occurrence> {
        val instant = at.toInstant()
        val before = at.minusNanos(1_000_000L)
        return entries.asSequence()
            .filter(::counts)
            .flatMap { sequenceOf(endOfRun(it, before), startAfter(it, before)) }
            .filterNotNull()
            .filter { it.at.toInstant() == instant }
            .sortedWith(ORDER)
            .toList()
    }

    /** Schedule [entry]'s first start strictly after [now]; null when it has no day. */
    fun startAfter(entry: ScheduleEntity, now: ZonedDateTime): Occurrence? {
        if (entry.days and ALL_DAYS == 0) return null
        val time = timeOf(entry.startMinute)
        val today = now.toLocalDate()
        // A week and a day: today's time may be past, and the same weekday comes round again.
        for (ahead in 0L..DAYS_AHEAD) {
            val date = today.plusDays(ahead)
            if (entry.days and dayBit(date.dayOfWeek) == 0) continue
            val at = ZonedDateTime.of(date, time, now.zone)
            if (at.isAfter(now)) return Occurrence(entry.id, Edge.START, at)
        }
        return null
    }

    /**
     * Where the run of [entry] going on at [now] ends: null when none is (not started yet, ended
     * already, or no end time). A run lasts less than a day, so it began today or yesterday.
     */
    fun endOfRun(entry: ScheduleEntity, now: ZonedDateTime): Occurrence? {
        if (entry.endMinute == null) return null
        val today = now.toLocalDate()
        for (back in 0L..1L) {
            val date = today.minusDays(back)
            if (entry.days and dayBit(date.dayOfWeek) == 0) continue
            val start = ZonedDateTime.of(date, timeOf(entry.startMinute), now.zone)
            if (start.isAfter(now)) continue   // today's start is still ahead
            val end = endAt(entry, date, now.zone) ?: continue
            if (end.isAfter(now)) return Occurrence(entry.id, Edge.END, end)
        }
        return null
    }

    /** The end of the run that starts on [startDate]: that day at the end time, or the next day when it is not after the start. */
    fun endAt(entry: ScheduleEntity, startDate: LocalDate, zone: ZoneId): ZonedDateTime? {
        val end = entry.endMinute ?: return null
        val date = if (end > entry.startMinute) startDate else startDate.plusDays(1)
        return ZonedDateTime.of(date, timeOf(end), zone)
    }

    private fun counts(entry: ScheduleEntity): Boolean =
        entry.enabled && ScheduleRules.timesProblem(entry.days, entry.startMinute, entry.endMinute) == null

    private fun timeOf(minute: Int): LocalTime = LocalTime.of(minute / MINUTES_PER_HOUR, minute % MINUTES_PER_HOUR)

    /** Every day's bit: Monday to Sunday. */
    const val ALL_DAYS = 127

    /** Monday to Friday. */
    const val WEEKDAYS = 31

    /** Saturday and Sunday. */
    const val WEEKENDS = 96

    const val MINUTES_PER_HOUR = 60
    const val MINUTES_PER_DAY = 1_440

    private const val DAYS_AHEAD = 7L
}
