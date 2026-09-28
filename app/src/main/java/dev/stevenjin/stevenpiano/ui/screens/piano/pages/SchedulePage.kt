// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.schedule.ScheduleDraft
import dev.stevenjin.stevenpiano.schedule.ScheduleRow
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.SectionRule
import dev.stevenjin.stevenpiano.ui.components.switchColors
import dev.stevenjin.stevenpiano.ui.screens.library.MenuItem
import dev.stevenjin.stevenpiano.ui.screens.schedule.NextScheduleLine
import dev.stevenjin.stevenpiano.ui.screens.schedule.ScheduleDraftSaver
import dev.stevenjin.stevenpiano.ui.screens.schedule.ScheduleEditorSheet
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import kotlinx.coroutines.launch
import java.time.LocalTime

/** Under Allow exact alarms. */
const val EXACT_ALARMS_NOTE = "Schedules start at an exact time, which Android asks you to allow. Until then, none will start."

/** Under Add schedule: what schedules need. */
const val TABLET_NOTE = "The tablet starts them: keep it on, charged and near the piano."

/** With no schedules yet. */
const val NO_SCHEDULES = "No schedules yet. The piano can play by itself at set times: a channel, a playlist or a piece."

/**
 * Schedule (DESIGN.md › v1.5.2 — M19), in the PLAYING group: at the top NEXT: WEDNESDAY 12:30, CALM
 * and under it what the last one did ("Missed: Wednesday 12:30 (piano not connected)"); Allow exact
 * alarms when Android refuses them; then a row a schedule, "Weekdays 12:30" over "Calm channel ·
 * until 13:15 · 70%", with its switch (a tap edits it, a long press offers Edit and Delete); then
 * Add schedule. The editor is [ScheduleEditorSheet]; the alarm follows every change.
 */
@Composable
fun SchedulePage() {
    val context = LocalContext.current
    val graph = context.graph
    val schedules = graph.schedules
    val rows by schedules.rows.collectAsStateWithLifecycle()
    val last by schedules.last.collectAsStateWithLifecycle()
    val exact by schedules.exactAllowed.collectAsStateWithLifecycle()
    var editing by rememberSaveable(stateSaver = ScheduleDraftSaver) { mutableStateOf<ScheduleDraft?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    LifecycleResumeEffect(Unit) {
        schedules.recheckExact()   // the person may come back from Android's Alarms & reminders
        onPauseOrDispose { }
    }

    NextScheduleLine(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
    // What the last one did, while there are schedules for it to be about.
    last?.takeIf { !rows.isNullOrEmpty() }?.let {
        Eyebrow(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
    }
    if (!exact) {
        ActionRow(Modifier.padding(top = 8.dp), note = EXACT_ALARMS_NOTE) {
            ActionButton("Allow exact alarms", onClick = { openExactAlarmSettings(context) })
        }
    }
    SectionRule()
    val list = rows
    when {
        list == null -> Unit
        list.isEmpty() -> NoteLine(NO_SCHEDULES)
        else -> for (row in list) {
            ScheduleRowView(
                row,
                onEdit = { editing = ScheduleDraft.of(row.entry) },
                onEnabled = { on -> graph.appScope.launch { schedules.setEnabled(row.entry.id, on) } },
                onDelete = { deleting = row.entry.id },
            )
        }
    }
    ActionRow(note = TABLET_NOTE) {
        ActionButton("Add schedule", onClick = { editing = ScheduleDraft.fresh(LocalTime.now()) })
    }

    editing?.let { draft -> ScheduleEditorSheet(draft, onDismiss = { editing = null }) }
    deleting?.let { id ->
        val row = list?.firstOrNull { it.entry.id == id }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this schedule?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Text(
                    listOfNotNull(row?.let { "${it.whenLine} · ${it.whatLine}." }, "It won't play again.").joinToString(" "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    graph.appScope.launch { schedules.delete(id) }
                }) { Text("Delete schedule") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/**
 * A schedule: its days and start in Body ("Weekdays 12:30") over what it plays, until when and how
 * loud, then its switch. A tap edits it; a long press offers Edit and Delete.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScheduleRowView(row: ScheduleRow, onEdit: () -> Unit, onEnabled: (Boolean) -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val enabled = row.entry.enabled
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .combinedClickable(
                    onClickLabel = "Edit",
                    onLongClickLabel = "Show options",
                    onLongClick = { menu = true },
                    hapticFeedbackEnabled = false,   // the app's only haptic is play/pause
                    onClick = onEdit,
                )
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    row.whenLine,
                    style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Eyebrow(row.whatLine, color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false, maxLines = 2)
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onEnabled,
                modifier = Modifier.semantics { contentDescription = "${row.whenLine}, ${row.whatLine}" },
                colors = switchColors(),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            MenuItem("Edit", { menu = false }, onEdit)
            MenuItem("Delete", { menu = false }, onDelete)
        }
    }
    HairlineDivider(startInset = 16.dp)
}

/** Android's page for this app's exact alarms (Android 12+), else the app's own settings page. */
private fun openExactAlarmSettings(context: Context) {
    val app = Uri.fromParts("package", context.packageName, null)
    val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, app) else null
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)
    val opened = exact != null && runCatching { context.startActivity(exact) }.isSuccess
    if (!opened) runCatching { context.startActivity(details) }
}
