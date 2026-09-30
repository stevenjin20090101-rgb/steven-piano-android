// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.audio

import kotlin.math.exp

/**
 * The last step of the tablet's mix (v1.8 — M25): a peak limiter. The mix passes unchanged while its
 * peaks stay under [ceiling]; a peak over it turns the gain down at once, just enough, and the gain
 * comes back up exponentially with [releaseMs] as its time constant. A loud pedalled chord is turned
 * down smoothly rather than clipped, and nothing leaves above [ceiling] (−1 dBFS). Pure, one thread.
 */
class Limiter(rate: Int, val ceiling: Float = CEILING, releaseMs: Int = RELEASE_MS) {
    private val recover = 1f - exp(-1.0 / (rate * releaseMs / 1000.0)).toFloat()

    /** The gain now: 1 while nothing has been too loud lately. */
    var gain = 1f
        private set

    /** The lowest [gain] since [resetLowest]: how hard the limiter worked. */
    var lowest = 1f
        private set

    fun process(x: Float): Float {
        gain += (1f - gain) * recover
        val a = if (x < 0f) -x else x
        if (a * gain > ceiling) {
            gain = ceiling / a
            if (gain < lowest) lowest = gain
            return if (x < 0f) -ceiling else ceiling   // exactly, whatever the rounding of x × gain
        }
        return x * gain
    }

    fun resetLowest() {
        lowest = 1f
    }

    companion object {
        /** −1 dBFS. */
        const val CEILING = 0.8913f

        /** How quickly the gain comes back after a loud peak. */
        const val RELEASE_MS = 250
    }
}
