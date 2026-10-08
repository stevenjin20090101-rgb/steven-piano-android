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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale

/**
 * One block of a quiet time: from [start] to [end], minutes after midnight (0–1439). An end not after the start is the
 * next day's (21:00–7:00 runs into the next morning).
 */
data class QuietBlock(val start: Int, val end: Int) {
    /** How long it lasts, in minutes: 1 to 1439 for a block that can be saved, 0 when its end is its start. */
    val minutes: Int get() = Math.floorMod(end - start, Occurrences.MINUTES_PER_DAY)
}

/** A section of quiet times: its [name] ("School days"), its [days] (a bit a day, Monday 1 … Sunday 64), its [blocks]. */
data class QuietSection(val name: String, val days: Int, val blocks: List<QuietBlock>)

/** The quiet on at a moment: the start of the block that began last ([since]), and when the quiet ends ([until]). */
data class QuietSpell(val since: ZonedDateTime, val until: ZonedDateTime)

/**
 * Quiet times (DESIGN.md › v1.20 — M54), pure: sections of days, each with blocks, kept as one [ScheduleKind.QUIET]
 * row a block (its section's name as the target, its days, both minutes). Every answer comes from the rows and a time
 * given, in that time's zone, through [ZonedDateTime] as [Occurrences] works (a block in the hour the clocks skip
 * starts that much later; midnight is crossed into the next day).
 *
 * - [sections] builds the sections from the rows; [rows] the rows from sections; [validate] says what keeps sections
 *   from being saved, in the editor's words.
 * - [at] is the quiet on at a moment: the block that began last, and the end of the quiet, which runs on through any
 *   block (of any section) that starts before it ends or as it ends. [next] is the next block's start.
 */
object QuietTimes {
    const val MAX_SECTIONS = 12
    const val MAX_BLOCKS = 16
    const val MAX_NAME = 40

    /** What Add block leaves between the last block's end and the new one's start. */
    const val GAP_MINUTES = 10

    /** A new block's length when there is no block before it to take one from. */
    const val DEFAULT_LENGTH_MINUTES = 50

    const val TOO_MANY_SECTIONS = "There can be 12 sections at most."
    const val NO_NAME = "Give the section a name."
    const val LONG_NAME = "A section's name can be 40 characters at most."
    const val SAME_NAME = "Two sections can't have the same name."
    const val NO_DAY = "Choose at least one day."
    const val NO_BLOCK = "Add at least one block."
    const val TOO_MANY_BLOCKS = "A section can have 16 blocks at most."
    const val NOT_A_TIME = "A block's times must be times of day."
    const val SAME_TIMES = "A block's end must differ from its start."

    /** Days of blocks laid out around a moment: yesterday's (one may still run) to a week ahead (every weekday's next). */
    private const val DAYS_BEFORE = 1L
    private const val DAYS_AFTER = 7L

    private val MINUTES = 0 until Occurrences.MINUTES_PER_DAY

    fun isQuiet(row: ScheduleEntity): Boolean = row.kind == ScheduleKind.QUIET

    /**
     * The sections [rows] hold: the quiet rows grouped by name and days, in the order they were saved (by id), each
     * section's blocks by start. Rows of the other kinds, and quiet rows without an end, are none of a section's.
     */
    fun sections(rows: List<ScheduleEntity>): List<QuietSection> =
        rows.asSequence()
            .filter { isQuiet(it) && it.endMinute != null }
            .sortedBy { it.id }
            .groupBy { it.target to it.days }
            .map { (key, list) ->
                QuietSection(key.first, key.second, list.map { QuietBlock(it.startMinute, it.endMinute!!) }.sortedWith(BLOCK_ORDER))
            }

    /** The rows [sections] are kept as, a block each, section by section (their names trimmed); [createdAt] on each. */
    fun rows(sections: List<QuietSection>, createdAt: Long): List<ScheduleEntity> =
        sections.flatMap { section ->
            section.blocks.sortedWith(BLOCK_ORDER).map { block ->
                ScheduleEntity(
                    days = section.days,
                    startMinute = block.start,
                    kind = ScheduleKind.QUIET,
                    target = section.name.trim(),
                    endMinute = block.end,
                    volumePct = null,
                    enabled = true,
                    createdAt = createdAt,
                )
            }
        }

    /**
     * What keeps [sections] from being saved, as the editors say it; null when they can be: at most [MAX_SECTIONS], each
     * with a name (1 to [MAX_NAME] characters once trimmed, no two the same), at least one day, one to [MAX_BLOCKS] blocks
     * whose times are times of day and whose end is not their start, and no two of its blocks overlapping on any day
     * (one that crosses midnight included). Blocks of different sections may overlap: the quiet is their union.
     */
    fun validate(sections: List<QuietSection>): String? {
        if (sections.size > MAX_SECTIONS) return TOO_MANY_SECTIONS
        val names = HashSet<String>()
        for (section in sections) {
            val name = section.name.trim()
            if (name.isEmpty()) return NO_NAME
            if (name.length > MAX_NAME) return LONG_NAME
            if (name.any { it.isISOControl() }) return NO_NAME
            if (!names.add(name.lowercase(Locale.ROOT))) return SAME_NAME
            if (section.days !in 1..Occurrences.ALL_DAYS) return NO_DAY
            if (section.blocks.isEmpty()) return NO_BLOCK
            if (section.blocks.size > MAX_BLOCKS) return TOO_MANY_BLOCKS
            for (block in section.blocks) {
                if (block.start !in MINUTES || block.end !in MINUTES) return NOT_A_TIME
                if (block.start == block.end) return SAME_TIMES
            }
            overlap(section)?.let { return it }
        }
        return null
    }

