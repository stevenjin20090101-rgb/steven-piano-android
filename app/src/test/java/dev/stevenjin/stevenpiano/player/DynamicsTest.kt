// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Dynamic range and the quietest note (v1.16 — M44): m + (v − m) × r, then the floor, within 1–127. */
class DynamicsTest {
    @Test
    fun `the range spreads notes from the mean, and the floor raises the softest`() {
        assertEquals(46, Dynamics.velocity(40, 60.0, DynamicRange.NARROW, 1))
        assertEquals(74, Dynamics.velocity(80, 60.0, DynamicRange.NARROW, 1))
        assertEquals(40, Dynamics.velocity(40, 60.0, DynamicRange.NATURAL, 1))
        assertEquals(34, Dynamics.velocity(40, 60.0, DynamicRange.WIDE, 1))
        assertEquals(86, Dynamics.velocity(80, 60.0, DynamicRange.WIDE, 1))
        assertEquals("raised to the floor", 20, Dynamics.velocity(10, 60.0, DynamicRange.NATURAL, 20))
        assertEquals("spread below the floor, then raised to it", 20, Dynamics.velocity(10, 60.0, DynamicRange.WIDE, 20))
        assertEquals("above the floor it stays", 34, Dynamics.velocity(40, 60.0, DynamicRange.WIDE, 20))
        assertEquals("127 at most", 127, Dynamics.velocity(127, 64.0, DynamicRange.WIDE, 20))
        assertEquals("1 at least", 1, Dynamics.velocity(1, 100.0, DynamicRange.WIDE, 1))
    }

    @Test
    fun `a piece's mean is its own`() {
        val three = piece {
            note(0, 60, 400, velocity = 40)
            note(500, 62, 400, velocity = 60)
            note(1_000, 64, 400, velocity = 80)
        }
        fun velocities(range: DynamicRange, floor: Int) =
            Performance.shape(three, null, RepeatsOnly.copy(dynamicRange = range, velocityFloor = floor), FreeRepeats).sounded().map { it.velocity }
        assertEquals(listOf(46, 60, 74), velocities(DynamicRange.NARROW, 1))
        assertEquals(listOf(34, 60, 86), velocities(DynamicRange.WIDE, 1))
        assertEquals(listOf(50, 60, 80), velocities(DynamicRange.NATURAL, 50))
    }
}
