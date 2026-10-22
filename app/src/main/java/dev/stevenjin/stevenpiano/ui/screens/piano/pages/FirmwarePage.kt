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
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.firmware.FirmwareFailures
import dev.stevenjin.stevenpiano.firmware.FirmwarePiano
import dev.stevenjin.stevenpiano.firmware.FirmwareState
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.piano.LockWord
import dev.stevenjin.stevenpiano.piano.PianoLock
import dev.stevenjin.stevenpiano.piano.PianoPage
import dev.stevenjin.stevenpiano.piano.PianoSettingsRepository
import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.ui.FirmwareCopy
import dev.stevenjin.stevenpiano.ui.LockCopy
import dev.stevenjin.stevenpiano.ui.LockedFirmware
import dev.stevenjin.stevenpiano.ui.PowerCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.FilledButton
import dev.stevenjin.stevenpiano.ui.components.GlassAlertDialog
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.ProgressHairline
import dev.stevenjin.stevenpiano.ui.components.ReadingRow
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.screens.piano.Anchored
import dev.stevenjin.stevenpiano.ui.screens.piano.PageRows
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoPageContent
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoReport
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoSettingsActions
import dev.stevenjin.stevenpiano.ui.theme.Tabular
import kotlinx.coroutines.delay

/**
 * Firmware and status: FIRMWARE (the piano's firmware version and its updates, v1.6 — M21) ·
 * STATUS (the seven power boards, I²C errors, the pedal board, uptime, then Read status with the
 * piano's report). Since v1.13 (M31b) the key-force lines are under Sound and touch › Fine tuning, Save
 * now is Sound and touch's "Save to the piano now", and All keys off is on the Instrument page.
 * The hub's row reads the version, "Update available" while a newer release is known, or "—".
 * From v1.25 (M58) FIRMWARE also shows the piano's Bluetooth lock, on firmware that has it ([LockRows]).
 */
@Composable
fun FirmwarePage(report: PianoReport, actions: PianoSettingsActions, firmware: FirmwareReport, firmwareActions: FirmwareActions) {
    LaunchedEffect(Unit) { firmwareActions.checkFirmwareOnOpen() }   // a check as the page opens, at most every ten minutes
    val lock = (report.piano as? PianoState.Ready)?.lock ?: PianoLock.NONE
    PianoPageContent(PianoPage.Firmware, report, actions) { FirmwareSection(firmware, firmwareActions, lock) }
}

/**
 * FIRMWARE while the settings are locked in kiosk ([LockedFirmware], v1.6.2): the update's block
 * alone, its progress with Cancel ([onCancel], which asks for the PIN) or how it ended, never the
 * release on offer or Retry; nothing when there is no update to show. The locked page's note follows.
 */
