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
 * in firmware/gui/piano-control.html: out-of-range notes move by octaves into range. [LOWEST] and
 * [HIGHEST] also bound the screen's 84-key drawings; another instrument's range goes to the five-argument
 * [map] (v1.11 — M29).
 */
object KeyMap {
    const val LOWEST = 24
    const val HIGHEST = 107
    const val KEY_COUNT = HIGHEST - LOWEST + 1
    const val UNPLAYABLE = -1

    /** The key that sounds [note] after [transpose], or [UNPLAYABLE] when [fold] is off and it is out of range. */
    fun map(note: Int, transpose: Int, fold: Boolean): Int = map(note, transpose, fold, LOWEST, HIGHEST)

    /**
     * The key of an instrument with keys [lowest]..[highest] (v1.11 — M29: Steven Piano's 24-107, a MIDI
     * piano's 21-108) that sounds [note] after [transpose]: in range as it is, else moved by octaves into it
     * when [fold] is on, else [UNPLAYABLE]. The range must span an octave at least.
     */
    fun map(note: Int, transpose: Int, fold: Boolean, lowest: Int, highest: Int): Int {
        var n = note + transpose
        if (n in lowest..highest) return n
        if (!fold) return UNPLAYABLE
        while (n < lowest) n += 12
        while (n > highest) n -= 12
        return n
    }
}
