// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.player.PlaybackLimits
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.settings.WideLayout
import dev.stevenjin.stevenpiano.ui.LocalAppFrame
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.ScreenHeader
import dev.stevenjin.stevenpiano.ui.components.StepperControl
import dev.stevenjin.stevenpiano.ui.components.readingWidth
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary

/**
 * The Piano tab: the connection card, the few preferences, and the About row at the very bottom.
 * On wide screens it reads as a 720 dp column in the middle; it scrolls from anywhere.
 */
@Composable
fun PianoScreen() {
    val graph = LocalContext.current.graph
    val vm = viewModel { PianoViewModel(graph) }
    val link by vm.link.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val playing by vm.playing.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Piano", Modifier.readingWidth())
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.readingWidth()) {
                ConnectionCard(link, playing, vm::connect, vm::cancel, vm::disconnect, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                Preferences(settings, vm)
                AboutRow(Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
private fun Preferences(settings: PianoSettings, vm: PianoViewModel) {
    val frame = LocalAppFrame.current
    Eyebrow(
        "Preferences",
        Modifier
            .padding(start = 16.dp, top = 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
    HairlineDivider()
    SwitchRow("Auto-connect on launch", settings.autoConnect, vm::setAutoConnect)
    // On wide screens the staff has its own place, so Note display picks the roll's style there.
    val display = if (frame.wide) settings.noteDisplay.rollStyle else settings.noteDisplay
    SingleChoice("Note display", frame.noteDisplayChoices, display, ::labelOf, vm::setNoteDisplay)
    if (frame.wide) SingleChoice("Wide layout", WideLayout.entries, settings.wideLayout, ::labelOf, vm::setWideLayout)
    StepperRow("Default tempo", settings.defaultTempoPct, PlaybackLimits.TempoPct, 5, Format::percent, "Slower default tempo", "Faster default tempo", vm::setDefaultTempo)
    StepperRow("Transpose", settings.transpose, PlaybackLimits.Transpose, 1, Format::semitones, "Transpose down a semitone", "Transpose up a semitone", vm::setTranspose)
    StepperRow("Velocity", settings.velocityPct, PlaybackLimits.VelocityPct, 5, Format::percent, "Play softer", "Play louder", vm::setVelocity)
    SwitchRow("Fold notes outside C1–B7", settings.foldOutOfRange, vm::setFold)
    SwitchRow("Skip drum channel", settings.skipDrumChannel, vm::setSkipDrums)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(label, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedBorderColor = LocalTertiary.current,
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
            ),
        )
    }
    HairlineDivider(startInset = 16.dp)
}

@Composable
private fun StepperRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    format: (Int) -> String,
    decreaseLabel: String,
    increaseLabel: String,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowLabel(label, Modifier.weight(1f))
        StepperControl(value, range, step, format, decreaseLabel, increaseLabel, onChange)
    }
    HairlineDivider(startInset = 16.dp)
}

/** A preference with a few named options: its label, then one radio row per option. */
@Composable
private fun <T> SingleChoice(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        RowLabel(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        options.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(option == selected, role = Role.RadioButton) { onSelect(option) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                RowLabel(label(option))
            }
        }
    }
    HairlineDivider(startInset = 16.dp)
}

@Composable
private fun RowLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
}

private fun labelOf(display: NoteDisplay): String = when (display) {
    NoteDisplay.PAPER_ROLL -> "Paper roll"
    NoteDisplay.FALLING -> "Falling notes"
    NoteDisplay.STAFF -> "Staff"
}

private fun labelOf(layout: WideLayout): String = when (layout) {
    WideLayout.STAFF_AND_NOTES -> "Staff and notes"
    WideLayout.NOTES_ONLY -> "Notes only"
    WideLayout.STAFF_ONLY -> "Staff only"
}
