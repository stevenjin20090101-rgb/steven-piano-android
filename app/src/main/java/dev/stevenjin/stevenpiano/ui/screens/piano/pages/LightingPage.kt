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
 * Lighting: the LED strip over the keys and the piano's own screen. STRIP (on or off, mode,
 * brightness, reactive palette) · LAYOUT (length, offset, scale, the unlit end, direction, glow, Test
 * LED) · MOTION (brightness following velocity, fade and rainbow speeds) · PIANO'S SCREEN (when it
 * dims, and how far). The hub's row reads "Off" or "Reactive · 62%".
 */
@Composable
fun LightingPage(report: PianoReport, actions: PianoSettingsActions) = PianoPageContent(PianoPage.Lighting, report, actions)