    /**
     * Two blocks of [section] that meet on some day of its week, as the editors say it ("8:40–9:30 and 9:00–9:50
     * overlap."); null when none do. Each block is laid on the week once a day it runs (a minute 0 to 10 079 from Monday
     * 00:00), one crossing midnight into the next day, Sunday's into Monday's; one ending as another starts meets nothing.
     */
    private fun overlap(section: QuietSection): String? {
        class Piece(val from: Int, val to: Int, val block: Int)
        val week = Occurrences.MINUTES_PER_DAY * DAYS_IN_WEEK
        val pieces = ArrayList<Piece>()
        section.blocks.forEachIndexed { index, block ->
            for (day in 0 until DAYS_IN_WEEK) {
                if (section.days and (1 shl day) == 0) continue
                val from = day * Occurrences.MINUTES_PER_DAY + block.start
                val to = from + block.minutes
                if (to <= week) {
                    pieces += Piece(from, to, index)
                } else {
                    pieces += Piece(from, week, index)
                    pieces += Piece(0, to - week, index)
                }
            }
        }
        pieces.sortBy { it.from }
        for (i in pieces.indices) {
            for (j in i + 1 until pieces.size) {
                if (pieces[j].from >= pieces[i].to) break
                if (pieces[j].block != pieces[i].block) {
                    val (a, b) = listOf(section.blocks[pieces[i].block], section.blocks[pieces[j].block]).sortedWith(BLOCK_ORDER)
                    return "${QuietCopy.range(a)} and ${QuietCopy.range(b)} overlap."
                }
            }
        }
        return null
    }

    /**
     * The quiet on at [now]: null when no block is; else the start of the block that began last, and when the quiet
     * ends: the latest end of the blocks on, carried on through every block that starts before that end (or at it) and
     * goes on past it, whichever section it is in.
     */
    fun at(rows: List<ScheduleEntity>, now: ZonedDateTime): QuietSpell? {
        val spans = spans(rows, now)
        val on = spans.filter { !it.start.isAfter(now) && it.end.isAfter(now) }
        if (on.isEmpty()) return null
        val since = on.maxOf { it.start }
        var until = on.maxOf { it.end }
        var grew = true
        while (grew) {
            grew = false
            for (span in spans) {
                if (!span.start.isAfter(until) && span.end.isAfter(until)) {
                    until = span.end
                    grew = true
                }
            }
        }
        return QuietSpell(since, until)
    }

    /** The next start of any block strictly after [now]; null when there are no blocks. */
    fun next(rows: List<ScheduleEntity>, now: ZonedDateTime): ZonedDateTime? =
        spans(rows, now).asSequence().map { it.start }.filter { it.isAfter(now) }.minOrNull()

    /**
     * The block Add block proposes after [blocks]' last: starting [GAP_MINUTES] after its end, as long as it; with no
     * block yet, [start] for [DEFAULT_LENGTH_MINUTES]. Times wrap past midnight.
     */
    fun proposed(blocks: List<QuietBlock>, start: Int): QuietBlock {
        val last = blocks.lastOrNull() ?: return QuietBlock(start, (start + DEFAULT_LENGTH_MINUTES) % Occurrences.MINUTES_PER_DAY)
        val length = last.minutes.takeIf { it > 0 } ?: DEFAULT_LENGTH_MINUTES
        val from = (last.start + last.minutes + GAP_MINUTES) % Occurrences.MINUTES_PER_DAY
        return QuietBlock(from, (from + length) % Occurrences.MINUTES_PER_DAY)
    }

    /** Every quiet block's run from yesterday to a week ahead, in [now]'s zone. */
    private fun spans(rows: List<ScheduleEntity>, now: ZonedDateTime): List<Span> {
        val first = now.toLocalDate().minusDays(DAYS_BEFORE)
        val spans = ArrayList<Span>()
        for (row in rows) {
            val end = row.endMinute ?: continue
            if (!isQuiet(row) || row.days !in 1..Occurrences.ALL_DAYS || row.startMinute !in MINUTES || end !in MINUTES || end == row.startMinute) continue
            for (ahead in 0L..DAYS_BEFORE + DAYS_AFTER) {
                val date: LocalDate = first.plusDays(ahead)
                if (row.days and Occurrences.dayBit(date.dayOfWeek) == 0) continue
                val start = ZonedDateTime.of(date, timeOf(row.startMinute), now.zone)
                val stop = ZonedDateTime.of(if (end > row.startMinute) date else date.plusDays(1), timeOf(end), now.zone)
                if (stop.isAfter(start)) spans += Span(start, stop)
            }
        }
        return spans
    }

    private class Span(val start: ZonedDateTime, val end: ZonedDateTime)

    private fun timeOf(minute: Int): LocalTime = LocalTime.of(minute / Occurrences.MINUTES_PER_HOUR, minute % Occurrences.MINUTES_PER_HOUR)

    private val BLOCK_ORDER = compareBy<QuietBlock>({ it.start }, { it.end })

    private const val DAYS_IN_WEEK = 7
}
