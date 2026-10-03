// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.stevenjin.stevenpiano.audio.TabletSoundMode
import dev.stevenjin.stevenpiano.player.DynamicRange
import dev.stevenjin.stevenpiano.player.ExpressionLevel
import dev.stevenjin.stevenpiano.player.Performance
import dev.stevenjin.stevenpiano.player.PlaybackLimits
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.ui.Format
import dev.stevenjin.stevenpiano.ui.PlaybackCopy
import dev.stevenjin.stevenpiano.ui.SettingNotes
import dev.stevenjin.stevenpiano.ui.TabletSoundCopy
import dev.stevenjin.stevenpiano.ui.components.ChoiceRow
import dev.stevenjin.stevenpiano.ui.components.NoteLine
import dev.stevenjin.stevenpiano.ui.components.SectionRule
import dev.stevenjin.stevenpiano.ui.components.SliderRow
import dev.stevenjin.stevenpiano.ui.components.SoundFontRow
import dev.stevenjin.stevenpiano.ui.components.StepperControl
import dev.stevenjin.stevenpiano.ui.components.StepperRow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.screens.piano.Anchored
import dev.stevenjin.stevenpiano.ui.screens.piano.PageRows
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoViewModel
import kotlin.math.roundToInt

/**
 * Playback: how the app sends a piece to the piano, in one section. Pause before each piece (Off,
 * then half seconds up to 5 s; 2 s at first), Default tempo, Transpose and Velocity (steppers), with the line under
 * Velocity on the piano's Full power; then how a piece is played (v1.16 — M44): Dynamic range (chips), Quietest note
 * (a stepper), Expression (chips) and Re-strike time (Auto, then 60–250 ms); then Fold notes outside C1–B7 and Skip
 * drum channel (switches), each with its one-line note (v1.13). The hub's row reads the pause and the default tempo,
 * "2 s pause · 100%". The tablet's own sound has its own page since v1.13 ([TabletSoundPage]).
 */
@Composable
fun PlaybackPage(settings: PianoSettings, vm: PianoViewModel) {
    val piano by vm.piano.collectAsStateWithLifecycle()
    SectionRule()
    Anchored(PageRows.PAUSE.anchor) {
        StepperRow(PageRows.PAUSE.label) {
            StepperControl(settings.preRollMs, PlaybackLimits.PreRollMs, PRE_ROLL_STEP_MS, ::pauseLabel, "Shorter pause", "Longer pause", vm::setPreRoll)
        }
    }
    Anchored(PageRows.DEFAULT_TEMPO.anchor) {
        StepperRow(PageRows.DEFAULT_TEMPO.label, note = SettingNotes.DEFAULT_TEMPO) {
            StepperControl(settings.defaultTempoPct, PlaybackLimits.TempoPct, 5, Format::percent, "Slower default tempo", "Faster default tempo", vm::setDefaultTempo, rolling = true)
        }
    }
    Anchored(PageRows.TRANSPOSE.anchor) {
        StepperRow(PageRows.TRANSPOSE.label) {
            StepperControl(settings.transpose, PlaybackLimits.Transpose, 1, Format::semitones, "Transpose down a semitone", "Transpose up a semitone", vm::setTranspose)
        }
    }
    Anchored(PageRows.VELOCITY.anchor) {
        // Its note, then what the piano's Full power means for every row below it (v1.16 — M44).
        StepperRow(
            PageRows.VELOCITY.label,
            below = {
                NoteLine(SettingNotes.VELOCITY)
                NoteLine(PlaybackCopy.fullPower(piano), Modifier.padding(bottom = 4.dp))
            },
        ) {
            StepperControl(settings.velocityPct, PlaybackLimits.VelocityPct, 5, Format::percent, "Play softer", "Play louder", vm::setVelocity)
        }
    }
    Anchored(PageRows.DYNAMIC_RANGE.anchor) {
        ChoiceRow(
            PageRows.DYNAMIC_RANGE.label,
            PlaybackCopy.RANGES,
            settings.dynamicRange.ordinal,
            { vm.setDynamicRange(DynamicRange.entries[it]) },
            note = SettingNotes.DYNAMIC_RANGE,
        )
    }
    Anchored(PageRows.QUIETEST_NOTE.anchor) {
        StepperRow(PageRows.QUIETEST_NOTE.label, note = SettingNotes.QUIETEST_NOTE) {
            StepperControl(
                settings.velocityFloor, PlaybackLimits.VelocityFloor, FLOOR_STEP, Int::toString, "Lower the quietest note", "Raise the quietest note",
                { vm.setVelocityFloor(floorStep(it)) },
            )
        }
    }
    Anchored(PageRows.EXPRESSION.anchor) {
        ChoiceRow(
            PageRows.EXPRESSION.label,
            PlaybackCopy.EXPRESSIONS,
            settings.expression.ordinal,
            { vm.setExpression(ExpressionLevel.entries[it]) },
            note = SettingNotes.EXPRESSION,
        )
    }
    Anchored(PageRows.RESTRIKE.anchor) {
        val pianoMs = PlaybackCopy.pianoRepeatMs(piano)
        StepperRow(PageRows.RESTRIKE.label, note = SettingNotes.RESTRIKE) {
            StepperControl(
                settings.restrikeMs, Performance.AUTO..PlaybackLimits.RestrikeMs.last, PlaybackLimits.RESTRIKE_STEP_MS,
                { PlaybackCopy.restrike(it, pianoMs) }, "Shorter re-strike time", "Longer re-strike time",
                { vm.setRestrike(restrikeStep(settings.restrikeMs, it)) },
            )
        }
    }
    Anchored(PageRows.FOLD.anchor) { SwitchRow(PageRows.FOLD.label, settings.foldOutOfRange, vm::setFold, note = SettingNotes.FOLD) }
    Anchored(PageRows.SKIP_DRUMS.anchor) { SwitchRow(PageRows.SKIP_DRUMS.label, settings.skipDrumChannel, vm::setSkipDrums, note = SettingNotes.SKIP_DRUMS) }
}

