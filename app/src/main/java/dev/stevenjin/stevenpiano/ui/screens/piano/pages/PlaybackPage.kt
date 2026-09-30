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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.audio.TabletSoundMode
import dev.stevenjin.stevenpiano.player.PlaybackLimits
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.TabletSoundCopy
import dev.stevenjin.stevenpiano.ui.components.ChoiceRow
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SectionRule
import dev.stevenjin.stevenpiano.ui.components.SliderRow
import dev.stevenjin.stevenpiano.ui.components.SoundFontRow
import dev.stevenjin.stevenpiano.ui.components.StepperControl
import dev.stevenjin.stevenpiano.ui.components.StepperRow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoViewModel
import kotlin.math.roundToInt

/**
 * Playback: how the app sends a piece to the piano, in one section. Pause before each piece (Off,
 * then half seconds up to 5 s; 2 s at first), Default tempo, Transpose and Velocity (steppers),
 * Fold notes outside C1–B7 and Skip drum channel (switches). The hub's row reads the pause and the
 * default tempo, "2 s pause · 100%". Then TABLET SOUND (v1.8 — M25): Piano sound on the tablet (Off ·
 * When the piano isn't connected · Always, with what the choice does), Volume, and the SoundFont's row
 * (Download, its progress with Cancel, or Installed with Remove).
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
    TabletSoundSection(settings, vm)
}

/** TABLET SOUND (DESIGN.md › v1.8 — M25): the mode, the volume, the SoundFont. */
@Composable
private fun TabletSoundSection(settings: PianoSettings, vm: PianoViewModel) {
    val sound by vm.tabletSound.collectAsStateWithLifecycle()
    SectionEyebrow(TabletSoundCopy.EYEBROW)
    ChoiceRow(
        TabletSoundCopy.CHOICE,
        TabletSoundCopy.MODES,
        settings.tabletSound.ordinal,
        { vm.setTabletSound(TabletSoundMode.entries[it]) },
        note = TabletSoundCopy.modeNote(settings.tabletSound),
    )
    // The slider shows the finger's value at once; the setting follows.
    var shown by remember(settings.tabletVolume) { mutableIntStateOf(settings.tabletVolume) }
    SliderRow(
        label = TabletSoundCopy.VOLUME,
        value = shown.toFloat(),
        range = 0f..100f,
        step = 1f,
        shown = Format.percent(shown),
        onChange = {
            shown = it.roundToInt()
            vm.setTabletVolume(shown)
        },
        description = "Tablet sound volume",
        stateDescription = Format.percent(shown),
        unit = "%",
    )
    SoundFontRow(sound, onDownload = vm::downloadTabletSound, onCancel = vm::cancelTabletSound, onRemove = vm::removeTabletSound)
}

/** The pause steps by half a second. */
private const val PRE_ROLL_STEP_MS = 500

/** "Off", "0.5 s", "1 s", "2 s", "2.5 s". */
private fun pauseLabel(ms: Int): String = if (ms == 0) "Off" else Format.seconds(ms)
