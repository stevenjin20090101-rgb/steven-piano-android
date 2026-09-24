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
 * Where in the song to draw, for a frame time (a `System.nanoTime()` value). The note canvas,
 * the keyboard strip and the scrubber all draw from one clock, so they agree on every frame.
 */
fun interface SongClock {
    fun positionAt(frameNanos: Long): Long
}
