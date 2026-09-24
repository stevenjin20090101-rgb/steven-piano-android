// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.midi

/**
 * The piano's keys are MIDI 24-107 (C1-B7); it drops anything else. Port of `foldNote()`
 * in firmware/gui/piano-control.html: out-of-range notes move by octaves into range.
 */
object KeyMap {
    const val LOWEST = 24
    const val HIGHEST = 107
    const val KEY_COUNT = HIGHEST - LOWEST + 1
    const val UNPLAYABLE = -1

    /** The key that sounds [note] after [transpose], or [UNPLAYABLE] when [fold] is off and it is out of range. */
    fun map(note: Int, transpose: Int, fold: Boolean): Int {
        var n = note + transpose
        if (n in LOWEST..HIGHEST) return n
        if (!fold) return UNPLAYABLE
        while (n < LOWEST) n += 12
        while (n > HIGHEST) n -= 12
        return n
    }
}
