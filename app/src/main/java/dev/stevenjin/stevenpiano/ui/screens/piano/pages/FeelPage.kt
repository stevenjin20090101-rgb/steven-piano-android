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
 * Sound and touch (Feel until v1.13): how the piano strikes. PRESETS (Soft, Cinematic, Expressive, Snappy
 * as chips) · LOUDNESS (Full power, Piano volume) · the Fine tuning disclosure, closed at first: TOUCH (the
 * velocity curve and multiplier, the two floors, the ceiling, the two key-force readings, the strike test) ·
 * TIMING (scatter, bursts, strike lengths, gaps, hold, re-strike) · RELEASE · DRIVE; then Save to the piano
 * now. The hub's row reads "Full power" or "Piano volume 70%".
 */
@Composable
fun FeelPage(report: PianoReport, actions: PianoSettingsActions) = PianoPageContent(PianoPage.Feel, report, actions)
