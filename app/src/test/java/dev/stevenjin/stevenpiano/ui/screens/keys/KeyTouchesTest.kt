// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyTouchesTest {
    private val sent = mutableListOf<String>()
    private val touches = KeyTouches(
        object : KeyTouches.Sink {
            override fun noteOn(key: Int, velocity: Int) {
                sent += "on $key $velocity"
            }

            override fun noteOff(key: Int) {
                sent += "off $key"
            }
        },
    )

    @Test
    fun `a tap strikes the key and lifting lets it go`() {
        touches.down(1, 60, 100)
        assertTrue(touches.isPressed(60))
        touches.up(1)
        assertFalse(touches.isPressed(60))
        assertEquals(listOf("on 60 100", "off 60"), sent)
    }

    @Test
    fun `moving within a key plays nothing new`() {
        touches.down(1, 60, 90)
        touches.move(1, 60, 20)
        assertEquals(listOf("on 60 90"), sent)
    }

    @Test
    fun `a glissando lets go of each key before striking the next`() {
        touches.down(1, 60, 100)
        touches.move(1, 62, 90)
        touches.move(1, 64, 80)
        touches.up(1)
        assertEquals(listOf("on 60 100", "off 60", "on 62 90", "off 62", "on 64 80", "off 64"), sent)
    }

    @Test
    fun `sliding off the keys lets go, and sliding back strikes again`() {
        touches.down(1, 60, 100)
        touches.move(1, KeyboardGeometry.NONE, 0)
        touches.move(1, 60, 70)
        touches.up(1)
        assertEquals(listOf("on 60 100", "off 60", "on 60 70", "off 60"), sent)
    }

    @Test
    fun `several fingers play a chord`() {
        touches.down(1, 60, 100)
        touches.down(2, 64, 90)
        touches.down(3, 67, 80)
        assertEquals((1L shl 36) or (1L shl 40) or (1L shl 43), touches.pressedLow)
        touches.up(2)
        touches.up(1)
        touches.up(3)
        assertEquals(listOf("on 60 100", "on 64 90", "on 67 80", "off 64", "off 60", "off 67"), sent)
        assertEquals(0L, touches.pressedLow)
    }

    @Test
    fun `two fingers on one key strike it once and let go with the last`() {
        touches.down(1, 60, 100)
        touches.down(2, 60, 50)
        touches.up(1)
        assertEquals(listOf("on 60 100"), sent)
        assertTrue(touches.isPressed(60))
        touches.up(2)
        assertEquals(listOf("on 60 100", "off 60"), sent)
    }

    @Test
    fun `every finger lifts at once when the screen goes`() {
        touches.down(1, 60, 100)
        touches.down(2, 64, 90)
        touches.down(3, 64, 90)
        touches.releaseAll()
        assertEquals(listOf("on 60 100", "on 64 90", "off 60", "off 64"), sent.take(2) + sent.drop(2).sorted())
        assertEquals(4, sent.size)
        assertEquals(0L, touches.pressedLow)
        touches.up(1)   // already let go
        assertEquals(4, sent.size)
    }

    @Test
    fun `a touch that lands off the keys plays nothing`() {
        touches.down(1, KeyboardGeometry.NONE, 0)
        touches.up(1)
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun `the top keys are held in the high bits`() {
        touches.down(1, 107, 64)
        touches.down(2, 88, 64)
        assertEquals((1L shl 19) or 1L, touches.pressedHigh)
        assertEquals(0L, touches.pressedLow)
    }
}
