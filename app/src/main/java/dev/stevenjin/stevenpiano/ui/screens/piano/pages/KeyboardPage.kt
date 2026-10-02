// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.instruments.KeyboardState
import dev.stevenjin.stevenpiano.instruments.MidiPurpose
import dev.stevenjin.stevenpiano.ui.InstrumentCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SectionRule
import dev.stevenjin.stevenpiano.ui.screens.piano.Anchored
import dev.stevenjin.stevenpiano.ui.screens.piano.MidiPickerSheet
import dev.stevenjin.stevenpiano.ui.screens.piano.PageRows
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoViewModel

/**
 * Piano › Keyboard (DESIGN.md › v1.11): the MIDI keyboard to play the piano from. Its state line; when the
 * keyboard asks to pair, the line saying what to do and Open Bluetooth settings; "Choose a keyboard…", which
 * opens the MIDI picker; once one is chosen, THIS KEYBOARD: its name, "Bluetooth" or "USB" with its state,
 * and Forget; then the two notes (where it is played from, and that a cable is steadier). In kiosk mode the
 * page waits for the PIN as every page does, so choosing and forgetting ask for it.
 */
@Composable
fun KeyboardPage(vm: PianoViewModel) {
    val state by vm.keyboard.collectAsStateWithLifecycle()
    val listed by vm.midiDevices.collectAsStateWithLifecycle()
    val scan by vm.midiScan.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    SectionRule()
    NoteLine(InstrumentCopy.keyboardLine(state), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (state.phase == KeyboardState.Phase.NeedsPairing) {
        NoteLine(InstrumentCopy.PAIR_LINE)
        ActionRow {
            ActionButton(
                InstrumentCopy.OPEN_BLUETOOTH_SETTINGS,
                onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } },
            )
        }
    }
    Anchored(PageRows.CHOOSE_KEYBOARD.anchor) {
        ActionRow {
            ActionButton(InstrumentCopy.CHOOSE_KEYBOARD, onClick = { picking = true }, enabled = state.phase != KeyboardState.Phase.Unavailable)
        }
    }
    val chosen = state.chosen
    if (chosen != null) {
        SectionEyebrow(InstrumentCopy.THIS_KEYBOARD)
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .semantics(mergeDescendants = true) { }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(chosen.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Eyebrow(InstrumentCopy.keyboardDetail(state), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HairlineDivider(startInset = 16.dp)
        Anchored(PageRows.FORGET_KEYBOARD.anchor) {
            ActionRow { ActionButton(InstrumentCopy.FORGET, onClick = vm::forgetKeyboard, description = "Forget ${chosen.name}") }
        }
    }
    NoteLine(InstrumentCopy.PLAY_FROM_KEYS)
    NoteLine(InstrumentCopy.CABLE_NOTE)

    if (picking) {
        DisposableEffect(Unit) {
            vm.startMidiScan()
            onDispose { vm.stopMidiScan() }
        }
        MidiPickerSheet(
            purpose = MidiPurpose.Keyboard,
            rows = vm.pickerRows(MidiPurpose.Keyboard, listed, scan),
            scan = scan,
            currentKey = chosen?.key,
            onChoose = { choice ->
                vm.chooseKeyboard(choice)
                picking = false
            },
            onLookAgain = vm::startMidiScan,
            onDismiss = { picking = false },
        )
    }
}
