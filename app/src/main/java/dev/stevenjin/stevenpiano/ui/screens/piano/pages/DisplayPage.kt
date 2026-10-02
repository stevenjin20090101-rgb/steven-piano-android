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
import dev.stevenjin.stevenpiano.ui.ArtworkCopy
import dev.stevenjin.stevenpiano.ui.components.ChoiceRow
import dev.stevenjin.stevenpiano.ui.components.SectionEyebrow
import dev.stevenjin.stevenpiano.ui.components.SwitchRow
import dev.stevenjin.stevenpiano.ui.label
import dev.stevenjin.stevenpiano.ui.screens.piano.PianoViewModel

/** Under Display mode after a minute: what it is, and for whom (the art and the notes, or the roll: Standby shows). */
private const val DISPLAY_MODE_NOTE = "The piece's art and title fill the screen for passers-by"

/**
 * Display: how the app looks. APPEARANCE (Follow system, Light, Dark: the app's whole look, at once) ·
 * ARTWORK (black and white, and fetching it automatically, with what that sends) · STANDBY (display
 * mode after a minute without a touch, its canvas: black, or the app's own, and what it shows: the art
 * and notes, or the paper roll). The hub's row reads the appearance. How the notes look (their style,
 * Fingering, Chord names, Hand colours) and how the score and the notes share the screen moved to Now
 * playing's View menu (DESIGN.md › v1.12).
 */
@Composable
fun DisplayPage(settings: PianoSettings, vm: PianoViewModel) {
    SectionEyebrow("Appearance")
    ChoiceRow("Appearance", Appearance.entries.map { it.label }, settings.appearance.ordinal, { vm.setAppearance(Appearance.entries[it]) })
    SectionEyebrow("Artwork")
    SwitchRow("Artwork in black and white", settings.artworkMonochrome, vm::setArtworkMonochrome)
    SwitchRow("Fetch artwork automatically", settings.fetchArtworkAutomatically, vm::setFetchArtworkAutomatically, note = ArtworkCopy.TRANSPARENCY)
    SectionEyebrow("Standby")
    SwitchRow("Display mode after a minute", settings.displayModeAfterMinute, vm::setDisplayModeAfterMinute, note = DISPLAY_MODE_NOTE)
    ChoiceRow("Standby canvas", StandbyCanvas.entries.map { it.label }, settings.standbyCanvas.ordinal, { vm.setStandbyCanvas(StandbyCanvas.entries[it]) })
    ChoiceRow("Standby shows", StandbyShows.entries.map { it.label }, settings.standbyShows.ordinal, { vm.setStandbyShows(StandbyShows.entries[it]) })
}
