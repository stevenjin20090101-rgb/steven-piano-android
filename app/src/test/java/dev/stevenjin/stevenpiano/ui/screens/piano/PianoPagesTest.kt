// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import dev.stevenjin.stevenpiano.piano.PianoFold
import dev.stevenjin.stevenpiano.piano.PianoPage
import dev.stevenjin.stevenpiano.piano.PianoRow
import dev.stevenjin.stevenpiano.piano.PianoSection
import dev.stevenjin.stevenpiano.piano.PianoSettings
import dev.stevenjin.stevenpiano.ui.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where everything on the Piano tab sits (v1.13 — M31b): the piano's settings on their pages and sections, and the hub's groups. */
class PianoPagesTest {
    @Test
    fun `every piano setting sits on one page, in one section, in the design's order`() {
        val expected = listOf(
            PianoSection.Loudness to listOf("fullpower", "volume"),
            PianoSection.Touch to listOf("velcurve", "velmult", "min", "minblack", "max", "keyforce_white", "keyforce_black"),
            PianoSection.Timing to listOf("humanvel", "humantime", "burstgap", "burstboost", "minstrike", "isostrike", "isogap", "gap", "hold", "restrike"),
            PianoSection.Release to listOf("softrelease", "releasepwm", "releasems"),
            PianoSection.Drive to listOf("freq"),
            PianoSection.Strip to listOf("leds", "ledmode", "ledbright", "reactcolor"),
            PianoSection.Layout to listOf("ledcount", "ledoffset", "ledscale", "ledtail", "ledreverse", "ledglow"),
            PianoSection.Motion to listOf("velbright", "decay", "rainspeed"),
            PianoSection.PianoScreen to listOf("dimsecs", "dimfloor"),
            PianoSection.Pedal to listOf("pedalon", "pedalhalf", "pedalup", "pedaldown"),
        )
        assertEquals(expected.flatMap { (section, names) -> names.map { section to it } }, PianoSettings.all.map { it.section to it.name })
        for (setting in PianoSettings.all) assertEquals(setting.name, setting.section.page, setting.page)
    }

    @Test
    fun `each page's sections, in order, under their eyebrows, the folded ones behind the page's one disclosure`() {
        fun titles(page: PianoPage) = PianoSettings.sections(page).map { it.title }
        assertEquals(listOf("Presets", "Loudness", "Touch", "Timing", "Release", "Drive", null), titles(PianoPage.Feel))
        assertEquals(listOf("Strip", "Layout", "Motion", "The piano's screen"), titles(PianoPage.Lighting))
        assertEquals("one section needs no eyebrow", listOf(null), titles(PianoPage.Pedal))
        assertEquals(listOf("Firmware", "Status"), titles(PianoPage.Firmware))
        assertEquals("every section is on one page", PianoSection.entries.toList(), PianoPage.entries.flatMap { PianoSettings.sections(it) })
        assertEquals(listOf(PianoSection.Touch, PianoSection.Timing, PianoSection.Release, PianoSection.Drive), PianoSettings.folded(PianoFold.FineTuning))
        assertEquals(listOf(PianoSection.Layout, PianoSection.Motion), PianoSettings.folded(PianoFold.StripSetup))
        assertEquals("Fine tuning", PianoFold.FineTuning.title)
        assertEquals("Strip set-up", PianoFold.StripSetup.title)
        for (fold in PianoFold.entries) {
            val sections = PianoSettings.sections(fold.page)
            val at = sections.indices.filter { sections[it].fold == fold }
            assertEquals("$fold's sections sit together, on its page", (at.first()..at.last()).toList(), at)
            assertEquals("one disclosure a page", listOf(fold), PianoFold.entries.filter { it.page == fold.page })
        }
        assertEquals(listOf("Sound and touch", "Lights and screen", "Pedal", "Firmware and status"), PianoPage.entries.map { it.title })
    }

