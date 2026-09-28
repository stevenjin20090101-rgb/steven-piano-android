// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.schedule.Occurrences
import dev.stevenjin.stevenpiano.schedule.SaveResult
import dev.stevenjin.stevenpiano.schedule.ScheduleCopy
import dev.stevenjin.stevenpiano.schedule.ScheduleDraft
import dev.stevenjin.stevenpiano.schedule.ScheduleRules
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.PieceSearch
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SheetChoiceRow
import dev.stevenjin.stevenpiano.ui.components.SheetNote
import dev.stevenjin.stevenpiano.ui.components.SheetChip
import dev.stevenjin.stevenpiano.ui.components.SliderRow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import kotlinx.coroutines.launch
import java.time.DayOfWeek

/** Under "Until the end": what it means for each kind. */
private const val UNTIL_NOTE = "A playlist or a piece plays to its end; a channel plays until someone stops it"

/** Under "Set the volume". */
private const val VOLUME_NOTE = "Off: the piano plays as it is set, and a channel at its own volume"

/** Under the Volume slider (the channel sheet's words). */
private const val VOLUME_HELP = "The piano's own volume while it plays, or how hard its keys are struck where the piano has none. What was there comes back when it ends."


/** Which of the two times a picker is open for. */
private enum class TimeField { START, END }

