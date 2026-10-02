// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ble.LinkState
import dev.stevenjin.stevenpiano.instruments.InstrumentKind
import dev.stevenjin.stevenpiano.instruments.MidiPurpose
import dev.stevenjin.stevenpiano.ui.InstrumentCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.SettingNotes
import dev.stevenjin.stevenpiano.ui.screens.piano.Anchored
import dev.stevenjin.stevenpiano.ui.screens.piano.PageRows
import dev.stevenjin.stevenpiano.ui.screens.piano.MidiPickerSheet
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoViewModel

/**
 * Piano › Instrument (DESIGN.md › v1.11): what plays. THIS INSTRUMENT: its name, its kind ("The school piano"
 * or "Standard MIDI piano") with its state in words, Connect or Disconnect, and Auto-connect on launch (from
 * the hub's APP group, v1.13). CHOOSE: "Steven Piano" and "Another MIDI piano…", a check on the one playing;
 * the second opens the MIDI picker. Under a MIDI piano the note that THE PIANO's pages are Steven Piano's and
 * hidden meanwhile. ACTIONS: All keys off, with what it does. In kiosk mode the page waits for the PIN as every
 * page does, so choosing an instrument asks for it.
 */
@Composable
fun InstrumentPage(vm: PianoViewModel) {
    val kind by vm.instrumentKind.collectAsStateWithLifecycle()
    val link by vm.link.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val listed by vm.midiDevices.collectAsStateWithLifecycle()
    val scan by vm.midiScan.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    val name = InstrumentCopy.instrumentValue(kind, settings.midiOutName)

    SectionEyebrow(InstrumentCopy.THIS_INSTRUMENT)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Eyebrow("${InstrumentCopy.kindLine(kind)} · ${InstrumentCopy.linkWords(kind, link)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HairlineDivider(startInset = 16.dp)
    Anchored(PageRows.CONNECTION.anchor) {
        ActionRow(note = (link as? LinkState.Error)?.message) {
            if (link is LinkState.Connected) {
                ActionButton(InstrumentCopy.DISCONNECT, onClick = vm::disconnect, description = "${InstrumentCopy.DISCONNECT} $name")
            } else {
                ActionButton(InstrumentCopy.CONNECT, onClick = vm::connect, description = "${InstrumentCopy.CONNECT} $name")
            }
        }
    }
    Anchored(PageRows.AUTO_CONNECT.anchor) {
        SwitchRow(PageRows.AUTO_CONNECT.label, settings.autoConnect, vm::setAutoConnect, note = SettingNotes.AUTO_CONNECT)
    }

    SectionEyebrow(InstrumentCopy.CHOOSE)
    Column(Modifier.selectableGroup()) {
        Anchored(PageRows.STEVEN_PIANO.anchor) {
            ChoiceLine(InstrumentCopy.STEVEN_PIANO, InstrumentCopy.kindLine(InstrumentKind.StevenPiano), selected = kind == InstrumentKind.StevenPiano) {
                if (kind != InstrumentKind.StevenPiano) vm.chooseStevenPiano()
            }
        }
        Anchored(PageRows.ANOTHER_PIANO.anchor) {
            ChoiceLine(
                InstrumentCopy.ANOTHER_MIDI_PIANO,
                if (kind == InstrumentKind.MidiPiano) name else InstrumentCopy.kindLine(InstrumentKind.MidiPiano),
                selected = kind == InstrumentKind.MidiPiano,
            ) { picking = true }
        }
    }
    if (kind == InstrumentKind.MidiPiano) NoteLine(InstrumentCopy.hiddenNote(name))

    SectionEyebrow(InstrumentCopy.ACTIONS)
    Anchored(PageRows.ALL_KEYS_OFF.anchor) {
        ActionRow(note = SettingNotes.ALL_KEYS_OFF) { ActionButton(InstrumentCopy.ALL_KEYS_OFF, onClick = vm::allKeysOff) }
    }

    if (picking) {
        DisposableEffect(Unit) {
            vm.startMidiScan()
            onDispose { vm.stopMidiScan() }
        }
        MidiPickerSheet(
            purpose = MidiPurpose.Instrument,
            rows = vm.pickerRows(MidiPurpose.Instrument, listed, scan),
            scan = scan,
            currentKey = if (kind == InstrumentKind.MidiPiano) settings.midiOutId else null,
            onChoose = { choice ->
                vm.chooseMidiPiano(choice)
                picking = false
            },
            onLookAgain = vm::startMidiScan,
            onDismiss = { picking = false },
        )
    }
}

/** One instrument to choose: its name, a line under it, a check when it plays; a 56 dp radio row. */
@Composable
private fun ChoiceLine(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Eyebrow(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, uppercase = false)
        }
        Spacer(Modifier.width(8.dp))
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
    }
    HairlineDivider(startInset = 16.dp)
}
