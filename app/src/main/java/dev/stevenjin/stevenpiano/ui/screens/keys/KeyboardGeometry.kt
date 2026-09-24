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
import dev.stevenjin.stevenpiano.ui.components.KeyLayout
import kotlin.math.roundToInt

/**
 * The playable keyboard's geometry for one canvas: [visibleWhites] white keys across [width] px,
 * the whole 84-key [KeyLayout] scaled to match and scrolled so a chosen white key is leftmost.
 * White keys run the full [height]; black keys reach [BLACK_HEIGHT] of it and win a touch inside
 * their rectangle. It answers which key a touch is on and how hard it plays. Pure: unit-tested.
 */
class KeyboardGeometry(val width: Float, val height: Float, val visibleWhites: Int) {
    val whiteWidth: Float = width / visibleWhites
    val layout = KeyLayout(whiteWidth * KeyLayout.WHITE_KEYS)
    val blackHeight: Float = height * BLACK_HEIGHT

    /** How far the full layout is scrolled, in px, when white key [firstWhite] is leftmost. */
    fun offset(firstWhite: Float): Float = firstWhite * whiteWidth

    /** The key under ([x], [y]) on the canvas with [firstWhite] leftmost, or [NONE] off the keys. */
    fun keyAt(x: Float, y: Float, firstWhite: Float): Int {
        if (x < 0f || x >= width || y < 0f || y >= height) return NONE
        val at = x + offset(firstWhite)
        if (y < blackHeight) {
            for (i in 0 until KeyMap.KEY_COUNT) {
                if (layout.isBlack(i) && at >= layout.left(i) && at < layout.right(i)) return KeyMap.LOWEST + i
            }
        }
        for (i in 0 until KeyMap.KEY_COUNT) {
            if (!layout.isBlack(i) && at >= layout.left(i) && at < layout.right(i)) return KeyMap.LOWEST + i
        }
        return NONE
    }

    /** How hard a touch at [y] plays [key]: softest at the top of the key, loudest at its bottom; a black key over its own height. */
    fun velocityAt(key: Int, y: Float): Int =
        velocityFor(y, if (key != NONE && layout.isBlack(key - KeyMap.LOWEST)) blackHeight else height)

    companion object {
        const val NONE = -1

        /** How far down the white keys the black keys reach. */
        const val BLACK_HEIGHT = 0.6f
        const val SOFTEST = 24
        const val LOUDEST = 127

        /** `24 + (y / keyHeight) x (127 - 24)`, rounded; a touch past either end of the key plays that end. */
        fun velocityFor(y: Float, keyHeight: Float): Int {
            if (keyHeight <= 0f) return LOUDEST
            return (SOFTEST + (y / keyHeight).coerceIn(0f, 1f) * (LOUDEST - SOFTEST)).roundToInt()
        }

        /** White-key index (0 = C1 … 48 = B7) of [key], or of the white key below it when it is black. */
        fun whiteIndexOf(key: Int): Int {
            val k = key.coerceIn(KeyMap.LOWEST, KeyMap.HIGHEST) - KeyMap.LOWEST   // C1 is a C
            return k / 12 * 7 + WHITE_AT_OR_BELOW[k % 12]
        }

        /** The key of white-key index [white] (0 = C1 … 48 = B7). */
        fun whiteKey(white: Int): Int {
            val w = white.coerceIn(0, KeyLayout.WHITE_KEYS - 1)
            return KeyMap.LOWEST + w / 7 * 12 + WHITE_STEPS[w % 7]
        }

        /** [first] kept where [visibleWhites] white keys still fit on the keyboard. */
        fun clampFirst(first: Float, visibleWhites: Int): Float =
            first.coerceIn(0f, (KeyLayout.WHITE_KEYS - visibleWhites).coerceAtLeast(0).toFloat())

        /** "C4", "F♯2": the name the octave labels and TalkBack use (middle C is C4). */
        fun name(key: Int): String = NAMES[key % 12] + (key / 12 - 1)

        private val WHITE_STEPS = intArrayOf(0, 2, 4, 5, 7, 9, 11)
        private val WHITE_AT_OR_BELOW = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
        private val NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
    }
}
