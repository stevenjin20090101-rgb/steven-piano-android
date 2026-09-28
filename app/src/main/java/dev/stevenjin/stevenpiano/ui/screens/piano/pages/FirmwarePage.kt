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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.firmware.FirmwarePiano
import dev.stevenjin.stevenpiano.firmware.FirmwareState
import dev.stevenjin.stevenpiano.piano.PianoPage
import dev.stevenjin.stevenpiano.ui.FirmwareCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.ReadingRow
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoPageContent
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoReport
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoSettingsActions
import dev.stevenjin.stevenpiano.ui.theme.Tabular

/**
 * Firmware and status: FIRMWARE (the piano's firmware version and its updates, v1.6 — M21) ·
 * STATUS (the seven power boards, I²C errors, the pedal board, uptime, the two key-force lines and
 * where key force is set) · ACTIONS (Read status with the piano's report, All keys off, Save now).
 * The hub's row reads the version, "Update available" while a newer release is known, or "—".
 */
@Composable
fun FirmwarePage(report: PianoReport, actions: PianoSettingsActions, firmware: FirmwareReport, firmwareActions: FirmwareActions) {
    LaunchedEffect(Unit) { firmwareActions.checkFirmwareOnOpen() }   // a check as the page opens, at most every ten minutes
    PianoPageContent(PianoPage.Firmware, report, actions) { FirmwareSection(firmware, firmwareActions) }
}

/** What the FIRMWARE section shows: the piano as the updater sees it, and the update's state. */
@Immutable
data class FirmwareReport(val piano: FirmwarePiano, val state: FirmwareState)

/** What the FIRMWARE section asks of the Piano tab. */
interface FirmwareActions {
    fun checkFirmware()

    fun checkFirmwareOnOpen()

    /** Update (or Retry): the release on offer goes to the piano, followed by the foreground service. */
    fun updateFirmware(context: Context)

    fun cancelFirmware()
}

/**
 * FIRMWARE (DESIGN.md › v1.6 — M21): "Piano firmware 2.0.0 · a1b2c3d" (from Device Information,
 * "Unknown" until connected, and the one USB flash asked for on firmware that has no version); the
 * outlined Check for piano updates with what it found; then the update as it stands: the release on
 * offer with its notes and the filled Update the piano to 2.1.0; the download, the transfer over
 * the progress hairline with an outlined Cancel, the piano checking and restarting; the outcome in
 * one line, with Retry where Retry can mend it. No red: a failure reads in words.
 */
@Composable
private fun FirmwareSection(firmware: FirmwareReport, actions: FirmwareActions) {
    val context = LocalContext.current
    val connected = firmware.piano as? FirmwarePiano.Connected
    val state = firmware.state
    val canSend = connected?.updatable == true
    SectionEyebrow("Firmware")
    val shown = when {
        connected == null -> FirmwareCopy.UNKNOWN
        connected.text != null -> FirmwareCopy.version(connected.text)
        connected.tooOld -> FirmwareCopy.NO_VERSION
        else -> FirmwareCopy.UNKNOWN
    }
    ReadingRow("Piano firmware", shown)
    ActionRow(note = FirmwareCopy.checkLine(state)) {
        ActionButton(FirmwareCopy.CHECK, onClick = actions::checkFirmware, enabled = canSend && !state.busy && state != FirmwareState.Checking)
    }
    UpdateBlock(state, canSend, onUpdate = { actions.updateFirmware(context) }, onCancel = actions::cancelFirmware)
}

