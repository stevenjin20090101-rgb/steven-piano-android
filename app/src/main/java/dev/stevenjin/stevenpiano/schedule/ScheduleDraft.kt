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
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * What a schedule may be, for the app's editor and the web panel's alike: at least one day, a start
 * and an end that are times of day and not the same (an end not after the start is past midnight),
 * something to play (a channel's key, or a playlist's or a piece's id), a volume from 0 to 100 %
 * or none, and at most [MAX_SCHEDULES] of them. Each problem reads as the sentence the editor shows.
 */
object ScheduleRules {
    const val MAX_SCHEDULES = 50
    val DAYS = 1..Occurrences.ALL_DAYS
    val MINUTES = 0 until Occurrences.MINUTES_PER_DAY
    val VOLUME = 0..100

    /** A channel's key as `assets/channels.json` names them ("calm"). */
    val CHANNEL_KEY = Regex("[a-z0-9_-]{1,40}")

    /** A playlist's or a piece's id, as the target column keeps it: a whole number above 0. */
    val ID = Regex("[1-9][0-9]{0,17}")

    const val NO_DAY = "Choose at least one day."
    const val NO_TARGET = "Choose what to play."
    const val SAME_TIMES = "The end must differ from the start."

    fun timesProblem(days: Int, startMinute: Int, endMinute: Int?): String? = when {
        days !in DAYS -> NO_DAY
        startMinute !in MINUTES -> "The start must be a time of day."
        endMinute != null && endMinute !in MINUTES -> "The end must be a time of day."
        endMinute == startMinute -> SAME_TIMES
        else -> null
    }

    fun targetProblem(kind: ScheduleKind?, target: String?): String? = when {
        kind == null || target.isNullOrEmpty() -> NO_TARGET
        kind == ScheduleKind.CHANNEL && !CHANNEL_KEY.matches(target) -> "That isn't a channel."
        kind != ScheduleKind.CHANNEL && !ID.matches(target) -> "That isn't a playlist or a piece."
        else -> null
    }

    fun volumeProblem(volumePct: Int?): String? = if (volumePct != null && volumePct !in VOLUME) "The volume runs from 0 to 100%." else null

    /** Why these fields can't be a schedule, in words; null when they can. */
    fun problem(days: Int, startMinute: Int, kind: ScheduleKind?, target: String?, endMinute: Int?, volumePct: Int?): String? =
        timesProblem(days, startMinute, endMinute) ?: targetProblem(kind, target) ?: volumeProblem(volumePct)
}

/**
 * A schedule being made or edited (the editor sheet, the web panel): [id] 0 for a new one. It is
 * saved only without a [problem]; [kind] and [target] are null until something to play is chosen,
 * [endMinute] null plays until the end, [volumePct] null leaves the loudness as it is (a channel
 * then plays at its own volume).
 */
data class ScheduleDraft(
    val id: Long = 0,
    val days: Int = Occurrences.WEEKDAYS,
    val startMinute: Int = DEFAULT_START,
    val kind: ScheduleKind? = null,
    val target: String? = null,
    val endMinute: Int? = null,
    val volumePct: Int? = DEFAULT_VOLUME,
    val enabled: Boolean = true,
) {
    /** What keeps it from being saved, as the editor says it; null when it can be. */
    val problem: String? get() = ScheduleRules.problem(days, startMinute, kind, target, endMinute, volumePct)

    val isNew: Boolean get() = id == 0L

    /** [day] on or off. */
    fun toggle(day: DayOfWeek): ScheduleDraft = copy(days = days xor Occurrences.dayBit(day))

    /** The row it becomes; call only without a [problem]. [createdAt] stays a saved schedule's own. */
    fun toEntity(createdAt: Long): ScheduleEntity {
        check(problem == null) { "A schedule with a problem can't be saved: $problem" }
        return ScheduleEntity(id, days, startMinute, kind!!, target!!, endMinute, volumePct, enabled, createdAt)
    }

    companion object {
        /** 12:00. */
        const val DEFAULT_START = 12 * Occurrences.MINUTES_PER_HOUR

        /** A schedule's volume until the person moves it: a channel's own default. */
        const val DEFAULT_VOLUME = 70

        /** How long a new schedule plays until the person says otherwise. */
        const val DEFAULT_LENGTH_MINUTES = 60

        fun of(entity: ScheduleEntity): ScheduleDraft = ScheduleDraft(
            entity.id,
            entity.days,
            entity.startMinute,
            entity.kind,
            entity.target,
            entity.endMinute,
            entity.volumePct,
            entity.enabled,
        )

        /**
         * A new schedule: weekdays, from the next whole hour after [now] for an hour, playing [kind]
         * [target] when given (a channel's card), at [volumePct].
         */
        fun fresh(now: LocalTime, kind: ScheduleKind? = null, target: String? = null, volumePct: Int? = DEFAULT_VOLUME): ScheduleDraft {
            val start = (now.hour + 1) % HOURS_PER_DAY * Occurrences.MINUTES_PER_HOUR
            val end = (start + DEFAULT_LENGTH_MINUTES) % Occurrences.MINUTES_PER_DAY
            return ScheduleDraft(startMinute = start, endMinute = end, kind = kind, target = target, volumePct = volumePct)
        }

        private const val HOURS_PER_DAY = 24
    }
}
