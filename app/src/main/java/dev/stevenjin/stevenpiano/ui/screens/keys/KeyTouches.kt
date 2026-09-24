// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.keys

import dev.stevenjin.stevenpiano.midi.KeyMap

/**
 * Fingers on the playable keyboard, turned into Note On and Note Off for [keys]. Each pointer
 * plays the key under it. Sliding onto another key lets go of the old one, then strikes the new
 * one (a glissando); sliding off the keys lets go. A key two fingers share is struck once and let
 * go when the last of them lifts: the piano cannot strike a key that is already down.
 * [pressedLow] and [pressedHigh] are the keys held, as bits `key - 24` (keys 24-87) and
 * `key - 88` (88-107), for drawing. Pure, so it is unit-tested; the main thread drives it.
 */
class KeyTouches(private val keys: Sink) {
    /** Where the notes go: the Keys view model, or a recorder in tests. */
    interface Sink {
        fun noteOn(key: Int, velocity: Int)

        fun noteOff(key: Int)
    }

    private val keyOf = HashMap<Long, Int>()
    private val fingers = IntArray(128)

    var pressedLow = 0L
        private set
    var pressedHigh = 0L
        private set

    fun isPressed(key: Int): Boolean = key in 0..127 && fingers[key] > 0

    /** [pointer] touched down on [key] ([KeyboardGeometry.NONE] when off the keys). */
    fun down(pointer: Long, key: Int, velocity: Int) {
        up(pointer)
        keyOf[pointer] = key
        press(key, velocity)
    }

    /** [pointer] moved; it is now over [key]. */
    fun move(pointer: Long, key: Int, velocity: Int) {
        val old = keyOf[pointer] ?: return
        if (old == key) return
        keyOf[pointer] = key
        release(old)
        press(key, velocity)
    }

    /** [pointer] lifted, or was cancelled. */
    fun up(pointer: Long) {
        val old = keyOf.remove(pointer) ?: return
        release(old)
    }

    /** Every finger lifts at once: the screen is going away. */
    fun releaseAll() {
        val held = keyOf.values.toList()
        keyOf.clear()
        held.forEach(::release)
    }

    private fun press(key: Int, velocity: Int) {
        if (key !in KeyMap.LOWEST..KeyMap.HIGHEST) return
        if (fingers[key]++ == 0) {
            setPressed(key, true)
            keys.noteOn(key, velocity)
        }
    }

    private fun release(key: Int) {
        if (key !in KeyMap.LOWEST..KeyMap.HIGHEST) return
        if (--fingers[key] == 0) {
            setPressed(key, false)
            keys.noteOff(key)
        }
    }

    private fun setPressed(key: Int, on: Boolean) {
        val bit = key - KeyMap.LOWEST
        if (bit < 64) {
            pressedLow = if (on) pressedLow or (1L shl bit) else pressedLow and (1L shl bit).inv()
        } else {
            pressedHigh = if (on) pressedHigh or (1L shl (bit - 64)) else pressedHigh and (1L shl (bit - 64)).inv()
        }
    }
}
