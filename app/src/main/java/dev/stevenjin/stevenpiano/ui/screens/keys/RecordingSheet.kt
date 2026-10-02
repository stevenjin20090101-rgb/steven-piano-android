// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.data.TextLimits
import dev.stevenjin.stevenpiano.record.SavedRecording
import dev.stevenjin.stevenpiano.record.TakeEnd
import dev.stevenjin.stevenpiano.ui.InstrumentCopy
import dev.stevenjin.stevenpiano.ui.components.Eyebrow
import dev.stevenjin.stevenpiano.ui.components.GlassSheet
import dev.stevenjin.stevenpiano.ui.theme.LocalHairline
import dev.stevenjin.stevenpiano.ui.theme.LocalTertiary
import dev.stevenjin.stevenpiano.ui.theme.Tabular

/**
 * After Stop (DESIGN.md › v1.11): a glass sheet, the eyebrow RECORDING, "Keep this recording?", the take's line
 * ("0:42 · 318 notes", tabular; why it stopped when it stopped by itself), the Title field (prefilled with
 * "Recording · <date> <time>"), and Discard · Listen · Keep. The take is in the library already, waiting:
 * [onKeep] keeps it under the title typed, [onDiscard] deletes it, [onListen] plays it (it waits for Keep or
 * Discard on Now playing's banner then), and dismissing the sheet leaves it waiting. In kiosk mode without the PIN
 * ([locked]): "Saved to Recordings. Someone with the PIN keeps or discards it." with Listen and Done.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordingSheet(
    saved: SavedRecording,
    ended: TakeEnd,
    locked: Boolean,
    onKeep: (title: String) -> Unit,
    onDiscard: () -> Unit,
    onListen: () -> Unit,
    onDone: () -> Unit,
) {
    var title by rememberSaveable(saved.pieceId) { mutableStateOf(saved.title) }
    GlassSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Eyebrow(InstrumentCopy.RECORDING_EYEBROW)
            Text(
                if (locked) InstrumentCopy.KIOSK_SAVED else InstrumentCopy.KEEP_RECORDING,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .semantics { heading() },
                style = if (locked) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                InstrumentCopy.takeLine(saved.durationMicros, saved.notes),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InstrumentCopy.takeEnded(ended)?.let { why ->
                Text(why, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!locked) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(TextLimits.TITLE) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    singleLine = true,
                    label = { Text(InstrumentCopy.TITLE_FIELD) },
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
            }
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
            ) {
                if (locked) {
                    TextButton(onClick = onListen) { Text(InstrumentCopy.LISTEN) }
                    Button(onClick = onDone) { Text(InstrumentCopy.DONE) }
                } else {
                    TextButton(onClick = onDiscard) { Text(InstrumentCopy.DISCARD) }
                    TextButton(onClick = onListen) { Text(InstrumentCopy.LISTEN) }
                    Button(onClick = { onKeep(title) }) { Text(InstrumentCopy.KEEP) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