/**
 * Tablet sound (DESIGN.md › v1.8 — M25; its own page in PLAYING since v1.13): Piano sound on the tablet (Off ·
 * When the piano isn't connected · Always, with what the choice does), Tablet volume, and the SoundFont's row
 * (Download, its progress with Cancel, or Installed with Remove). The hub's row reads "Off" or "Always · 70%".
 */
@Composable
fun TabletSoundPage(settings: PianoSettings, vm: PianoViewModel) {
    val sound by vm.tabletSound.collectAsStateWithLifecycle()
    SectionRule()
    Anchored(PageRows.TABLET_SOUND.anchor) {
        ChoiceRow(
            TabletSoundCopy.CHOICE,
            TabletSoundCopy.MODES,
            settings.tabletSound.ordinal,
            { vm.setTabletSound(TabletSoundMode.entries[it]) },
            note = TabletSoundCopy.modeNote(settings.tabletSound),
        )
    }
    // The slider shows the finger's value at once; the setting follows.
    var shown by remember(settings.tabletVolume) { mutableIntStateOf(settings.tabletVolume) }
    Anchored(PageRows.TABLET_VOLUME.anchor) {
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
            description = TabletSoundCopy.VOLUME,
            stateDescription = Format.percent(shown),
            unit = "%",
        )
    }
    Anchored(PageRows.SOUND_FILE.anchor) {
        SoundFontRow(sound, onDownload = vm::downloadTabletSound, onCancel = vm::cancelTabletSound, onRemove = vm::removeTabletSound)
    }
}

/** The pause steps by half a second. */
private const val PRE_ROLL_STEP_MS = 500

/** The quietest note steps by five: 1, 5, 10 … 60. */
private const val FLOOR_STEP = 5

/** A step of the quietest note, landing on 1 or a multiple of five. */
private fun floorStep(velocity: Int): Int = if (velocity <= 1) 1 else (velocity + FLOOR_STEP / 2) / FLOOR_STEP * FLOOR_STEP

/** A step of the re-strike time from [from] to [to]: between Auto and 60 ms there is nothing, so it goes to the other. */
private fun restrikeStep(from: Int, to: Int): Int = when {
    to <= Performance.AUTO -> Performance.AUTO
    to < PlaybackLimits.RestrikeMs.first -> if (to > from) PlaybackLimits.RestrikeMs.first else Performance.AUTO
    else -> to
}

/** "Off", "0.5 s", "1 s", "2 s", "2.5 s". */
private fun pauseLabel(ms: Int): String = if (ms == 0) "Off" else Format.seconds(ms)
