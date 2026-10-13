// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

import dev.stevenjin.stevenpiano.midi.KeyMap
import dev.stevenjin.stevenpiano.ui.components.StaffPitch.ledgerLines
import dev.stevenjin.stevenpiano.ui.components.StaffPitch.onTreble
import dev.stevenjin.stevenpiano.ui.components.StaffPitch.position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaffPitchTest {
    /** A black key: a sharp on the staff. */
    private fun isSharp(key: Int): Boolean = key % 12 in setOf(1, 3, 6, 8, 10)

    @Test
    fun `middle C sits on its own ledger line below the treble staff`() {
        assertTrue(onTreble(60))
        assertEquals(-2, position(60))
        assertEquals(-1, ledgerLines(position(60)))
    }

    @Test
    fun `E4 is the treble staff's bottom line and G2 the bass staff's`() {
        assertTrue(onTreble(64))
        assertEquals(0, position(64))
        assertFalse(onTreble(43))
        assertEquals(0, position(43))
        assertEquals(0, ledgerLines(0))
    }

    @Test
    fun `each staff's five lines`() {
        assertEquals(listOf(0, 2, 4, 6, 8), listOf(64, 67, 71, 74, 77).map(::position))   // E4 G4 B4 D5 F5
        assertEquals(listOf(0, 2, 4, 6, 8), listOf(43, 47, 50, 53, 57).map(::position))   // G2 B2 D3 F3 A3
        (0..StaffPitch.TOP_LINE).forEach { assertEquals(0, ledgerLines(it)) }
    }

    @Test
    fun `the piano's C1 needs five ledger lines below the bass staff, and B7 eight above the treble`() {
        assertFalse(onTreble(KeyMap.LOWEST))
        assertEquals(-11, position(KeyMap.LOWEST))
        assertEquals(-5, ledgerLines(position(KeyMap.LOWEST)))
        assertTrue(onTreble(KeyMap.HIGHEST))
        assertEquals(25, position(KeyMap.HIGHEST))
        assertEquals(8, ledgerLines(position(KeyMap.HIGHEST)))
    }

    @Test
    fun `ledger lines fall every second step outside the lines`() {
        assertEquals(listOf(-1, -1, -2, -2, -3), (-2 downTo -6).map(::ledgerLines))
        assertEquals(listOf(0, 1, 1, 2, 2), (9..13).map(::ledgerLines))
    }

    @Test
    fun `B3 sits just above the bass staff and D4 just below the treble, without ledger lines`() {
        assertFalse(onTreble(59))
        assertEquals(9, position(59))
        assertEquals(0, ledgerLines(position(59)))
        assertEquals(-1, position(62))
        assertEquals(0, ledgerLines(position(62)))
    }

    @Test
    fun `black keys are sharps on the step of the natural below`() {
        for (key in KeyMap.LOWEST..KeyMap.HIGHEST) {
            if (isSharp(key)) {
                assertEquals("key $key", position(key - 1), position(key))
                assertEquals("key $key", onTreble(key - 1), onTreble(key))
            }
        }
        assertEquals(-2, position(61))   // C♯4 on middle C's ledger line
    }

    @Test
    fun `white keys climb one step at a time on each staff`() {
        val whites = (KeyMap.LOWEST..KeyMap.HIGHEST).filterNot(::isSharp)
        assertEquals(49, whites.size)
        whites.zipWithNext().filter { (a, b) -> onTreble(a) == onTreble(b) }.forEach { (a, b) ->
            assertEquals("$a to $b", position(a) + 1, position(b))
        }
    }
}
