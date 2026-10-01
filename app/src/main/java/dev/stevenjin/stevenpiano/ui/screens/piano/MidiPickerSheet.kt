// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ble.BlePermissions
import dev.stevenjin.stevenpiano.ble.LinkError
import dev.stevenjin.stevenpiano.instruments.MidiChoice
import dev.stevenjin.stevenpiano.instruments.MidiPurpose
import dev.stevenjin.stevenpiano.instruments.MidiScan
import dev.stevenjin.stevenpiano.ui.InstrumentCopy
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassSheet
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline

/**
 * The MIDI picker (DESIGN.md › v1.11), one for both: a glass sheet, the eyebrow MIDI and the title "Choose a
 * keyboard" or "Choose an instrument" ([purpose]); [rows] as [dev.stevenjin.stevenpiano.instruments.MidiPicker]
 * gives them (USB first, then Bluetooth as the search finds them, never Steven Piano), each the device's name
 * and "USB" or "Bluetooth", a check on the one chosen now ([currentKey]); under them where the search stands:
 * "Looking for MIDI devices…" over a hairline while it looks (12 s), the wait line while Android's budget for
 * searches is spent, why it can't look (with Allow when the permission is missing), "Look again" once it has
 * looked; then Cancel. A row chosen is [onChoose]'s; dismissing the sheet is Cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MidiPickerSheet(
    purpose: MidiPurpose,
    rows: List<MidiChoice>,
    scan: MidiScan,
    currentKey: String?,
    onChoose: (MidiChoice) -> Unit,
    onLookAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val allow = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (BlePermissions.missing(context).isEmpty()) onLookAgain()
    }
    GlassSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Eyebrow(InstrumentCopy.PICKER_EYEBROW, Modifier.padding(horizontal = 16.dp))
            Text(
                if (purpose == MidiPurpose.Keyboard) InstrumentCopy.PICK_KEYBOARD else InstrumentCopy.PICK_INSTRUMENT,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 8.dp)
                    .semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            HairlineDivider()
            for (row in rows) PickerRow(row, current = row.key == currentKey) { onChoose(row) }
            SearchLine(scan, empty = rows.isEmpty(), onLookAgain = onLookAgain, onAllow = { allow.launch(BlePermissions.missing(context).toTypedArray()) })
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(InstrumentCopy.CANCEL) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A device: its name, "USB" or "Bluetooth" under it, and a check when it is the one chosen now. 56 dp at least. */
@Composable
private fun PickerRow(choice: MidiChoice, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = current, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = InstrumentCopy.pickerRow(choice.name, choice.transport, current) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clearAndSetSemantics { },
        ) {
            Text(choice.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Eyebrow(InstrumentCopy.transport(choice.transport), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (current) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    HairlineDivider(startInset = 16.dp)
}

/** Where the Bluetooth search stands, under the rows. */
@Composable
private fun SearchLine(scan: MidiScan, empty: Boolean, onLookAgain: () -> Unit, onAllow: () -> Unit) {
    val line = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .padding(top = 12.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    when (scan.phase) {
        MidiScan.Phase.Looking -> {
            Text(InstrumentCopy.LOOKING, line, style = MaterialTheme.typography.bodyLarge, color = secondary)
            ProgressHairline(null, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        MidiScan.Phase.Waiting -> Text(InstrumentCopy.waitLine(scan.waitMs), line, style = MaterialTheme.typography.bodyLarge, color = secondary)
        MidiScan.Phase.Blocked -> {
            Text(InstrumentCopy.blocked(scan.problem), line, style = MaterialTheme.typography.bodyLarge, color = secondary)
            Row(Modifier.padding(horizontal = 8.dp)) {
                if (scan.problem == LinkError.PermissionMissing) TextButton(onClick = onAllow) { Text(InstrumentCopy.ALLOW) }
                TextButton(onClick = onLookAgain) { Text(InstrumentCopy.LOOK_AGAIN) }
            }
        }
        MidiScan.Phase.Idle, MidiScan.Phase.Done -> {
            if (empty) Text(InstrumentCopy.NOTHING_FOUND, line, style = MaterialTheme.typography.bodyLarge, color = secondary)
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                TextButton(onClick = onLookAgain) { Text(InstrumentCopy.LOOK_AGAIN) }
            }
        }
    }
}
