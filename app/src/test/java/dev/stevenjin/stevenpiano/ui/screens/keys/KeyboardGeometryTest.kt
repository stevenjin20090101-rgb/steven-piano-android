// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import dev.stevenjin.stevenpiano.ui.screens.keys.KeyboardGeometry.Companion.NONE
import dev.stevenjin.stevenpiano.ui.screens.keys.KeyboardGeometry.Companion.clampFirst
import dev.stevenjin.stevenpiano.ui.screens.keys.KeyboardGeometry.Companion.name
import dev.stevenjin.stevenpiano.ui.screens.keys.KeyboardGeometry.Companion.velocityFor
import dev.stevenjin.stevenpiano.ui.screens.keys.KeyboardGeometry.Companion.whiteIndexOf
import dev.stevenjin.stevenpiano.ui.screens.keys.KeyboardGeometry.Companion.whiteKey
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardGeometryTest {
    /** All 84 keys across 490 px: 10 px per white key, keys 100 px tall, black keys 60 px. */
    private val all = KeyboardGeometry(490f, 100f, 49)

    @Test
    fun `velocity runs from 24 at the top of a key to 127 at its bottom`() {
        assertEquals(24, velocityFor(0f, 100f))
        assertEquals(34, velocityFor(10f, 100f))    // 24 + 10.3
        assertEquals(76, velocityFor(50f, 100f))    // 24 + 51.5, rounded
        assertEquals(127, velocityFor(100f, 100f))
    }

    @Test
    fun `a touch past either end of a key plays that end`() {
        assertEquals(24, velocityFor(-30f, 100f))
        assertEquals(127, velocityFor(250f, 100f))
        assertEquals(127, velocityFor(10f, 0f))
    }

    @Test
    fun `a black key maps loudness over its own height`() {
        assertEquals(24, all.velocityAt(61, 0f))
        assertEquals(76, all.velocityAt(61, 30f))
        assertEquals(127, all.velocityAt(61, 60f))
        assertEquals(86, all.velocityAt(60, 60f))   // a white key at the same height: 24 + 61.8
    }

    @Test
    fun `black keys win inside their rectangle, white keys everywhere else`() {
        // C1 spans 0-10 px; C♯1 is 6 px wide, centred on the join at 10 px: 7-13 px, 60 px down.
        assertEquals(24, all.keyAt(5f, 30f, 0f))
        assertEquals(25, all.keyAt(8f, 30f, 0f))
        assertEquals(25, all.keyAt(12.9f, 59f, 0f))
        assertEquals(24, all.keyAt(8f, 80f, 0f))      // under C♯1: C1
        assertEquals(26, all.keyAt(12.9f, 61f, 0f))   // under C♯1: D1
        assertEquals(26, all.keyAt(13.5f, 30f, 0f))   // beside it: D1
        assertEquals(60, all.keyAt(215f, 90f, 0f))    // middle C, 21 white keys up
        assertEquals(107, all.keyAt(489f, 99f, 0f))   // B7
    }

    @Test
    fun `off the keys is no key`() {
        assertEquals(NONE, all.keyAt(-1f, 50f, 0f))
        assertEquals(NONE, all.keyAt(490f, 50f, 0f))
        assertEquals(NONE, all.keyAt(100f, -0.5f, 0f))
        assertEquals(NONE, all.keyAt(100f, 100f, 0f))
    }

    @Test
    fun `a scrolled keyboard starts at its first white key`() {
        val twoOctaves = KeyboardGeometry(150f, 100f, 15)   // 10 px per white key
        assertEquals(140f, twoOctaves.offset(14f), 0.001f)
        assertEquals(48, twoOctaves.keyAt(5f, 90f, 14f))      // C3 at the left edge
        assertEquals(49, twoOctaves.keyAt(10f, 30f, 14f))     // C♯3 on the first join
        assertEquals(72, twoOctaves.keyAt(145f, 90f, 14f))    // C5 at the right edge
        assertEquals(60, twoOctaves.keyAt(5f, 90f, 21f))      // an octave up: C4 on the left
    }

    @Test
    fun `white keys by index, and back`() {
        assertEquals(24, whiteKey(0))
        assertEquals(48, whiteKey(14))
        assertEquals(60, whiteKey(21))
        assertEquals(107, whiteKey(48))
        for (w in 0..48) assertEquals(w, whiteIndexOf(whiteKey(w)))
        assertEquals(14, whiteIndexOf(49))    // C♯3 counts as C3
        assertEquals(0, whiteIndexOf(10))     // below the piano: C1
        assertEquals(48, whiteIndexOf(120))   // above it: B7
    }

    @Test
    fun `the first white key stays where the keys still fill the view`() {
        assertEquals(34f, clampFirst(40f, 15), 0f)
        assertEquals(0f, clampFirst(-3f, 15), 0f)
        assertEquals(20f, clampFirst(20f, 29), 0f)
        assertEquals(20f, clampFirst(25f, 29), 0f)
        assertEquals(0f, clampFirst(10f, 49), 0f)
    }

    @Test
    fun `keys are named as the octave labels name them`() {
        assertEquals("C1", name(24))
        assertEquals("C4", name(60))
        assertEquals("C♯4", name(61))
        assertEquals("B7", name(107))
    }
}
