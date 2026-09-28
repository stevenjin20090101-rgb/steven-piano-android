// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.schedule

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.schedule.ScheduleDraft
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import androidx.compose.material3.MaterialTheme

/**
 * "NEXT: WEDNESDAY 12:30, CALM", in the eyebrow style (DESIGN.md › v1.6.2 — M19): atop the Schedule
 * page, and where nothing plays, over "Choose a piece from the library." on Now playing and in the
 * tablet's now-playing panel. Nothing at all when no schedule is ahead.
 */
@Composable
fun NextScheduleLine(modifier: Modifier = Modifier, centred: Boolean = false) {
    val next by LocalContext.current.graph.schedules.next.collectAsStateWithLifecycle()
    val line = next?.line ?: return
    Eyebrow(
        line,
        modifier,
        maxLines = 2,
        style = if (centred) MaterialTheme.typography.labelSmall.merge(textAlign = TextAlign.Center) else MaterialTheme.typography.labelSmall,
    )
}

/** A schedule being edited, kept across rotation and process death: its fields as a list, empty for none. */
val ScheduleDraftSaver = listSaver<ScheduleDraft?, Any>(
    save = { draft ->
        if (draft == null) {
            emptyList()
        } else {
            listOf(
                draft.id,
                draft.days,
                draft.startMinute,
                draft.kind?.name.orEmpty(),
                draft.target.orEmpty(),
                draft.endMinute ?: NONE,
                draft.volumePct ?: NONE,
                draft.enabled,
            )
        }
    },
    restore = { saved ->
        if (saved.isEmpty()) {
            null
        } else {
            ScheduleDraft(
                id = saved[0] as Long,
                days = saved[1] as Int,
                startMinute = saved[2] as Int,
                kind = (saved[3] as String).takeIf { it.isNotEmpty() }?.let(ScheduleKind::valueOf),
                target = (saved[4] as String).ifEmpty { null },
                endMinute = (saved[5] as Int).takeIf { it != NONE },
                volumePct = (saved[6] as Int).takeIf { it != NONE },
                enabled = saved[7] as Boolean,
            )
        }
    },
)

private const val NONE = -1
