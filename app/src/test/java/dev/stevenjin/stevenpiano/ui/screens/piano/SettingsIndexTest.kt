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
import dev.stevenjin.stevenpiano.piano.PianoRow
import dev.stevenjin.stevenpiano.piano.PianoSettings
import dev.stevenjin.stevenpiano.ui.SettingNotes
import dev.stevenjin.stevenpiano.ui.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Piano tab's search (v1.13 — M31b): every row of every page indexed once, found by label, synonym, folded words and prefixes. */
class SettingsIndexTest {
    private val rows = SettingsIndex.entries.mapNotNull { entry -> (entry.target as? SettingsTarget.Row)?.let { entry to it } }

    private fun first(query: String): SettingsEntry = SettingsIndex.search(query).first()

    @Test
    fun `every page and every row of every page is indexed exactly once`() {
        for (page in SettingsPage.entries) {
            assertEquals("$page's own entry", 1, rows.count { (_, target) -> target.page == page && target.anchor == null })
        }
        // The piano's pages: every row of the table but the key-force note, with its section's fold.
        for (page in SettingsPage.entries.filter { it.piano != null }) {
            for (section in PianoSettings.sections(page.piano!!)) {
                for (row in PianoSettings.rows(section)) {
                    val anchor = SettingsIndex.anchorOf(row)
                    if (row == PianoRow.KeyForceNote) {
                        assertEquals(null, anchor)
                        continue
                    }
                    val found = rows.filter { (_, target) -> target.page == page && target.anchor == anchor }
                    assertEquals("$page › $section › $row", 1, found.size)
                    assertEquals("$row opens its fold", section.fold, found.single().second.fold)
                }
            }
        }
        // The app's pages: every row of their table, on its page.
        for (row in PageRows.all) {
            val found = rows.filter { (_, target) -> target.page == row.page && target.anchor == row.anchor }
            assertEquals(row.anchor, 1, found.size)
            assertEquals(row.label, found.single().first.label)
        }
        val anchors = rows.mapNotNull { it.second.anchor }
        assertEquals("no anchor twice", anchors.size, anchors.toSet().size)
        val pianoRows = SettingsPage.entries.mapNotNull { it.piano }.flatMap { PianoSettings.sections(it) }.flatMap { PianoSettings.rows(it) }.count { it != PianoRow.KeyForceNote }
        assertEquals("nothing else", pianoRows + PageRows.all.size, anchors.size)
        assertTrue("every app row's page is in the hub", PageRows.all.all { row -> HubGroups.all.any { group -> HubRow.Page(row.page) in group.rows } })
    }

    @Test
    fun `a row is found by its label, with its path, and by a synonym`() {
        val curve = first("Velocity curve")
        assertEquals("Velocity curve", curve.label)
        assertEquals("The piano › Sound and touch › Fine tuning", curve.eyebrow)
        assertEquals(SettingsTarget.Row(SettingsPage.Feel, "velcurve", PianoFold.FineTuning), curve.target)
        assertEquals("The piano › Sound and touch › Loudness", first("piano volume").eyebrow)
        assertEquals(SettingsTarget.Row(SettingsPage.Lighting, "test-led", PianoFold.StripSetup), first("test led").target)
        assertEquals("Web panel", first("web control").label)
        assertEquals("Resting screen after a minute", first("standby").label)
        assertEquals("Appearance", first("dark mode").label)
        assertEquals("Sharing › Web panel › Over the internet", first("cloud").eyebrow)
        assertEquals("This tablet › Display › Resting screen", first("resting screen after").eyebrow)
        assertEquals("a page by its name", SettingsTarget.Row(SettingsPage.Guests, null), first("guests").target)
    }

