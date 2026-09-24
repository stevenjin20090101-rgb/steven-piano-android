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
 * Where a key sits on the grand staff. Sharps only, and no key signatures: a black key takes the
 * staff step of the natural below it, with a sharp. Keys from middle C up sit on the treble
 * staff, keys below it on the bass staff. A position counts diatonic steps up from the staff's
 * bottom line (E4 on the treble, G2 on the bass): even positions are lines, odd ones spaces, and
 * the five lines are 0, 2, 4, 6 and 8; middle C is -2 on the treble, its own ledger line.
 * Pure, so it is unit-tested.
 */
object StaffPitch {
    const val MIDDLE_C = 60
    const val TOP_LINE = 8

    /** Diatonic step of each pitch class, C = 0 … B = 6; a black key takes the natural below. */
    private val STEP = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)
    private val SHARP = booleanArrayOf(false, true, false, true, false, false, true, false, true, false, true, false)

    /** E4, the treble staff's bottom line, and G2, the bass staff's, as diatonic steps. */
    private val TREBLE_BOTTOM = diatonic(64)
    private val BASS_BOTTOM = diatonic(43)

    /** Diatonic steps up from C-1 (MIDI 0): C4 is 35. */
    fun diatonic(key: Int): Int = key / 12 * 7 + STEP[key % 12]

    fun isSharp(key: Int): Boolean = SHARP[key % 12]

    fun onTreble(key: Int): Boolean = key >= MIDDLE_C

    /** Steps up from the bottom line of [key]'s staff. */
    fun position(key: Int): Int = diatonic(key) - if (onTreble(key)) TREBLE_BOTTOM else BASS_BOTTOM

    /** Ledger lines a head at [position] needs: negative below its staff, positive above, 0 on it. */
    fun ledgerLines(position: Int): Int = when {
        position <= -2 -> -(-position / 2)
        position >= TOP_LINE + 2 -> (position - TOP_LINE) / 2
        else -> 0
    }

    /**
     * Which heads move one head width to the right, as engraving does for a second: within each
     * chord (notes on one staff that start within [chordMicros] of the chord's first note),
     * walking up from the lowest head, a head a step or less above an unmoved one moves; the
     * next one up stays, so clusters alternate. [keys] are the keys played, sorted with [starts];
     * [KeyMap.UNPLAYABLE] ones are left out.
     */
    fun secondOffsets(starts: LongArray, keys: IntArray, chordMicros: Long): BooleanArray {
        val moved = BooleanArray(keys.size)
        val chord = IntArray(CHORD_LIMIT)
        var first = 0
        while (first < keys.size) {
            var end = first + 1
            while (end < keys.size && starts[end] - starts[first] <= chordMicros) end++
            for (treble in booleanArrayOf(true, false)) {
                var count = 0
                for (i in first until end) {
                    if (keys[i] != KeyMap.UNPLAYABLE && onTreble(keys[i]) == treble && count < CHORD_LIMIT) chord[count++] = i
                }
                sortByPosition(chord, count, keys)
                for (n in 1 until count) {
                    val below = chord[n - 1]
                    val here = chord[n]
                    val step = position(keys[here]) - position(keys[below])
                    // The same key twice is one head; a step apart (or a sharp on the same step) collide.
                    if (!moved[below] && keys[here] != keys[below] && step <= 1) moved[here] = true
                }
            }
            first = end
        }
        return moved
    }

    private fun sortByPosition(chord: IntArray, count: Int, keys: IntArray) {
        for (n in 1 until count) {
            val index = chord[n]
            var j = n - 1
            while (j >= 0 && keys[chord[j]] > keys[index]) {
                chord[j + 1] = chord[j]
                j--
            }
            chord[j + 1] = index
        }
    }

    /** More heads than this in one chord on one staff are drawn without the second rule. */
    private const val CHORD_LIMIT = 16
}
