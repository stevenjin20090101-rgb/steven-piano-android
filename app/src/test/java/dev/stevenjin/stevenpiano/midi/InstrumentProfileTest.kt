// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each instrument takes (v1.11 — M29). */
class InstrumentProfileTest {
    @Test
    fun `Steven Piano is C1 to B7, 100 ms between strikes, shares a held key, the sustain pedal alone and paced`() {
        val piano = InstrumentProfile.StevenPiano
        assertEquals(24, piano.lowest)
        assertEquals(107, piano.highest)
        assertEquals(100_000L, piano.minOnsetGapMicros)
        assertFalse(piano.restrike)
        assertTrue(piano.forwards(64))
        assertFalse(piano.forwards(66))
        assertFalse(piano.forwards(67))
        assertFalse(piano.forwards(123))
        assertTrue(piano.pacedPedal)
        assertFalse(piano.explicitOffs)
    }

    @Test
    fun `a MIDI piano is A0 to C8, struck again at once, three pedals unpaced, explicit offs in its stop`() {
        val midi = InstrumentProfile.StandardPiano
        assertEquals(21, midi.lowest)
        assertEquals(108, midi.highest)
        assertEquals(0L, midi.minOnsetGapMicros)
        assertTrue(midi.restrike)
        assertEquals(listOf(64, 66, 67), midi.pedals.toList())
        assertFalse(midi.forwards(7))
        assertFalse(midi.forwards(121))
        assertFalse(midi.pacedPedal)
        assertTrue(midi.explicitOffs)
    }

    @Test
    fun `notes fold into each instrument's own keys`() {
        assertEquals(33, KeyMap.map(21, 0, true, InstrumentProfile.StevenPiano.lowest, InstrumentProfile.StevenPiano.highest))
        assertEquals(21, KeyMap.map(21, 0, true, InstrumentProfile.StandardPiano.lowest, InstrumentProfile.StandardPiano.highest))
        assertEquals(108, KeyMap.map(108, 0, true, 21, 108))
        assertEquals(96, KeyMap.map(108, 0, true, 24, 107))
        assertEquals(KeyMap.UNPLAYABLE, KeyMap.map(20, 0, false, 21, 108))
        assertEquals(32, KeyMap.map(8, 0, true, 21, 108))
        assertEquals(103, KeyMap.map(127, 0, true, 21, 108))
    }
}
