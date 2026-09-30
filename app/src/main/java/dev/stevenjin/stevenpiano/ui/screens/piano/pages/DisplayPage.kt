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
import dev.stevenjin.stevenpiano.settings.Appearance
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.settings.StandbyCanvas
import dev.stevenjin.stevenpiano.settings.StandbyShows
import dev.stevenjin.stevenpiano.settings.WideLayout
import dev.stevenjin.stevenpiano.ui.ArtworkCopy
import dev.stevenjin.stevenpiano.ui.LocalAppFrame
import dev.stevenjin.stevenpiano.ui.components.ChoiceRow
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.label
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoViewModel

/** Under the Hand colours switch: what it colours, and what it leaves alone. */
private const val HAND_COLOURS_NOTE = "Colours the two hands on the waterfall and the keyboard strip"

/** Under Display mode after a minute: what it is, and for whom (the art and the notes, or the roll: Standby shows). */
private const val DISPLAY_MODE_NOTE = "The piece's art and title fill the screen for passers-by"

/**
 * Display: how the app and Now playing look. APPEARANCE (Follow system, Light, Dark: the app's
 * whole look, at once) · NOTES (Note display as chips: Paper roll, Falling notes, and Score on
 * phones; on wide screens, where the score has its own place, the roll's style, with Wide layout
 * under it; Fingering, Chord names, Hand colours) · ARTWORK (black and white, and fetching it
 * automatically, with what that sends) · STANDBY (display mode after a minute without a touch, its
 * canvas: black, or the app's own, and what it shows: the art and notes, or the paper roll). The hub's
 * row reads the note display's name.
 */
@Composable
fun DisplayPage(settings: PianoSettings, vm: PianoViewModel) {
    val frame = LocalAppFrame.current
    SectionEyebrow("Appearance")
    ChoiceRow("Appearance", Appearance.entries.map { it.label }, settings.appearance.ordinal, { vm.setAppearance(Appearance.entries[it]) })
    SectionEyebrow("Notes")
    // On wide screens the score has its own place, so Note display picks the roll's style there.
    val choices = frame.noteDisplayChoices
    val display = if (frame.wide) settings.noteDisplay.rollStyle else settings.noteDisplay
    ChoiceRow("Note display", choices.map { it.label }, choices.indexOf(display).takeIf { it >= 0 }, { vm.setNoteDisplay(choices[it]) })
    if (frame.wide) {
        ChoiceRow("Wide layout", WideLayout.entries.map { it.label }, settings.wideLayout.ordinal, { vm.setWideLayout(WideLayout.entries[it]) })
    }
    SwitchRow("Fingering", settings.fingering, vm::setFingering)
    SwitchRow("Chord names", settings.chordNames, vm::setChordNames)
    SwitchRow("Hand colours", settings.handColours, vm::setHandColours, note = HAND_COLOURS_NOTE)
    SectionEyebrow("Artwork")
    SwitchRow("Artwork in black and white", settings.artworkMonochrome, vm::setArtworkMonochrome)
    SwitchRow("Fetch artwork automatically", settings.fetchArtworkAutomatically, vm::setFetchArtworkAutomatically, note = ArtworkCopy.TRANSPARENCY)
    SectionEyebrow("Standby")
    SwitchRow("Display mode after a minute", settings.displayModeAfterMinute, vm::setDisplayModeAfterMinute, note = DISPLAY_MODE_NOTE)
    ChoiceRow("Standby canvas", StandbyCanvas.entries.map { it.label }, settings.standbyCanvas.ordinal, { vm.setStandbyCanvas(StandbyCanvas.entries[it]) })
    ChoiceRow("Standby shows", StandbyShows.entries.map { it.label }, settings.standbyShows.ordinal, { vm.setStandbyShows(StandbyShows.entries[it]) })
}
