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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLayoutTest {
    private val keys = KeyLayout(490f)   // 10 px per white key
    private val all = 0 until KeyMap.KEY_COUNT

    @Test
    fun `84 keys, 49 white and 35 black, white at both ends`() {
        assertEquals(84, KeyMap.KEY_COUNT)
        assertEquals(35, all.count { keys.isBlack(it) })
        assertFalse(keys.isBlack(0))                      // C1
        assertFalse(keys.isBlack(KeyMap.KEY_COUNT - 1))   // B7
    }

    @Test
    fun `white keys tile the width in order`() {
        val whites = all.filterNot { keys.isBlack(it) }
        assertEquals(0f, keys.left(whites.first()), 0.001f)
        assertEquals(490f, keys.right(whites.last()), 0.001f)
        whites.zipWithNext().forEach { (a, b) -> assertEquals(keys.right(a), keys.left(b), 0.001f) }
        assertEquals(210f, keys.left(60 - KeyMap.LOWEST), 0.001f)   // middle C: 21 white keys up
    }

    @Test
    fun `black keys are narrower and sit on the join between their neighbours`() {
        all.filter { keys.isBlack(it) }.forEach { i ->
            assertEquals(10f * KeyLayout.BLACK_RATIO, keys.width(i), 0.001f)
            assertEquals(keys.right(i - 1), (keys.left(i) + keys.right(i)) / 2, 0.001f)
            assertTrue(keys.width(i) < keys.width(i - 1))
        }
    }
}
