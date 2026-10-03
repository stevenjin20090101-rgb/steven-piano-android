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
import dev.stevenjin.stevenpiano.ui.SettingNotes
import dev.stevenjin.stevenpiano.ui.components.ChoiceRow
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.label
import dev.stevenjin.stevenpiano.ui.screens.piano.Anchored
import dev.stevenjin.stevenpiano.ui.screens.piano.PageRows
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoViewModel

/** Under Resting screen after a minute: what it is, and for whom (the art and the notes, or the roll: What it shows). */
private const val RESTING_NOTE = "The piece's art and title fill the screen for passers-by"

/**
 * Display (THIS TABLET since v1.13 — M31b): how the app looks on this tablet. APPEARANCE (Follow system,
 * Light, Dark: the app's whole look, at once; Artwork in black and white; Album colours behind the player, v1.15 —
 * M41, also in Now playing's View menu) · RESTING SCREEN ("Display mode"
 * and STANDBY until v1.13: the resting screen after a minute without a touch, its background: black, or
 * the app's own, and what it shows: the art and notes, or the paper roll). The note views and their
 * options live in Now playing's View menu (M31a); fetching artwork is under Library and artwork. The hub's
 * row reads the appearance.
 */
@Composable
fun DisplayPage(settings: PianoSettings, vm: PianoViewModel) {
    SectionEyebrow(PageRows.APPEARANCE_SECTION)
    Anchored(PageRows.APPEARANCE.anchor) {
        ChoiceRow(PageRows.APPEARANCE.label, Appearance.entries.map { it.label }, settings.appearance.ordinal, { vm.setAppearance(Appearance.entries[it]) })
    }
    Anchored(PageRows.MONOCHROME.anchor) {
        SwitchRow(PageRows.MONOCHROME.label, settings.artworkMonochrome, vm::setArtworkMonochrome)
    }
    Anchored(PageRows.ALBUM_BACKDROP.anchor) {
        SwitchRow(PageRows.ALBUM_BACKDROP.label, settings.albumBackdrop, vm::setAlbumBackdrop, note = SettingNotes.ALBUM_BACKDROP)
    }
    SectionEyebrow(PageRows.RESTING_SECTION)
    Anchored(PageRows.RESTING.anchor) {
        SwitchRow(PageRows.RESTING.label, settings.displayModeAfterMinute, vm::setDisplayModeAfterMinute, note = RESTING_NOTE)
    }
    Anchored(PageRows.RESTING_BACKGROUND.anchor) {
        ChoiceRow(
            PageRows.RESTING_BACKGROUND.label,
            StandbyCanvas.entries.map { it.label },
            settings.standbyCanvas.ordinal,
            { vm.setStandbyCanvas(StandbyCanvas.entries[it]) },
            note = SettingNotes.RESTING_BACKGROUND,
        )
    }
    Anchored(PageRows.RESTING_SHOWS.anchor) {
        ChoiceRow(
            PageRows.RESTING_SHOWS.label,
            StandbyShows.entries.map { it.label },
            settings.standbyShows.ordinal,
            { vm.setStandbyShows(StandbyShows.entries[it]) },
            note = SettingNotes.RESTING_SHOWS,
        )
    }
}