    @Test
    fun `the rows that are not settings sit where the pages show them`() {
        assertEquals(listOf(PianoRow.Presets), PianoSettings.rows(PianoSection.Presets))
        val touch = PianoSettings.rows(PianoSection.Touch)
        assertEquals("the strike test closes TOUCH", PianoRow.StrikeTest, touch.last())
        assertEquals("where key force is set, under its two readings", PianoRow.KeyForceNote, touch[touch.size - 2])
        assertEquals("the LED test closes LAYOUT", PianoRow.TestLed, PianoSettings.rows(PianoSection.Layout).last())
        assertEquals(listOf("fw"), PianoSettings.rows(PianoSection.Firmware).map { (it as PianoRow.Reading).fact.name })
        assertEquals("Piano firmware", (PianoSettings.rows(PianoSection.Firmware).single() as PianoRow.Reading).fact.label)
        val status = PianoSettings.rows(PianoSection.Status)
        assertEquals(
            listOf("boards", "i2cfails", "pedalboard", "uptime", "read status"),
            status.map {
                when (it) {
                    is PianoRow.Reading -> it.fact.name
                    PianoRow.ReadStatus -> "read status"
                    else -> it.toString()
                }
            },
        )
        assertEquals("Save to the piano now ends Sound and touch", listOf(PianoRow.SaveNow), PianoSettings.rows(PianoSection.Save))
        assertEquals(PianoSection.Save, PianoSettings.sections(PianoPage.Feel).last())
        assertNull("not folded", PianoSection.Save.fold)
    }

    @Test
    fun `every setting and every fact shows exactly once across the pages`() {
        val rows = PianoPage.entries.flatMap { page -> PianoSettings.sections(page).flatMap { PianoSettings.rows(it) } }
        assertEquals(PianoSettings.all.map { it.name }, rows.filterIsInstance<PianoRow.Control>().map { it.setting.name })
        assertEquals(PianoSettings.facts, rows.filterIsInstance<PianoRow.Reading>().map { it.fact })
        for (single in listOf(PianoRow.Presets, PianoRow.StrikeTest, PianoRow.TestLed, PianoRow.KeyForceNote, PianoRow.ReadStatus, PianoRow.SaveNow)) {
            assertEquals(single.toString(), 1, rows.count { it == single })
        }
    }

    @Test
    fun `the hub's groups run instruments, the piano, playing, sharing, this tablet, as Steven chose them`() {
        assertEquals(listOf("Instruments", "The piano", "Playing", "Sharing", "This tablet"), HubGroups.all.map { it.title })
        assertEquals(HubGroups.all, HubGroups.shown)
        assertEquals(
            listOf(
                listOf(SettingsPage.Instrument, SettingsPage.Keyboard),
                listOf(SettingsPage.Feel, SettingsPage.Lighting, SettingsPage.Pedal, SettingsPage.Firmware),
                listOf(SettingsPage.Playback, SettingsPage.TabletSound, SettingsPage.Quiet),
                listOf(SettingsPage.Remote, SettingsPage.Guests),
                listOf(SettingsPage.System, SettingsPage.Display, SettingsPage.Kiosk, SettingsPage.Updates, SettingsPage.Artwork, SettingsPage.Help),
            ).map { pages -> pages.map { HubRow.Page(it) } },
            HubGroups.shown.map { it.rows },
        )
        assertEquals(
            listOf(
                listOf("Instrument", "Keyboard"),
                listOf("Sound and touch", "Lights and screen", "Pedal", "Firmware and status"),
                listOf("Playback", "Tablet sound", "Quiet times"),
                listOf("Web panel", "Guests"),
                listOf("System", "Display", "Kiosk", "Updates", "Library and artwork", "Help and about"),
            ),
            HubGroups.shown.map { group -> group.rows.map { (it as HubRow.Page).page.title } },
        )
        assertTrue("a group without rows still shows nothing", HubGroups.shown.none { it.rows.isEmpty() })
        assertEquals("Sharing", HubGroups.groupOf(SettingsPage.Guests).title)
    }

    @Test
    fun `while a MIDI piano plays THE PIANO group is hidden, everything else stays (v1_11 M29)`() {
        assertEquals(HubGroups.shown, HubGroups.shown(midiPiano = false))
        assertEquals(listOf("Instruments", "Playing", "Sharing", "This tablet"), HubGroups.shown(midiPiano = true).map { it.title })
        val hidden = HubGroups.shown.flatMap { it.rows } - HubGroups.shown(midiPiano = true).flatMap { it.rows }.toSet()
        assertEquals("only the piano's own pages", PianoPage.entries.toList(), hidden.map { ((it as HubRow.Page).page).piano })
    }

    @Test
    fun `every page opens from exactly one row of the hub, the piano's from THE PIANO`() {
        val opened = HubGroups.all.flatMap { it.rows }.filterIsInstance<HubRow.Page>().map { it.page }
        assertEquals(SettingsPage.entries.toList(), opened)
        val piano = HubGroups.all.single { it.title == HubGroups.PIANO }.rows.map { (it as HubRow.Page).page }
        assertEquals(SettingsPage.entries.filter { it.piano != null }, piano)
        assertEquals(PianoPage.entries.toList(), piano.map { it.piano })
    }
}
