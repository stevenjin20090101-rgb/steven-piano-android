// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.instruments

import dev.stevenjin.stevenpiano.piano.PianoState
import kotlin.math.roundToInt

/**
 * While Live plays Steven Piano (v1.11 — M29), its timing scatter (`humantime`, a random delay of up to N
 * ms before each strike) is held at 0, as a channel holds the volume: [hold] sends 0 without counting it for
 * a save; when Live closes, [release] puts back the value the piano had. Nothing is held when the piano
 * says 0 already, or before it has answered (it is held as soon as it does). A release while the piano is
 * away waits until it answers again. The app never raises `hold` (the piano's 2 s limit for a held key).
 * Main thread.
 */
class LiveTimingHold(private val hold: (name: String, value: Int) -> Unit, private val release: (name: String, value: Int) -> Unit) {
    /** What `humantime` was before Live held it at 0; null while nothing is held. */
    var before: Int? = null
        private set

    fun update(liveOpen: Boolean, piano: PianoState) {
        val values = (piano as? PianoState.Ready)?.values ?: return
        val held = before
        if (liveOpen && held == null) {
            val scatter = values[NAME]?.toFloatOrNull()?.roundToInt() ?: return
            if (scatter == 0) return
            before = scatter
            hold(NAME, 0)
        } else if (!liveOpen && held != null) {
            before = null
            release(NAME, held)
        }
    }

    companion object {
        /** The piano's timing scatter, in ms (firmware `humantime`, 0-40). */
        const val NAME = "humantime"
    }
}