    @Test
    fun `matching is on folded words and prefixes`() {
        assertEquals("Velocity curve", first("VEL CUR").label)
        assertEquals("Velocity curve", first("  velocity   curve ").label)
        assertEquals("Tablet volume", first("tab vol").label)
        assertEquals("Also on Wi-Fi", first("wifi").label)
        assertEquals("Also on Wi-Fi", first("wi-fi").label)
        assertEquals("I²C errors", first("i2c").label)
        assertEquals("Half-pedalling", first("pédal half").label)
        assertEquals("Steven's library", first("stevens").label)
        assertEquals(SettingsIndex.words("Black-key floor (0 = same as white)").toSet(), setOf("black", "key", "floor", "0", "same", "as", "white", "blackkey"))
        assertTrue("every word typed must match", SettingsIndex.search("velocity zebra").isEmpty())
        assertTrue("a word inside another doesn't match", SettingsIndex.search("ocity").isEmpty())
        assertTrue(SettingsIndex.search("").isEmpty())
        assertTrue(SettingsIndex.search(" - ").isEmpty())
        assertEquals("No setting matches.", SettingsIndex.NO_MATCH)
        assertEquals("Search settings", SettingsIndex.PLACEHOLDER)
    }

    @Test
    fun `label hits come first, in the hub's order`() {
        val volume = SettingsIndex.search("volume").map { it.label }
        assertEquals(listOf("Piano volume", "Tablet volume", "Channel volume"), volume.take(3))
        // Quiet times (v1.20 — M54): its own name first, then what only a synonym names ("downtime", "class", "schedule").
        val quiet = SettingsIndex.search("quiet time").map { it.label }
        assertEquals("Quiet times", quiet.first())
        for (word in listOf("downtime", "silent", "class", "schedule")) {
            assertTrue("$word finds Quiet times", SettingsIndex.search(word).any { it.label == "Quiet times" })
        }
        assertTrue("then what only a synonym names", "Add section" in SettingsIndex.search("schedule").map { it.label })
    }

    @Test
    fun `what moved elsewhere opens that screen`() {
        val fingering = first("fingering")
        assertEquals(SettingsTarget.Away(Elsewhere.NowPlayingView), fingering.target)
        assertEquals("Now playing › View", fingering.eyebrow)
        assertEquals(SettingsTarget.Away(Elsewhere.Studio), first("models").target)
        assertEquals("Studio", first("models").eyebrow)
        assertEquals(SettingsTarget.Away(Elsewhere.Studio), first("compose a piece").target)
        assertEquals("Note display", first("waterfall").label)
        assertEquals("the View menu's Show replaced Wide layout (v1.12)", "Show", first("wide layout").label)
        assertEquals(SettingsTarget.Away(Elsewhere.NowPlayingView), first("wide layout").target)
        assertTrue("no Wide layout any more", SettingsIndex.entries.none { it.label == "Wide layout" })
        for (moved in listOf("Show", "Note display", "Fingering", "Chord names", "Hand colours")) {
            assertEquals(moved, "Now playing › View", SettingsIndex.entries.single { it.label == moved }.eyebrow)
        }
    }

    @Test
    fun `while a MIDI piano plays, Steven Piano's own rows aren't offered`() {
        assertTrue(SettingsIndex.search("velocity curve").isNotEmpty())
        assertTrue(SettingsIndex.search("velocity curve", midiPiano = true).isEmpty())
        assertFalse(SettingsIndex.search("volume", midiPiano = true).any { it.label == "Piano volume" })
        assertTrue(SettingsIndex.search("volume", midiPiano = true).any { it.label == "Tablet volume" })
    }

    @Test
    fun `the new notes are one plain line each, sentence case, under 60 characters`() {
        for (note in SettingNotes.all) {
            assertTrue(note, note.length < 60)
            assertTrue(note, note.first().isUpperCase())
            assertFalse(note, note.endsWith("."))
        }
        for (name in SettingNotes.piano.keys) assertTrue("$name is a setting", PianoSettings.named(name) != null)
        assertEquals("the table takes them", SettingNotes.piano["velcurve"], PianoSettings.named("velcurve")!!.note)
        assertEquals("an older note stays", "A new length maps notes at once; the strip itself follows after the piano restarts.", PianoSettings.named("ledcount")!!.note)
    }
}
