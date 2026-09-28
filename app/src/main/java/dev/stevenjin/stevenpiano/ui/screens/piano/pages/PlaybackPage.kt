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
import dev.stevenjin.stevenpiano.player.PlaybackLimits
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.components.SectionRule
import dev.stevenjin.stevenpiano.ui.components.StepperControl
import dev.stevenjin.stevenpiano.ui.components.StepperRow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoViewModel

/**
 * Playback: how the app sends a piece to the piano, in one section. Pause before each piece (Off,
 * then half seconds up to 5 s; 2 s at first), Default tempo, Transpose and Velocity (steppers),
 * Fold notes outside C1–B7 and Skip drum channel (switches). The hub's row reads the pause and the
 * default tempo, "2 s pause · 100%".
 */
@Composable
fun PlaybackPage(settings: PianoSettings, vm: PianoViewModel) {
    SectionRule()
    StepperRow("Pause before each piece") {
        StepperControl(settings.preRollMs, PlaybackLimits.PreRollMs, PRE_ROLL_STEP_MS, ::pauseLabel, "Shorter pause", "Longer pause", vm::setPreRoll)
    }
    StepperRow("Default tempo") {
        StepperControl(settings.defaultTempoPct, PlaybackLimits.TempoPct, 5, Format::percent, "Slower default tempo", "Faster default tempo", vm::setDefaultTempo)
    }
    StepperRow("Transpose") {
        StepperControl(settings.transpose, PlaybackLimits.Transpose, 1, Format::semitones, "Transpose down a semitone", "Transpose up a semitone", vm::setTranspose)
    }
    StepperRow("Velocity") {
        StepperControl(settings.velocityPct, PlaybackLimits.VelocityPct, 5, Format::percent, "Play softer", "Play louder", vm::setVelocity)
    }
    SwitchRow("Fold notes outside C1–B7", settings.foldOutOfRange, vm::setFold)
    SwitchRow("Skip drum channel", settings.skipDrumChannel, vm::setSkipDrums)
}

/** The pause steps by half a second. */
private const val PRE_ROLL_STEP_MS = 500

/** "Off", "0.5 s", "1 s", "2 s", "2.5 s". */
private fun pauseLabel(ms: Int): String = if (ms == 0) "Off" else Format.seconds(ms)
