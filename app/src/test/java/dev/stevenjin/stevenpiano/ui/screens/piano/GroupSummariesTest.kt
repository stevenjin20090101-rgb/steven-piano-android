// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano

import dev.stevenjin.stevenpiano.piano.PianoState
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import dev.stevenjin.stevenpiano.settings.PianoSettings
import dev.stevenjin.stevenpiano.ui.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Test

/** The hub's one-line values: from what the app holds, never a read from the piano. */
class GroupSummariesTest {
    private fun ready(vararg values: Pair<String, String>, facts: Map<String, String> = emptyMap()) =
        PianoState.Ready(values.toMap(), facts)

    private val unknown = GroupSummaries.UNKNOWN

    @Test
    fun `Feel reads full power, or the volume`() {
        assertEquals("Full power", GroupSummaries.feel(ready("fullpower" to "1", "volume" to "100")))
        assertEquals("full power wins over the volume", "Full power", GroupSummaries.feel(ready("fullpower" to "1", "volume" to "55")))
        assertEquals("Volume 70%", GroupSummaries.feel(ready("fullpower" to "0", "volume" to "70")))
        assertEquals("Volume 0%", GroupSummaries.feel(ready("fullpower" to "0", "volume" to "0")))
        assertEquals("firmware without the names", unknown, GroupSummaries.feel(ready("fullpower" to "0")))
    }

    @Test
    fun `Lighting reads off, or the mode and the brightness as the piano rounds it`() {
        assertEquals("Off", GroupSummaries.lighting(ready("leds" to "0", "ledmode" to "3", "ledbright" to "160")))
        assertEquals("Reactive · 62%", GroupSummaries.lighting(ready("leds" to "1", "ledmode" to "3", "ledbright" to "160")))
        assertEquals("Static · 100%", GroupSummaries.lighting(ready("leds" to "1", "ledmode" to "1", "ledbright" to "255")))
        assertEquals("the firmware's own (40 x 100) / 255", "Rainbow · 15%", GroupSummaries.lighting(ready("leds" to "1", "ledmode" to "2", "ledbright" to "40")))
        assertEquals("a strip whose mode is Off is off", "Off", GroupSummaries.lighting(ready("leds" to "1", "ledmode" to "0", "ledbright" to "160")))
        assertEquals("Reactive", GroupSummaries.lighting(ready("leds" to "1", "ledmode" to "3")))
        assertEquals(unknown, GroupSummaries.lighting(ready()))
    }

    @Test
    fun `Pedal reads on or off, and Firmware the version`() {
        assertEquals("On", GroupSummaries.pedal(ready("pedalon" to "1")))
        assertEquals("Off", GroupSummaries.pedal(ready("pedalon" to "0")))
        assertEquals(unknown, GroupSummaries.pedal(ready()))
        assertEquals("1.4.0", GroupSummaries.firmware(ready(facts = mapOf("fw" to "1.4.0"))))
        assertEquals(unknown, GroupSummaries.firmware(ready(facts = mapOf("fw" to " "))))
        assertEquals(unknown, GroupSummaries.firmware(ready()))
    }

    @Test
    fun `until the piano answers its four rows read a dash, and the app's rows still read`() {
        for (state in listOf(PianoState.Unknown, PianoState.Unsupported)) {
            val rows = GroupSummaries.from(state, PianoSettings(), wide = false)
            assertEquals(listOf(unknown, unknown, unknown, unknown), listOf(rows.feel, rows.lighting, rows.pedal, rows.firmware))
            assertEquals("2 s pause · 100%", rows.playback)
            assertEquals("Paper roll", rows.display)
        }
    }

    @Test
    fun `Playback reads the pause before each piece, then the default tempo`() {
        assertEquals("2 s pause · 100%", GroupSummaries.playback(PianoSettings()))
        assertEquals("2 s pause · 85%", GroupSummaries.playback(PianoSettings(defaultTempoPct = 85)))
        assertEquals("No pause · 100%", GroupSummaries.playback(PianoSettings(preRollMs = 0)))
        assertEquals("0.5 s pause · 100%", GroupSummaries.playback(PianoSettings(preRollMs = 500)))
        assertEquals("2.5 s pause · 120%", GroupSummaries.playback(PianoSettings(preRollMs = 2_500, defaultTempoPct = 120)))
        assertEquals("5 s pause · 100%", GroupSummaries.playback(PianoSettings(preRollMs = 5_000)))
    }

    @Test
    fun `Display reads the note display, the roll's style on wide screens`() {
        fun shown(display: NoteDisplay, wide: Boolean) = GroupSummaries.display(PianoSettings(noteDisplay = display), wide)
        assertEquals("Paper roll", shown(NoteDisplay.PAPER_ROLL, wide = false))
        assertEquals("Falling notes", shown(NoteDisplay.FALLING, wide = false))
        assertEquals("Score", shown(NoteDisplay.STAFF, wide = false))
        assertEquals("the score has its own place there", "Paper roll", shown(NoteDisplay.STAFF, wide = true))
        assertEquals("Falling notes", shown(NoteDisplay.FALLING, wide = true))
    }

    @Test
    fun `each page's row takes its own value`() {
        val piano = ready("fullpower" to "0", "volume" to "70", "leds" to "0", "pedalon" to "1", facts = mapOf("fw" to "emulator"))
        val rows = GroupSummaries.from(piano, PianoSettings(defaultTempoPct = 90, noteDisplay = NoteDisplay.FALLING), wide = false)
        assertEquals(
            listOf("Volume 70%", "Off", "On", "emulator", "2 s pause · 90%", "Falling notes"),
            SettingsPage.entries.map { rows.of(it) },
        )
    }
}
