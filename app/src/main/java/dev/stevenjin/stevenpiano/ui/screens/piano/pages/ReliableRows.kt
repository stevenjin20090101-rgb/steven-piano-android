// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.stevenjin.stevenpiano.admin.KeepRunning
import dev.stevenjin.stevenpiano.diag.SystemReading
import dev.stevenjin.stevenpiano.ui.ReliableCopy
import dev.stevenjin.stevenpiano.ui.Reliability
import dev.stevenjin.stevenpiano.ui.SystemCopy
import dev.stevenjin.stevenpiano.ui.components.ActionButton
import dev.stevenjin.stevenpiano.ui.components.ActionRow
import dev.stevenjin.stevenpiano.ui.components.FactRow
import dev.stevenjin.stevenpiano.ui.components.HairlineDivider

/**
 * The Tablet card's foot (v1.39 — M77; DESIGN.md › v1.39 — M77): **Runs reliably** (the device owner, no battery limits,
 * the power watch running while a plug is paired), amber when any is missing, with the fix in one line; then the battery
 * row (Android's dialog, Allow, while it is limited) and the app's own settings page, where Lenovo's ZUI keeps "Allow
 * background activity". Read with the rest of the tablet, every 5 s while the page shows, so a yes shows on return.
 */
@Composable
internal fun ReliableRows(reading: SystemReading?) {
    val context = LocalContext.current
    val reliability = Reliability.of(reading?.app)
    HairlineDivider(startInset = 16.dp)
    FactRow(SystemCopy.Fact(ReliableCopy.LABEL, reliability?.let(ReliableCopy::value), attention = reliability?.ok == false))
    reliability?.let(ReliableCopy::fix)?.let { CardNote(it) }
    HairlineDivider(startInset = 16.dp)
    val unrestricted = reading?.app?.batteryUnrestricted
    ActionRow(note = ReliableCopy.batteryNote(unrestricted)) {
        ActionButton(ReliableCopy.ALLOW, onClick = { KeepRunning.askBattery(context) }, enabled = unrestricted == false)
    }
    ActionRow(note = ReliableCopy.BACKGROUND_NOTE) {
        ActionButton(ReliableCopy.APP_SETTINGS, onClick = { KeepRunning.openAppSettings(context) })
    }
}