/**
 * A schedule made or edited (DESIGN.md › v1.6.2 — M19), in a sheet with its drag handle: SCHEDULE
 * over "Add schedule" or "Edit schedule"; DAYS, a chip a day, Monday first, with Weekdays and Every
 * day; TIME, Starts and Ends each with the time on an outlined button that opens the time picker (in
 * the app's ink), and Until the end; PLAYS, chips Channels · Playlists · Pieces over the choices (a
 * search for pieces), the chosen one checked; VOLUME, Set the volume and its slider. Save keeps it
 * (the alarm follows); what keeps it from saving is said above the buttons. [initial] is the
 * schedule as it opens (a new one, a row's, or a channel card's with the channel chosen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorSheet(initial: ScheduleDraft, onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    var draft by rememberSaveable(stateSaver = ScheduleDraftSaver) { mutableStateOf<ScheduleDraft?>(initial) }
    var plays by rememberSaveable { mutableStateOf(initial.kind ?: ScheduleKind.CHANNEL) }
    var picking by rememberSaveable { mutableStateOf<TimeField?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val current = draft ?: initial
    fun change(next: ScheduleDraft) {
        draft = next
        failure = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Eyebrow("Schedule", Modifier.padding(horizontal = 16.dp))
            Text(
                if (current.isNew) "Add schedule" else "Edit schedule",
                Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            SectionEyebrow("Days")
            DayChips(current.days) { change(current.copy(days = it)) }

            SectionEyebrow("Time")
            TimeRow("Starts", current.startMinute, note = null) { picking = TimeField.START }
            SwitchRow(
                "Until the end",
                current.endMinute == null,
                { on -> change(current.copy(endMinute = if (on) null else (current.startMinute + ScheduleDraft.DEFAULT_LENGTH_MINUTES) % Occurrences.MINUTES_PER_DAY)) },
                note = UNTIL_NOTE,
            )
            current.endMinute?.let { end ->
                TimeRow("Ends", end, note = if (end < current.startMinute) "The next day" else null) { picking = TimeField.END }
            }

            SectionEyebrow("Plays")
            KindChips(plays) { plays = it }
            when (plays) {
                ScheduleKind.CHANNEL -> ChannelChoices(current) { change(current.copy(kind = ScheduleKind.CHANNEL, target = it)) }
                ScheduleKind.PLAYLIST -> PlaylistChoices(current) { change(current.copy(kind = ScheduleKind.PLAYLIST, target = it.toString())) }
                ScheduleKind.PIECE -> PieceChoices(current) { change(current.copy(kind = ScheduleKind.PIECE, target = it.toString())) }
            }

            SectionEyebrow("Volume")
            SwitchRow(
                "Set the volume",
                current.volumePct != null,
                { on -> change(current.copy(volumePct = if (on) ScheduleDraft.DEFAULT_VOLUME else null)) },
                note = VOLUME_NOTE,
            )
            current.volumePct?.let { volume ->
                SliderRow(
                    label = "Volume",
                    value = volume.toFloat(),
                    range = 0f..100f,
                    step = 1f,
                    shown = Format.percent(volume),
                    onChange = { change(current.copy(volumePct = it.toInt())) },
                    description = "Schedule volume",
                    stateDescription = Format.percent(volume),
                    unit = "%",
                )
                Eyebrow(VOLUME_HELP, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
            }

            val problem = failure ?: current.problem
            if (problem != null) {
                Text(
                    problem,
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.widthIn(min = 8.dp))
                ActionButton(
                    "Save",
                    onClick = {
                        if (saving) return@ActionButton
                        saving = true
                        // In the app's scope: a save outlives the sheet it came from.
                        graph.appScope.launch {
                            val result = graph.schedules.save(current)
                            saving = false
                            when (result) {
                                is SaveResult.Saved -> onDismiss()
                                SaveResult.TooMany -> failure = "There are ${ScheduleRules.MAX_SCHEDULES} schedules already. Delete one first."
                                SaveResult.Gone -> failure = "This schedule was deleted meanwhile."
                            }
                        }
                    },
                    enabled = current.problem == null && !saving,
                )
            }
        }
    }

    picking?.let { field ->
        val minute = if (field == TimeField.START) current.startMinute else current.endMinute ?: current.startMinute
        TimeDialog(
            title = if (field == TimeField.START) "Starts" else "Ends",
            minute = minute,
            onPick = { picked ->
                picking = null
                change(if (field == TimeField.START) current.copy(startMinute = picked) else current.copy(endMinute = picked))
            },
            onDismiss = { picking = null },
        )
    }
}

/** A chip a day, Monday first, then Weekdays and Every day, which set the days at once and show when they are what is chosen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayChips(days: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        FlowRow(
            Modifier
                .fillMaxWidth()
                .semantics { selectableGroup() },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (day in DayOfWeek.entries) {
                val bit = Occurrences.dayBit(day)
                SheetChip(ScheduleCopy.short(day), days and bit != 0, description = ScheduleCopy.full(day)) { onChange(days xor bit) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetChip("Weekdays", days == Occurrences.WEEKDAYS) { onChange(Occurrences.WEEKDAYS) }
            SheetChip("Every day", days == Occurrences.ALL_DAYS) { onChange(Occurrences.ALL_DAYS) }
        }
    }
    HairlineDivider(startInset = 16.dp)
}

/** Channels · Playlists · Pieces: which list of choices shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KindChips(kind: ScheduleKind, onSelect: (ScheduleKind) -> Unit) {
    FlowRow(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { selectableGroup() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SheetChip("Channels", kind == ScheduleKind.CHANNEL) { onSelect(ScheduleKind.CHANNEL) }
        SheetChip("Playlists", kind == ScheduleKind.PLAYLIST) { onSelect(ScheduleKind.PLAYLIST) }
        SheetChip("Pieces", kind == ScheduleKind.PIECE) { onSelect(ScheduleKind.PIECE) }
    }
    HairlineDivider(startInset = 16.dp)
}

/** A time of day: the label, and the time on the app's outlined button, which opens the picker. */
@Composable
private fun TimeRow(label: String, minute: Int, note: String?, onClick: () -> Unit) {
    val time = ScheduleCopy.clock(minute)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (note != null) Eyebrow(note, color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
        }
        Spacer(Modifier.width(16.dp))
        ActionButton(time, onClick = onClick, description = "$label $time. Change the time")
    }
    HairlineDivider(startInset = 16.dp)
}

