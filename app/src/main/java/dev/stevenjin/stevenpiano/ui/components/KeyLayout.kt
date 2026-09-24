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

/**
 * Where each of the piano's 84 keys (MIDI 24-107, C1-B7) sits across [width] pixels: 49 white
 * keys side by side, and 35 narrower black keys centred on the joins between them. The note
 * canvas uses it for lanes and the keyboard strip for keys, so the two always line up.
 * Index `i` is key `KeyMap.LOWEST + i`.
 */
class KeyLayout(val width: Float) {
    private val left = FloatArray(KeyMap.KEY_COUNT)
    private val right = FloatArray(KeyMap.KEY_COUNT)
    private val black = BooleanArray(KeyMap.KEY_COUNT)

    /** The width of one white key. */
    val whiteWidth: Float = width / WHITE_KEYS

    init {
        var whites = 0
        for (i in 0 until KeyMap.KEY_COUNT) {
            if (BLACK_IN_OCTAVE[(KeyMap.LOWEST + i) % 12]) {
                val join = whites * whiteWidth
                black[i] = true
                left[i] = join - whiteWidth * BLACK_RATIO / 2
                right[i] = join + whiteWidth * BLACK_RATIO / 2
            } else {
                left[i] = whites * whiteWidth
                right[i] = (whites + 1) * whiteWidth
                whites++
            }
        }
    }

    fun left(i: Int): Float = left[i]

    fun right(i: Int): Float = right[i]

    fun width(i: Int): Float = right[i] - left[i]

    fun isBlack(i: Int): Boolean = black[i]

    companion object {
        const val WHITE_KEYS = 49

        /** A black key's width as a share of a white key's. */
        const val BLACK_RATIO = 0.6f

        private val BLACK_IN_OCTAVE = booleanArrayOf(false, true, false, true, false, false, true, false, true, false, true, false)
    }
}
