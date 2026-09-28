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
 * Feel: how the piano strikes. PRESETS (Soft, Cinematic, Expressive, Snappy as chips) · LOUDNESS
 * (full power, volume) · TOUCH (the velocity curve and multiplier, the two floors, the ceiling, the
 * strike test) · TIMING (scatter, bursts, strike lengths, gaps, hold, re-strike) · RELEASE ·
 * DRIVE. The hub's row reads "Full power" or "Volume 70%".
 */
@Composable
fun FeelPage(report: PianoReport, actions: PianoSettingsActions) = PianoPageContent(PianoPage.Feel, report, actions)
