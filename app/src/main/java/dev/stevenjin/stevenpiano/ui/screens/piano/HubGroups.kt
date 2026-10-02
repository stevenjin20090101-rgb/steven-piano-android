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

/** A row of the Piano tab's hub. */
@Immutable
sealed interface HubRow {
    /** Opens [page]; its value comes from [GroupSummaries]. */
    data class Page(val page: SettingsPage) : HubRow

    /** Auto-connect on launch (a switch). */
    data object AutoConnect : HubRow

    /** Check for updates automatically (a switch). */
    data object CheckForUpdates : HubRow

    /** Check now, with what the last check found under it. */
    data object CheckNow : HubRow

    /** Share diagnostics, with what it sends under it. */
    data object ShareDiagnostics : HubRow
}

/** A group of the hub: its eyebrow and its rows. */
@Immutable
data class HubGroup(val title: String, val rows: List<HubRow>)

/**
 * The hub's groups, in order (DESIGN.md › v1.5): INSTRUMENTS (v1.11 — M29: what plays and what is played
 * from, under the connection card), PIANO (the piano's own settings), PLAYING (how the app plays and shows
 * pieces), CONTROL (ways to run the piano from elsewhere), APP (the app itself). A later feature adds its
 * page to [SettingsPage] and its row here, nowhere else.
 */
object HubGroups {
    val all: List<HubGroup> = listOf(
        HubGroup("Instruments", pages(SettingsPage.Instrument, SettingsPage.Keyboard)),
        HubGroup("Piano", pages(SettingsPage.Feel, SettingsPage.Lighting, SettingsPage.Pedal, SettingsPage.Firmware)),
        HubGroup("Playing", pages(SettingsPage.Playback, SettingsPage.Display, SettingsPage.Schedule)),
        HubGroup("Control", pages(SettingsPage.Remote, SettingsPage.Kiosk, SettingsPage.Studio)),
        HubGroup("App", listOf(HubRow.AutoConnect, HubRow.CheckForUpdates, HubRow.CheckNow, HubRow.ShareDiagnostics)),
    )

    /** What the hub shows: a group with no rows yet shows nothing, not even its eyebrow. */
    val shown: List<HubGroup> = all.filter { it.rows.isNotEmpty() }

    /**
     * What the hub shows while a MIDI piano plays (v1.11 — M29): the PIANO group's pages belong to Steven Piano (its
     * feel, lights, pedal and firmware), so they are hidden; [midiPiano] false, [shown].
     */
    fun shown(midiPiano: Boolean): List<HubGroup> = if (midiPiano) shown.filter { it.title != PIANO } else shown

    /** The PIANO group's title. */
    const val PIANO = "Piano"

    private fun pages(vararg pages: SettingsPage): List<HubRow> = pages.map { HubRow.Page(it) }
}