/** The update as it stands, under the check; nothing while there is none to show. */
@Composable
private fun UpdateBlock(state: FirmwareState, canSend: Boolean, onUpdate: () -> Unit, onCancel: () -> Unit) {
    val manifest = state.manifest
    val show = when (state) {
        FirmwareState.Idle, FirmwareState.Checking, is FirmwareState.UpToDate -> false
        is FirmwareState.Failed -> !state.check || manifest != null
        else -> true
    }
    if (!show) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        when (state) {
            is FirmwareState.Available -> Offer(state.manifest.version, state.manifest.notes, canSend, onUpdate)
            is FirmwareState.UsbOnly -> Blocked(state.manifest.version, state.manifest.notes, FirmwareCopy.USB_ONLY)
            is FirmwareState.NeedsNewerApp -> Blocked(state.manifest.version, state.manifest.notes, FirmwareCopy.NEEDS_NEWER_APP)
            is FirmwareState.Downloading -> Progress(FirmwareCopy.downloading(state.bytes), FirmwareCopy.fraction(state), onCancel)
            is FirmwareState.Verifying -> Progress(FirmwareCopy.VERIFYING_DOWNLOAD, null, onCancel)
            is FirmwareState.Sending -> Progress(FirmwareCopy.sending(state.bytes, state.total, state.ratePerSec), FirmwareCopy.fraction(state), onCancel)
            is FirmwareState.PianoVerifying -> Progress(FirmwareCopy.PIANO_VERIFYING, null, onCancel = null)
            is FirmwareState.Restarting -> Progress(FirmwareCopy.RESTARTING, null, onCancel = null)
            is FirmwareState.Done -> Line(FirmwareCopy.done(state.version, state.confirming))
            is FirmwareState.Failed -> when {
                // A check failed while a release was on offer: it stays on offer; the check's line says why.
                state.check && manifest != null -> when {
                    manifest.usbOnly -> Blocked(manifest.version, manifest.notes, FirmwareCopy.USB_ONLY)
                    else -> Offer(manifest.version, manifest.notes, canSend, onUpdate)
                }
                else -> Failure(state, canSend, onUpdate)
            }
            FirmwareState.Idle, FirmwareState.Checking, is FirmwareState.UpToDate -> Unit
        }
    }
    HairlineDivider(startInset = 16.dp)
}

/** A release the app can send: its heading and notes, the filled Update, and what the piano does meanwhile. */
@Composable
private fun Offer(version: String, notes: String, canSend: Boolean, onUpdate: () -> Unit) {
    Line(FirmwareCopy.available(version))
    Notes(notes)
    Button(onClick = onUpdate, enabled = canSend, modifier = Modifier.padding(top = 12.dp)) { Text(FirmwareCopy.update(version)) }
    Secondary(if (canSend) FirmwareCopy.QUIET else FirmwareCopy.CONNECT_FIRST, top = 8.dp)
}

/** A release this app can't send: why, in one line, and no button. */
@Composable
private fun Blocked(version: String, notes: String, why: String) {
    Line(FirmwareCopy.available(version))
    Notes(notes)
    Secondary(why, top = 12.dp)
}

/** The transfer's line over the progress hairline ([fraction] null: indeterminate), and Cancel while it still stops it. */
@Composable
private fun Progress(line: String, fraction: Float?, onCancel: (() -> Unit)?) {
    Text(
        line,
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyLarge.merge(Tabular),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ProgressHairline(fraction, Modifier.padding(top = 8.dp))
    if (onCancel != null) {
        ActionButton("Cancel", onClick = onCancel, modifier = Modifier.padding(top = 12.dp), description = "Cancel the piano's update")
    }
}

/** How the update ended, in one line (and a second when there is something to do); Retry where it can mend it. */
@Composable
private fun Failure(state: FirmwareState.Failed, canSend: Boolean, onRetry: () -> Unit) {
    Line(state.message)
    state.hint?.let { Secondary(it, top = 4.dp) }
    if (state.retryable && state.manifest != null) {
        Button(onClick = onRetry, enabled = canSend, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
        Secondary(if (canSend) FirmwareCopy.QUIET else FirmwareCopy.CONNECT_FIRST, top = 8.dp)
    }
}

@Composable
private fun Line(text: String) {
    Text(
        text,
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun Notes(notes: String) {
    if (notes.isBlank()) return
    Text(notes, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Secondary(text: String, top: Dp) {
    Text(text, Modifier.padding(top = top), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
