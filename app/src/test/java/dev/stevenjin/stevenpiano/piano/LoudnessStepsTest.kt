// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The loudness steps' lines and replies (M83), word for word against BLE_SETTINGS.md › "Distinct loudness steps". */
class LoudnessStepsTest {
    @Test
    fun `only the calibration's own lines go through its conversation`() {
        for (line in listOf("calstrike 60 2400", "steptable 60 1200 1700", "steptable 107 1 2 3 4 5 6 7 8", "steptable 60 clear", "velsteps 5", "fullpower 0", "save")) {
            assertTrue(line, LoudnessSteps.ours(line))
        }
        for (line in listOf("fullpower 1", "calstrike 60", "steptable clear", "steptable 60 1", "velsteps 10", "ledbright 40", "calstrike 60 2400 1")) {
            assertFalse(line, LoudnessSteps.ours(line))
        }
        assertEquals("steptable 60 1200 1700 2600", LoudnessSteps.row(60, listOf(1200, 1700, 2600)))
    }

    @Test
    fun `a reply is the line's own, or a refusal any command gets, never the echo or a value`() {
        val line = "calstrike 60 2400"
        assertTrue(LoudnessSteps.answers(line, "  calstrike 60 at 2400: struck for 100 ms"))
        assertTrue(LoudnessSteps.answers(line, "  calstrike refused: too soon (one every 300 ms)"))
        assertTrue(LoudnessSteps.answers(line, "  usage: calstrike <note 0..127> <pwm 1..4096>"))
        assertTrue(LoudnessSteps.answers(line, "  refused — updating firmware"))
        assertTrue(LoudnessSteps.answers(line, "  refused — not authorised (send auth first)"))
        assertTrue(LoudnessSteps.answers(line, "  refused over Bluetooth — use the USB console"))
        assertFalse(LoudnessSteps.answers(line, "> calstrike 60 2400"))
        assertFalse(LoudnessSteps.answers(line, "!i2cfails=0"))
        assertFalse(LoudnessSteps.answers(line, "  LED rest = 1 (live only, never saved; ends with this Bluetooth link)"))
        assertTrue(LoudnessSteps.answers("save", "[settings] saved to NVS"))
        assertTrue(LoudnessSteps.answers("save", "[settings] save FAILED — could not open NVS RW"))
        assertTrue(LoudnessSteps.answers("fullpower 0", "  fullpower = OFF — velocity-mapped PWM with min/max/velmult."))
    }

    @Test
    fun `what a reply says is read against the spec, and anything else is the piano's own words`() {
        val strike = "calstrike 60 2400"
        assertEquals(LoudnessSteps.Reply.Struck(100), LoudnessSteps.read(strike, "  calstrike 60 at 2400: struck for 100 ms"))
        assertEquals(LoudnessSteps.Reply.TooSoon, LoudnessSteps.read(strike, "  calstrike refused: too soon (one every 300 ms)"))
        assertEquals("another key's strike is not this one's", LoudnessSteps.Reply.Other("calstrike 61 at 2400: struck for 100 ms"), LoudnessSteps.read(strike, "  calstrike 61 at 2400: struck for 100 ms"))
        assertEquals(LoudnessSteps.Reply.Other("calstrike refused: that key is busy or has no board"), LoudnessSteps.read(strike, "  calstrike refused: that key is busy or has no board"))
        assertEquals(LoudnessSteps.Reply.Done, LoudnessSteps.read("steptable 60 1200 1700 2600", "  steptable 60 = 3 steps (saved by save or the auto-save)"))
        assertEquals("the count must match", LoudnessSteps.Reply.Other("steptable 60 = 2 steps (saved by save or the auto-save)"), LoudnessSteps.read("steptable 60 1200 1700 2600", "  steptable 60 = 2 steps (saved by save or the auto-save)"))
        assertEquals(LoudnessSteps.Reply.Done, LoudnessSteps.read("steptable 60 clear", "  steptable 60 cleared"))
        assertEquals(LoudnessSteps.Reply.Done, LoudnessSteps.read("velsteps 5", "  velsteps = 5: every note snaps to one of 5 fixed loudness steps (shows once fullpower is 0)"))
        assertEquals(LoudnessSteps.Reply.Done, LoudnessSteps.read("velsteps 5", "  velsteps = 5: every note snaps to one of 5 fixed loudness steps"))
        assertEquals(LoudnessSteps.Reply.Other("velsteps out of range (0 or 2..8)"), LoudnessSteps.read("velsteps 5", "  velsteps out of range (0 or 2..8)"))
        assertEquals(LoudnessSteps.Reply.Done, LoudnessSteps.read("fullpower 0", "  fullpower = OFF — velocity-mapped PWM with min/max/velmult."))
        assertEquals("Full power on is not off", LoudnessSteps.Reply.Other("fullpower = ON — every NoteOn drives FULL_ON. Max force, no hum, no velocity."), LoudnessSteps.read("fullpower 0", "  fullpower = ON — every NoteOn drives FULL_ON. Max force, no hum, no velocity."))
        assertEquals(LoudnessSteps.Reply.Done, LoudnessSteps.read("save", "[settings] saved to NVS"))
        assertEquals(LoudnessSteps.Reply.Other("[settings] save FAILED — could not open NVS RW"), LoudnessSteps.read("save", "[settings] save FAILED — could not open NVS RW"))
        assertTrue(LoudnessSteps.supported(mapOf("stepsproto" to "1")))
        assertFalse(LoudnessSteps.supported(mapOf("ledrestproto" to "1")))
        assertEquals(82, LoudnessSteps.calibrated(mapOf("stepkeys" to "82")))
    }
}