@Composable
fun LockedFirmwareUpdate(firmware: FirmwareReport, onCancel: () -> Unit) {
    if (!LockedFirmware.shows(firmware.state)) return
    SectionEyebrow("Firmware")
    UpdateBlock(firmware.state, canSend = false, onUpdate = {}, onCancel = onCancel, locked = true)
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
 * one line, with Retry where Retry can mend it. No red: a failure reads in words. Nothing is sent
 * while the piano's lock doesn't let this tablet write ([lock], v1.25 — M58); its rows follow.
 */
@Composable
private fun FirmwareSection(firmware: FirmwareReport, actions: FirmwareActions, lock: PianoLock) {
    val context = LocalContext.current
    val connected = firmware.piano as? FirmwarePiano.Connected
    val state = firmware.state
    val canCheck = connected?.updatable == true
    val canSend = canCheck && lock.writable
    // Why Update waits: not connected, or (v1.25 — M58) the piano's lock reads only.
    val waiting = if (canCheck && lock.phase.readOnly) FirmwareFailures.NOT_AUTHORISED else FirmwareCopy.CONNECT_FIRST
    SectionEyebrow("Firmware")
    val shown = when {
        connected == null -> FirmwareCopy.UNKNOWN
        connected.text != null -> FirmwareCopy.version(connected.text)
        connected.tooOld -> FirmwareCopy.NO_VERSION
        else -> FirmwareCopy.UNKNOWN
    }
    Anchored("fw") { ReadingRow("Piano firmware", shown) }
    Anchored(PageRows.CHECK_PIANO_UPDATES.anchor) {
        ActionRow(note = FirmwareCopy.checkLine(state)) {
            ActionButton(FirmwareCopy.CHECK, onClick = actions::checkFirmware, enabled = canCheck && !state.busy && state != FirmwareState.Checking)
        }
    }
    UpdateBlock(state, canSend, onUpdate = { actions.updateFirmware(context) }, onCancel = actions::cancelFirmware, waiting = waiting)
    RailGuestRows()   // v1.26 — M60: shown whether or not the tablet holds the piano (it lets go during guest play)
    LockRows(lock, context.graph.pianoSettings)
}

/**
 * Guest play this tablet started while the 24 V is on its smart plug (v1.26 — M60; DESIGN.md › v1.26 — M60): the time left
 * (the tablet keeps the 24 V on for it, 60 minutes at most, and has let go of the piano so another device can connect) and
 * **End guest play** (connect again, `lock guest 0`, the stop sequence, then the 24 V off). Nothing otherwise.
 */
@Composable
private fun RailGuestRows() {
    val graph = LocalContext.current.graph
    val until by graph.rail.guestUntil.collectAsStateWithLifecycle()
    val end = until ?: return
    val left by produceState(((end - SystemClock.elapsedRealtime()) / MS_PER_S).coerceAtLeast(0), end) {
        while (value > 0) {
            delay(GUEST_TICK_MS)
            value = ((end - SystemClock.elapsedRealtime()) / MS_PER_S).coerceAtLeast(0)
        }
    }
    Anchored(PageRows.END_GUEST.anchor) {
        ActionRow(note = PowerCopy.guestLeft(left) + ". " + PowerCopy.END_GUEST_NOTE) {
            ActionButton(PowerCopy.END_GUEST, onClick = graph::endGuestPlay)
        }
    }
}

/**
 * The piano's Bluetooth lock (v1.25 — M58; firmware 2.1.0, DESIGN.md › v1.25 — M58), on firmware that has it: where it
 * stands ("Locked to this tablet", "Open: any device can claim it", "Pairing open for 5 min", "Locked to another device");
 * then, on a trusted connection, Let another device pair (5 min), Unlock the piano (after the question) and Steven's
 * guest switch, Let anyone play for 1 hour, with the time left. Read only, the state alone (the page's top says why). The
 * piano's word for a command it refused shows under them. `!lock` and `!guest` are read again every 30 s while the page
 * shows (the piano says nothing when its screen opens a window, or guest play ends). Behind the kiosk PIN, as the page.
 */
@Composable
private fun LockRows(lock: PianoLock, settings: PianoSettingsRepository) {
    ReadWhileShown(LOCK_READ_MS, settings::refreshLock)
    val state = LockCopy.state(lock) ?: return
    Anchored(PageRows.LOCK.anchor) { ReadingRow(LockCopy.LOCK, state) }
    if (lock.phase.readOnly) return   // why, and how to let this tablet in: the page's own line, at its top
    val can = lock.manageable
    Anchored(PageRows.LOCK_PAIR.anchor) {
        ActionRow(note = LockCopy.PAIR_NOTE) {
            // An open piano lets any device claim it already; a window open now needs no second.
            ActionButton(LockCopy.PAIR, onClick = settings::openPairing, enabled = can && lock.piano == LockWord.Claimed)
        }
    }
    var asking by rememberSaveable { mutableStateOf(false) }
    Anchored(PageRows.LOCK_UNLOCK.anchor) {
        ActionRow(note = LockCopy.UNLOCK_NOTE) {
            ActionButton(LockCopy.UNLOCK, onClick = { asking = true }, enabled = can && lock.piano != LockWord.Open)
        }
    }
    val left = lock.guestLeft(SystemClock.elapsedRealtime())
    // v1.26 — M60: with the 24 V on its smart plug, guest play from here keeps the 24 V on for it (60 minutes at most), and
    // its end is the full sequence (End guest play).
    val graph = LocalContext.current.graph
    val plug by graph.plugSettings.collectAsStateWithLifecycle()
    val railGuest by graph.rail.guestUntil.collectAsStateWithLifecycle()
    val withPlug = plug?.switches == true
    Anchored(PageRows.LOCK_GUEST.anchor) {
        SwitchRow(
            LockCopy.GUEST,
            checked = left > 0 || railGuest != null,
            onChange = { on ->
                when {
                    !withPlug -> settings.guestPlay(if (on) LockCopy.GUEST_MINUTES else 0)
                    on -> graph.startGuestPlay()
                    else -> graph.endGuestPlay()
                }
            },
            enabled = can,
            note = LockCopy.guestNote(left) + if (withPlug && left <= 0) " " + PowerCopy.GUEST_WITH_PLUG else "",
        )
    }
    lock.said?.let {
        NoteLine(LockCopy.said(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        HairlineDivider(startInset = 16.dp)
    }
    if (asking) {
        GlassAlertDialog(
            onDismissRequest = { asking = false },
            title = { Text(LockCopy.UNLOCK_TITLE) },
            text = { Text(LockCopy.UNLOCK_TEXT) },
            confirmButton = {
                TextButton(onClick = {
                    asking = false
                    settings.unlock()
                }) { Text(LockCopy.UNLOCK_ACTION) }
            },
            dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
        )
    }
}

/** How often the lock's two facts are read again while the page shows. */
private const val LOCK_READ_MS = 30_000L

/** Guest play's time left on the plug, counted down in minutes (v1.26 — M60). */
private const val GUEST_TICK_MS = 15_000L
private const val MS_PER_S = 1_000L

/**
 * The update as it stands, under the check; nothing while there is none to show. [locked]: no Update or Retry (kiosk).
 * [waiting]: why Update can't go while it can't ([canSend] false).
 */
@Composable
private fun UpdateBlock(
    state: FirmwareState,
    canSend: Boolean,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    locked: Boolean = false,
    waiting: String = FirmwareCopy.CONNECT_FIRST,
) {
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
            is FirmwareState.Available -> Offer(state.manifest.version, state.manifest.notes, canSend, onUpdate, waiting)
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
                    else -> Offer(manifest.version, manifest.notes, canSend, onUpdate, waiting)
                }
                else -> Failure(state, canSend, onUpdate, locked, waiting)
            }
            FirmwareState.Idle, FirmwareState.Checking, is FirmwareState.UpToDate -> Unit
        }
    }
    HairlineDivider(startInset = 16.dp)
}

/** A release the app can send: its heading and notes, the filled Update, and what the piano does meanwhile (or why it waits). */
@Composable
private fun Offer(version: String, notes: String, canSend: Boolean, onUpdate: () -> Unit, waiting: String) {
    Line(FirmwareCopy.available(version))
    Notes(notes)
    FilledButton(onClick = onUpdate, enabled = canSend, modifier = Modifier.padding(top = 12.dp)) { Text(FirmwareCopy.update(version)) }
    Secondary(if (canSend) FirmwareCopy.QUIET else waiting, top = 8.dp)
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

/** How the update ended, in one line (and a second when there is something to do); Retry where it can mend it, unless [locked]. */
@Composable
private fun Failure(state: FirmwareState.Failed, canSend: Boolean, onRetry: () -> Unit, locked: Boolean, waiting: String) {
    Line(state.message)
    state.hint?.let { Secondary(it, top = 4.dp) }
    if (!locked && state.retryable && state.manifest != null) {
        FilledButton(onClick = onRetry, enabled = canSend, modifier = Modifier.padding(top = 12.dp)) { Text("Retry") }
        Secondary(if (canSend) FirmwareCopy.QUIET else waiting, top = 8.dp)
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
