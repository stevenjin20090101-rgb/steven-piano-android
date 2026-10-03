// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import androidx.compose.runtime.Immutable
import dev.stevenjin.stevenpiano.ui.SettingsPage

/** A row of the Piano tab's hub: since v1.13 (M31b) every row opens a page; the switches and actions moved onto them. */
@Immutable
sealed interface HubRow {
    /** Opens [page]; its value comes from [GroupSummaries]. */
    data class Page(val page: SettingsPage) : HubRow
}

/** A group of the hub: its eyebrow and its rows. */
@Immutable
data class HubGroup(val title: String, val rows: List<HubRow>)

/**
 * The hub's groups, in order (DESIGN.md › v1.13 — M31b, decided by Steven from a preview): INSTRUMENTS
 * (what plays and what is played from), THE PIANO (the piano's own settings), PLAYING (how the app plays,
 * on the piano and on the tablet), SHARING (the web panel and its guests), THIS TABLET (the app on this
 * tablet: System first (v1.18 — M50: the tablet, the piano and what runs), its look and resting screen, kiosk,
 * updates, the library's artwork, help). A later feature adds
 * its page to [SettingsPage] and its row here, nowhere else; the search index ([SettingsIndex]) follows.
 */
object HubGroups {
    val all: List<HubGroup> = listOf(
        HubGroup("Instruments", pages(SettingsPage.Instrument, SettingsPage.Keyboard)),
        HubGroup(PIANO, pages(SettingsPage.Feel, SettingsPage.Lighting, SettingsPage.Pedal, SettingsPage.Firmware)),
        HubGroup("Playing", pages(SettingsPage.Playback, SettingsPage.TabletSound, SettingsPage.Schedule)),
        HubGroup("Sharing", pages(SettingsPage.Remote, SettingsPage.Guests)),
        HubGroup("This tablet", pages(SettingsPage.System, SettingsPage.Display, SettingsPage.Kiosk, SettingsPage.Updates, SettingsPage.Artwork, SettingsPage.Help)),
    )

    /** What the hub shows: a group with no rows yet shows nothing, not even its eyebrow. */
    val shown: List<HubGroup> = all.filter { it.rows.isNotEmpty() }

    /**
     * What the hub shows while a MIDI piano plays (v1.11 — M29): THE PIANO's pages belong to Steven Piano (its
     * sound and touch, lights, pedal and firmware), so they are hidden; [midiPiano] false, [shown].
     */
    fun shown(midiPiano: Boolean): List<HubGroup> = if (midiPiano) shown.filter { it.title != PIANO } else shown

    /** The group [page]'s row sits in. */
    fun groupOf(page: SettingsPage): HubGroup = all.first { group -> group.rows.any { it == HubRow.Page(page) } }

    /** THE PIANO group's title. */
    const val PIANO = "The piano"

    private fun pages(vararg pages: SettingsPage): List<HubRow> = pages.map { HubRow.Page(it) }
}
