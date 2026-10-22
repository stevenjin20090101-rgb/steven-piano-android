// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What a row of the schedules table is (`ScheduleKind` until v1.27 — M63; the table, its columns and the stored names
 * stay). Stored by name (Room's own enum converter, the column TEXT), so a kind added later needs no migration: QUIET
 * (v1.20 — M54) came without one. The timed plays' kinds (PLAYLIST, CHANNEL, PIECE), never run since 1.20, went with
 * their rows in schema v6 (v1.24 — M57).
 */
enum class QuietKind {
    /**
     * One block of a quiet time (v1.20 — M54, `quiet.QuietTimes`): the target is its section's name, [QuietEntity.days]
     * the section's days, both minutes required, no volume. The piano stays silent from its start to its end.
     */
    QUIET,
}

/**
 * A row of the schedules table (DESIGN.md › v1.5, Schedules, M19; `ScheduleEntity` until v1.27 — M63): on [days] (a bit
 * a day, Monday 1, Tuesday 2 … Sunday 64) at [startMinute] (minutes after local midnight) until [endMinute] (an end not
 * after the start is the next day). Since 1.20 (M54) a quiet time's block ([QuietKind.QUIET], its section's name the
 * [target]). Created in schema v3 (app 1.5); schema v6 (v1.24 — M57) dropped the timed plays' rows and their
 * `volumePct` and `enabled`.
 */
@Entity(tableName = "schedules")
data class QuietEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val days: Int,
    val startMinute: Int,
    val kind: QuietKind,
    val target: String,
    val endMinute: Int? = null,
    val createdAt: Long,
)
