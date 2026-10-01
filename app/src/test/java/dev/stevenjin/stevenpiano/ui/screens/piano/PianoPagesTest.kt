// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import dev.stevenjin.stevenpiano.piano.PianoPage
import dev.stevenjin.stevenpiano.piano.PianoRow
import dev.stevenjin.stevenpiano.piano.PianoSection
import dev.stevenjin.stevenpiano.piano.PianoSettings
import dev.stevenjin.stevenpiano.ui.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where everything on the Piano tab sits: the piano's settings on their pages and sections, and the hub's groups. */
class PianoPagesTest {
    @Test
    fun `every piano setting sits on one page, in one section, in the design's order`() {
        val expected = listOf(
            PianoSection.Loudness to listOf("fullpower", "volume"),
            PianoSection.Touch to listOf("velcurve", "velmult", "min", "minblack", "max"),
            PianoSection.Timing to listOf("humanvel", "humantime", "burstgap", "burstboost", "minstrike", "isostrike", "isogap", "gap", "hold", "restrike"),
            PianoSection.Release to listOf("softrelease", "releasepwm", "releasems"),
            PianoSection.Drive to listOf("freq"),
            PianoSection.Strip to listOf("leds", "ledmode", "ledbright", "reactcolor"),
            PianoSection.Layout to listOf("ledcount", "ledoffset", "ledscale", "ledtail", "ledreverse", "ledglow"),
            PianoSection.Motion to listOf("velbright", "decay", "rainspeed"),
            PianoSection.PianoScreen to listOf("dimsecs", "dimfloor"),
            PianoSection.Pedal to listOf("pedalon", "pedalhalf", "pedalup", "pedaldown"),
            PianoSection.Status to listOf("keyforce_white", "keyforce_black"),
        )
        assertEquals(expected.flatMap { (section, names) -> names.map { section to it } }, PianoSettings.all.map { it.section to it.name })
        for (setting in PianoSettings.all) assertEquals(setting.name, setting.section.page, setting.page)
    }

    @Test
    fun `each page's sections, in order, under their eyebrows`() {
        fun titles(page: PianoPage) = PianoSettings.sections(page).map { it.title }
        assertEquals(listOf("Presets", "Loudness", "Touch", "Timing", "Release", "Drive"), titles(PianoPage.Feel))
        assertEquals(listOf("Strip", "Layout", "Motion", "Piano's screen"), titles(PianoPage.Lighting))
        assertEquals("one section needs no eyebrow", listOf(null), titles(PianoPage.Pedal))
        assertEquals(listOf("Firmware", "Status", "Actions"), titles(PianoPage.Firmware))
        assertEquals("every section is on one page", PianoSection.entries.toList(), PianoPage.entries.flatMap { PianoSettings.sections(it) })
    }

    @Test
    fun `the rows that are not settings sit where the pages show them`() {
        assertEquals(listOf(PianoRow.Presets), PianoSettings.rows(PianoSection.Presets))
        assertEquals("the strike test closes TOUCH", PianoRow.StrikeTest, PianoSettings.rows(PianoSection.Touch).last())
        assertEquals("the LED test closes LAYOUT", PianoRow.TestLed, PianoSettings.rows(PianoSection.Layout).last())
        assertEquals(listOf("fw"), PianoSettings.rows(PianoSection.Firmware).map { (it as PianoRow.Reading).fact.name })
        assertEquals("Piano firmware", (PianoSettings.rows(PianoSection.Firmware).single() as PianoRow.Reading).fact.label)
        val status = PianoSettings.rows(PianoSection.Status)
        assertEquals(
            listOf("boards", "i2cfails", "pedalboard", "uptime", "keyforce_white", "keyforce_black", "note"),
            status.map {
                when (it) {
                    is PianoRow.Reading -> it.fact.name
                    is PianoRow.Control -> it.setting.name
                    PianoRow.KeyForceNote -> "note"
                    else -> it.toString()
                }
            },
        )
        assertEquals(listOf(PianoRow.Actions), PianoSettings.rows(PianoSection.Actions))
    }

    @Test
    fun `every setting and every fact shows exactly once across the pages`() {
        val rows = PianoPage.entries.flatMap { page -> PianoSettings.sections(page).flatMap { PianoSettings.rows(it) } }
        assertEquals(PianoSettings.all.map { it.name }, rows.filterIsInstance<PianoRow.Control>().map { it.setting.name })
        assertEquals(PianoSettings.facts, rows.filterIsInstance<PianoRow.Reading>().map { it.fact })
        for (single in listOf(PianoRow.Presets, PianoRow.StrikeTest, PianoRow.TestLed, PianoRow.KeyForceNote, PianoRow.Actions)) {
            assertEquals(single.toString(), 1, rows.count { it == single })
        }
    }

    @Test
    fun `the hub's groups run instruments, piano, playing, control, app, playing has Schedule and control has Remote control, Kiosk and Studio`() {
        assertEquals(listOf("Instruments", "Piano", "Playing", "Control", "App"), HubGroups.all.map { it.title })
        assertEquals("with its first row the CONTROL eyebrow shows", listOf("Instruments", "Piano", "Playing", "Control", "App"), HubGroups.shown.map { it.title })
        assertEquals(
            listOf(
                listOf(HubRow.Page(SettingsPage.Keyboard)),
                listOf(HubRow.Page(SettingsPage.Feel), HubRow.Page(SettingsPage.Lighting), HubRow.Page(SettingsPage.Pedal), HubRow.Page(SettingsPage.Firmware)),
                listOf(HubRow.Page(SettingsPage.Playback), HubRow.Page(SettingsPage.Display), HubRow.Page(SettingsPage.Schedule)),
                listOf(HubRow.Page(SettingsPage.Remote), HubRow.Page(SettingsPage.Kiosk), HubRow.Page(SettingsPage.Studio)),
                listOf(HubRow.AutoConnect, HubRow.CheckForUpdates, HubRow.CheckNow, HubRow.ShareDiagnostics),
            ),
            HubGroups.shown.map { it.rows },
        )
        assertTrue("a group without rows still shows nothing", HubGroups.shown.none { it.rows.isEmpty() })
    }

    @Test
    fun `every page opens from exactly one row of the hub, the piano's from PIANO`() {
        val opened = HubGroups.all.flatMap { it.rows }.filterIsInstance<HubRow.Page>().map { it.page }
        assertEquals(SettingsPage.entries.toList(), opened)
        val piano = HubGroups.all.single { it.title == "Piano" }.rows.map { (it as HubRow.Page).page }
        assertEquals(SettingsPage.entries.filter { it.piano != null }, piano)
        assertEquals(PianoPage.entries.toList(), piano.map { it.piano })
    }
}
