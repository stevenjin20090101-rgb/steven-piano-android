// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.piano.PianoPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {
    @Test
    fun `the five tabs keep their paths, labels and order - Studio between Keys and Piano (v1_12)`() {
        assertEquals(listOf("library", "now-playing", "keys", "studio", "piano"), Route.entries.map { it.path })
        assertEquals(listOf("Library", "Now playing", "Keys", "Studio", "Piano"), Route.entries.map { it.label })
        for (route in Route.entries) assertEquals(route, Route.of(route.path))
    }

    @Test
    fun `a destination inside the Piano tab belongs to it, so the bar still shows Piano`() {
        assertEquals(Route.Piano, Route.of(PianoRoutes.HUB))
        assertEquals(Route.Piano, Route.of(PianoRoutes.PAGE))
        assertEquals(Route.Piano, Route.of("piano/feel"))
        assertEquals(Route.Piano, Route.of(PianoRoutes.page(SettingsPage.Display, cut = true)))
        assertEquals("EXTRA_TAB's value opens the tab (on its hub)", Route.Piano, Route.of("piano"))
        assertNull(Route.of("pianola"))
        assertNull(Route.of(""))
        assertNull(Route.of(null))
        assertEquals(Route.NowPlaying, Route.of("now-playing"))
        assertEquals("a notification's EXTRA_TAB opens Studio", Route.Studio, Route.of("studio"))
    }

    @Test
    fun `every page has its own route under the Piano tab`() {
        assertEquals(listOf("instrument", "keyboard", "feel", "lighting", "pedal", "firmware", "playback", "display", "schedule", "remote", "kiosk"), SettingsPage.entries.map { it.key })
        assertEquals("piano/keyboard", PianoRoutes.page(SettingsPage.Keyboard))
        assertEquals("Keyboard", SettingsPage.Keyboard.title)
        assertNull(SettingsPage.Keyboard.piano)
        assertNull("Studio is a tab now: a saved page key reads as none", SettingsPage.of("studio"))
        assertEquals("piano/remote", PianoRoutes.page(SettingsPage.Remote))
        assertEquals("Remote control", SettingsPage.Remote.title)
        assertNull(SettingsPage.Remote.piano)
        assertEquals("piano/schedule", PianoRoutes.page(SettingsPage.Schedule))
        assertEquals("Schedule", SettingsPage.Schedule.title)
        assertNull(SettingsPage.Schedule.piano)
        assertEquals("piano/kiosk", PianoRoutes.page(SettingsPage.Kiosk))
        assertEquals("Kiosk", SettingsPage.Kiosk.title)
        assertNull(SettingsPage.Kiosk.piano)
        assertEquals("piano/feel", PianoRoutes.page(SettingsPage.Feel))
        assertEquals("piano/firmware", PianoRoutes.page(SettingsPage.Firmware))
        assertEquals("a page put back as the window narrows appears without the push", "piano/display?cut=true", PianoRoutes.page(SettingsPage.Display, cut = true))
        for (page in SettingsPage.entries) assertEquals(page, SettingsPage.of(page.key))
        assertNull("the hub's route is never taken for a page", SettingsPage.of("hub"))
        assertNull(SettingsPage.of(null))
    }

    @Test
    fun `the graph starts at the hub, and pages carry their key and whether to cut`() {
        assertEquals("piano/hub", PianoRoutes.HUB)
        assertEquals("piano/{page}?cut={cut}", PianoRoutes.PAGE)
        assertTrue(PianoRoutes.isPage(PianoRoutes.PAGE))
        assertFalse(PianoRoutes.isPage(PianoRoutes.HUB))
        assertFalse(PianoRoutes.isPage(Route.Piano.path))
    }

    @Test
    fun `a page's route takes only a page's key, so the hub's route can't open a page called hub`() {
        for (page in SettingsPage.entries) assertEquals(page, PianoRoutes.PageType.parseValue(page.key))
        for (notAPage in listOf("hub", "Feel ", "", "Schedule")) {
            assertThrows(IllegalArgumentException::class.java) { PianoRoutes.PageType.parseValue(notAPage) }
        }
        assertEquals("feel", PianoRoutes.PageType.serializeAsValue(SettingsPage.Feel))
    }

    @Test
    fun `four pages are the piano's, seven are the app's`() {
        assertEquals(
            listOf(null, null, PianoPage.Feel, PianoPage.Lighting, PianoPage.Pedal, PianoPage.Firmware, null, null, null, null, null),
            SettingsPage.entries.map { it.piano },
        )
        assertEquals(
            listOf("Instrument", "Keyboard", "Feel", "Lighting", "Pedal", "Firmware and status", "Playback", "Display", "Schedule", "Remote control", "Kiosk"),
            SettingsPage.entries.map { it.title },
        )
    }
}
