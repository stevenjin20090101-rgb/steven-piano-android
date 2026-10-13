// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.components

/**
 * Where a key sits on the grand staff. Keys from middle C up sit on the treble staff, keys below
 * it on the bass staff. A position counts diatonic steps up from the staff's bottom line (E4 on
 * the treble, G2 on the bass): even positions are lines, odd ones spaces, and the five lines are
 * 0, 2, 4, 6 and 8; middle C is -2 on the treble, its own ledger line. With no key signature to
 * spell by, a black key takes the step of the natural below it, with a sharp ([position] of a
 * key); the score spells in the key (`score.Spelling`) and places the letter it chose
 * ([position] of a step). Pure, so it is unit-tested.
 */
object StaffPitch {
    const val MIDDLE_C = 60
    const val TOP_LINE = 8

    /** Diatonic step of each pitch class, C = 0 … B = 6; a black key takes the natural below. */
    private val STEP = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6)

    /** E4, the treble staff's bottom line, and G2, the bass staff's, as diatonic steps. */
    private val TREBLE_BOTTOM = diatonic(64)
    private val BASS_BOTTOM = diatonic(43)

    /** Diatonic steps up from C-1 (MIDI 0): C4 is 35. */
    fun diatonic(key: Int): Int = key / 12 * 7 + STEP[key % 12]

    fun onTreble(key: Int): Boolean = key >= MIDDLE_C

    /** Steps up from the bottom line of [key]'s staff, a black key spelled as a sharp. */
    fun position(key: Int): Int = position(diatonic(key), onTreble(key))

    /** Steps up from the bottom line of the [treble] or bass staff for a letter at diatonic [step] (C4 is 35). */
    fun position(step: Int, treble: Boolean): Int = step - if (treble) TREBLE_BOTTOM else BASS_BOTTOM

    /** Ledger lines a head at [position] needs: negative below its staff, positive above, 0 on it. */
    fun ledgerLines(position: Int): Int = when {
        position <= -2 -> -(-position / 2)
        position >= TOP_LINE + 2 -> (position - TOP_LINE) / 2
        else -> 0
    }
}
