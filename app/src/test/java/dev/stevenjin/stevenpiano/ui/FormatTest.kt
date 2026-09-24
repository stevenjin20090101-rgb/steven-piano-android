// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormatTest {
    @Test
    fun `clock reads m-ss, with hours when there are any`() {
        assertEquals("0:00", Format.clock(0))
        assertEquals("0:59", Format.clock(59))
        assertEquals("4:31", Format.clock(271))
        assertEquals("1:02:03", Format.clock(3_723))
        assertEquals("0:00", Format.clock(-5))
        assertEquals("4:08", Format.clockMicros(248_900_000L))
        assertEquals("1:16", Format.clockMillis(76_999L))
    }

    @Test
    fun `counts group their digits and agree with their noun`() {
        assertEquals("1,204", Format.count(1_204, Locale.US))
        assertEquals("1 piece", Format.count(1, "piece", "pieces", Locale.US))
        assertEquals("0 pieces", Format.count(0, "piece", "pieces", Locale.US))
        assertEquals("1,727 pieces", Format.count(1_727, "piece", "pieces", Locale.US))
    }

    @Test
    fun `semitones carry their sign, with a true minus`() {
        assertEquals("+2", Format.semitones(2))
        assertEquals("0", Format.semitones(0))
        assertEquals("−12", Format.semitones(-12))
        assertEquals("100%", Format.percent(100))
    }
}