/** The channels, as the Library's cards list them; one too small to play can't be chosen. */
@Composable
private fun ChannelChoices(draft: ScheduleDraft, onChoose: (String) -> Unit) {
    val channels by LocalContext.current.graph.channelPools.summaries.collectAsStateWithLifecycle()
    val list = channels ?: return SheetNote("The channels are being worked out from the library.")
    Column(Modifier.semantics { selectableGroup() }) {
        for (channel in list) {
            SheetChoiceRow(
                title = channel.name,
                meta = if (channel.playable) Format.count(channel.size, "piece", "pieces") else "Add more pieces",
                chosen = draft.kind == ScheduleKind.CHANNEL && draft.target == channel.key,
                enabled = channel.playable,
            ) { onChoose(channel.key) }
        }
    }
}

/** Every playlist, the built-in ones first, as the Library shows them. */
@Composable
private fun PlaylistChoices(draft: ScheduleDraft, onChoose: (Long) -> Unit) {
    val library = LocalContext.current.graph.library
    val playlists by produceState<List<dev.stevenjin.stevenpiano.data.db.PlaylistSummary>?>(null, library) {
        library.playlists().collect { value = it }
    }
    val list = playlists ?: return
    if (list.isEmpty()) return SheetNote("No playlists yet.")
    Column(Modifier.semantics { selectableGroup() }) {
        for (playlist in list.sortedWith(compareByDescending<dev.stevenjin.stevenpiano.data.db.PlaylistSummary> { it.builtIn })) {
            SheetChoiceRow(
                title = playlist.name,
                meta = listOfNotNull(if (playlist.builtIn) "Built in" else null, Format.count(playlist.pieceCount, "piece", "pieces")).joinToString(" · "),
                chosen = draft.kind == ScheduleKind.PLAYLIST && draft.target == playlist.id.toString(),
            ) { onChoose(playlist.id) }
        }
    }
}

/** A search over the library's titles and composers; before one, the piece chosen and the ones played last. */
@Composable
private fun PieceChoices(draft: ScheduleDraft, onChoose: (Long) -> Unit) {
    PieceSearch(chosenId = if (draft.kind == ScheduleKind.PIECE) draft.target?.toLongOrNull() else null, onChoose = onChoose)
}

/**
 * The time picker, on the 24-hour clock, in the app's ink: no colour but the content's, on the
 * elevated tone as the app's other dialogs. Its own dialog surface: Material's TimePickerDialog lays
 * the paper tinted by the ink's elevation overlay, a grey that is none of the app's tokens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, minute: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(
        initialHour = minute / Occurrences.MINUTES_PER_HOUR,
        initialMinute = minute % Occurrences.MINUTES_PER_HOUR,
        is24Hour = true,
    )
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val elevated = MaterialTheme.colorScheme.surfaceVariant
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    BasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = elevated, tonalElevation = 0.dp) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Eyebrow(
                    title,
                    Modifier
                        .align(Alignment.Start)
                        .padding(bottom = 20.dp)
                        .semantics { heading() },
                )
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = paper,
                        clockDialSelectedContentColor = paper,
                        clockDialUnselectedContentColor = ink,
                        selectorColor = ink,
                        containerColor = elevated,
                        periodSelectorBorderColor = LocalTertiary.current,
                        periodSelectorSelectedContainerColor = ink,
                        periodSelectorUnselectedContainerColor = elevated,
                        periodSelectorSelectedContentColor = paper,
                        periodSelectorUnselectedContentColor = secondary,
                        timeSelectorSelectedContainerColor = ink,
                        timeSelectorUnselectedContainerColor = paper,
                        timeSelectorSelectedContentColor = paper,
                        timeSelectorUnselectedContentColor = ink,
                    ),
                )
                Row(Modifier.align(Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onPick(state.hour * Occurrences.MINUTES_PER_HOUR + state.minute) }) { Text("Done") }
                }
            }
        }
    }
}
