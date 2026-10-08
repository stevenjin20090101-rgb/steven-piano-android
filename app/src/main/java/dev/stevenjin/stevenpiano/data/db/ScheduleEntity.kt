// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What a schedule is. Stored by name (Room's own enum converter, the column TEXT), so a kind added later needs no
 * migration: QUIET (v1.20 — M54) came without one.
 */
enum class ScheduleKind {
    /** A playlist; the target is its id. Timed plays were removed in 1.20: kept, never run. */
    PLAYLIST,

    /** A channel; the target is its key ("calm"). Kept, never run (1.20). */
    CHANNEL,

    /** One piece; the target is its id. Kept, never run (1.20). */
    PIECE,

    /**
     * One block of a quiet time (v1.20 — M54, `schedule.QuietTimes`): the target is its section's name, [ScheduleEntity.days]
     * the section's days, both minutes required, no volume. The piano stays silent from its start to its end.
     */
    QUIET,
}

/**
 * A row of the schedules table (DESIGN.md › v1.5, Schedules, M19): on [days] (a bit a day, Monday 1, Tuesday 2 …
 * Sunday 64) at [startMinute] (minutes after local midnight) until [endMinute] (an end not after the start is the next
 * day). Until 1.20 a time the piano played by itself, [kind] and [target] saying what, at [volumePct] or as the piano
 * was (null), while [enabled]; those rows stay in the table, unshown and never run. Since 1.20 (M54) the only rows
 * written are quiet times' blocks ([ScheduleKind.QUIET]). Created in schema v3 (app 1.5) so that 1.6 needed no second
 * migration.
 */
@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val days: Int,
    val startMinute: Int,
    val kind: ScheduleKind,
    val target: String,
    val endMinute: Int? = null,
    val volumePct: Int? = null,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    val createdAt: Long,
)
