// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.quiet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.schedule.Occurrences
import dev.stevenjin.stevenpiano.schedule.QuietBlock
import dev.stevenjin.stevenpiano.schedule.QuietCopy
import dev.stevenjin.stevenpiano.schedule.QuietSection
import dev.stevenjin.stevenpiano.schedule.QuietTimes
import dev.stevenjin.stevenpiano.schedule.ScheduleCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassAlertDialog
import dev.stevenjin.stevenpiano.ui.components.GlassDialogSurface
import dev.stevenjin.stevenpiano.ui.components.GlassSheet
import dev.stevenjin.stevenpiano.ui.components.GlyphButton
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SheetChip
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

/** The name field's example, before one is typed. */
private const val NAME_EXAMPLE = "School days"

/**
 * A section of quiet times made or edited (DESIGN.md › v1.20 — M54), in a sheet with its drag handle: QUIET TIMES over
 * "Add section" or "Edit section"; the name; DAYS, a chip a day, Monday first, with Weekdays and Every day; BLOCKS,
 * each from and to on the app's outlined buttons that open the time picker (in the app's ink), "The next day" under a
 * block that crosses midnight, and its remove glyph; Add block, which proposes one starting ten minutes after the last
 * block's end, as long as it ([QuietTimes.proposed]). Save keeps every section as it stands with this one ([sections]
 * with this one at [index], or added: [index] null), checked by [QuietTimes.validate], whose words show above the
 * buttons; Delete section (an edited one) asks first. The alarm and the gate follow the table.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuietEditorSheet(index: Int?, sections: List<QuietSection>, onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    val initial = remember(index) { index?.let { sections.getOrNull(it) } ?: fresh() }
    var draft by rememberSaveable(stateSaver = SectionSaver) { mutableStateOf(initial) }
    var pickingBlock by rememberSaveable { mutableIntStateOf(NONE) }
    var pickingEnd by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    fun change(next: QuietSection) {
        draft = next
        failure = null
    }
    /** Every section as Save would keep them: this one at its place, or added; without it ([with] null) for Delete. */
    fun all(with: QuietSection?): List<QuietSection> = sections.toMutableList().apply {
        when {
            index == null -> if (with != null) add(with)
            with == null -> removeAt(index)
            else -> set(index, with)
        }
    }
    fun keep(kept: List<QuietSection>) {
        if (saving) return
        saving = true
        // In the app's scope: a save outlives the sheet it came from.
        graph.appScope.launch {
            val problem = graph.schedules.saveQuiet(kept)
            saving = false
            if (problem == null) onDismiss() else failure = problem
        }
    }

    GlassSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Eyebrow(QuietCopy.TITLE, Modifier.padding(horizontal = 16.dp))
            Text(
                if (index == null) "Add section" else "Edit section",
                Modifier
                    .padding(horizontal = 16.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = draft.name,
                onValueChange = { change(draft.copy(name = it.take(QuietTimes.MAX_NAME))) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                singleLine = true,
                label = { Text("Name") },
                placeholder = { Text(NAME_EXAMPLE) },
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = MaterialTheme.shapes.small,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedBorderColor = LocalTertiary.current,
                    disabledBorderColor = LocalHairline.current,
                    focusedLabelColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.onSurface,
                ),
            )

            SectionEyebrow("Days")
            DayChips(draft.days) { change(draft.copy(days = it)) }

            SectionEyebrow("Blocks")
            draft.blocks.forEachIndexed { i, block ->
                BlockRow(
                    block,
                    onStart = {
                        pickingBlock = i
                        pickingEnd = false
                    },
                    onEnd = {
                        pickingBlock = i
                        pickingEnd = true
                    },
                    onRemove = { change(draft.copy(blocks = draft.blocks.filterIndexed { j, _ -> j != i })) },
                )
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ActionButton(
                    "Add block",
                    onClick = { change(draft.copy(blocks = draft.blocks + QuietTimes.proposed(draft.blocks, nextHour()))) },
                    enabled = draft.blocks.size < QuietTimes.MAX_BLOCKS,
                )
            }

            val problem = failure ?: QuietTimes.validate(all(draft))
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (index != null) TextButton(onClick = { deleting = true }) { Text("Delete section") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.widthIn(min = 8.dp))
                ActionButton("Save", onClick = { keep(all(draft.copy(name = draft.name.trim()))) }, enabled = problem == null && !saving)
            }
        }
    }

    draft.blocks.getOrNull(pickingBlock)?.let { block ->
        val at = pickingBlock
        TimeDialog(
            title = if (pickingEnd) "To" else "From",
            minute = if (pickingEnd) block.end else block.start,
            onPick = { picked ->
                val changed = if (pickingEnd) block.copy(end = picked) else block.copy(start = picked)
                pickingBlock = NONE
                change(draft.copy(blocks = draft.blocks.mapIndexed { j, it -> if (j == at) changed else it }))
            },
            onDismiss = { pickingBlock = NONE },
        )
    }
    if (deleting && index != null) {
        GlassAlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete this section?") },
            text = { Text("${draft.name.ifBlank { "This section" }}: its blocks go, and the piano may play at those times again.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    keep(all(null))
                }) { Text("Delete section") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** A new section: weekdays, one block from the next whole hour. */
private fun fresh(): QuietSection = QuietSection("", Occurrences.WEEKDAYS, listOf(QuietTimes.proposed(emptyList(), nextHour())))

/** The next whole hour, minutes after midnight. */
private fun nextHour(): Int = (LocalTime.now().hour + 1) % HOURS_PER_DAY * Occurrences.MINUTES_PER_HOUR

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

/** A block: from and to on the app's outlined buttons (each opens the picker), "The next day" under it when it crosses midnight, and its remove glyph. */
@Composable
private fun BlockRow(block: QuietBlock, onStart: () -> Unit, onEnd: () -> Unit, onRemove: () -> Unit) {
    val from = QuietCopy.clock(block.start)
    val to = QuietCopy.clock(block.end)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("From", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                ActionButton(from, onClick = onStart, description = "From $from. Change the time")
                Spacer(Modifier.width(12.dp))
                Text("to", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                ActionButton(to, onClick = onEnd, description = "To $to. Change the time")
            }
            if (block.end < block.start) Eyebrow("The next day", color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
        }
        GlyphButton(R.drawable.ic_close, "Remove ${QuietCopy.range(block)}", onClick = onRemove)
    }
    HairlineDivider(startInset = 16.dp)
}

/**
 * The time picker, on the 24-hour clock, in the app's ink: no colour but the content's, on the
 * dialogs' glass as the app's other dialogs (DESIGN.md › v1.9; the elevated tone where the glass
 * cannot be drawn). Its own dialog surface: Material's TimePickerDialog lays the paper tinted by the
 * ink's elevation overlay, a grey that is none of the app's tokens; the picker itself is clear, so
 * the glass shows through it, and only its dial and its fields are drawn. (The schedule editor's, until 1.20.)
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
    val clear = Color.Transparent
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    BasicAlertDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        GlassDialogSurface {
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
                        containerColor = clear,
                        periodSelectorBorderColor = LocalTertiary.current,
                        periodSelectorSelectedContainerColor = ink,
                        periodSelectorUnselectedContainerColor = clear,
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

/** A section being edited, kept across rotation and process death: its name, its days, then each block's start and end. */
private val SectionSaver = listSaver<QuietSection, Any>(
    save = { section -> listOf(section.name, section.days) + section.blocks.flatMap { listOf(it.start, it.end) } },
    restore = { saved ->
        val times = saved.drop(2).map { it as Int }
        QuietSection(saved[0] as String, saved[1] as Int, times.chunked(2).filter { it.size == 2 }.map { QuietBlock(it[0], it[1]) })
    },
)

private const val NONE = -1
private const val HOURS_PER_DAY = 24
