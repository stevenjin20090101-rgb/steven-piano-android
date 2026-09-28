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
import dev.stevenjin.stevenpiano.piano.PianoPage
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoPageContent
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoReport
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoSettingsActions

/**
 * Firmware and status: FIRMWARE (the piano's firmware version, "Unknown" until it has said; M21
 * adds updating it from here) · STATUS (the seven power boards, I²C errors, the pedal board, uptime,
 * the two key-force lines and where key force is set) · ACTIONS (Read status with the piano's
 * report, All keys off, Save now). The hub's row reads the version, or "—".
 */
@Composable
fun FirmwarePage(report: PianoReport, actions: PianoSettingsActions) = PianoPageContent(PianoPage.Firmware, report, actions)
