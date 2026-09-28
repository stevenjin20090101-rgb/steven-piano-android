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

/** What a schedule plays. Stored by name. */
enum class ScheduleKind {
    /** A playlist; the target is its id. */
    PLAYLIST,

    /** A channel; the target is its key ("calm"). */
    CHANNEL,

    /** One piece; the target is its id. */
    PIECE,
}

/**
 * A time the piano plays by itself (DESIGN.md › v1.5, Schedules, M19): on [days] (a bit a day,
 * Monday 1, Tuesday 2 … Sunday 64) at [startMinute] (minutes after local midnight), [kind] and
 * [target] say what plays, until [endMinute] or the end of it (null), at [volumePct] or as the
 * piano is (null), while [enabled]. Created in schema v3 (app 1.5) so that 1.6 needs no second
 * migration; read and written from M19 (app 1.6.2) by `schedule.ScheduleRepository`.
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
