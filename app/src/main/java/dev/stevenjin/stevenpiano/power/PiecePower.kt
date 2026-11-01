// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.power

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The power on Now playing (v1.39 — M76): the latest reading ([watts], taken [at] on the [PiecePower]'s clock) and this piece's [peak]. */
data class PieceWatts(val watts: Double? = null, val at: Long = Long.MIN_VALUE, val peak: Double? = null) {
    /** The reading when it is under [PlugAdverts.FRESH_MS] old at [now]; else null ("— W"). */
    fun fresh(now: Long): Double? = watts?.takeIf { now - at <= PlugAdverts.FRESH_MS }
}

/**
 * Now playing's power line (v1.39 — M76; Steven: live watts and peak watts for this song, where the player is): every
 * advert of the paired plug ([reading]) is the watts now, and while a piece plays the highest of them is its peak; the
 * peak starts again at each piece's start ([pieceStarts]). [now] is a monotonic clock, ms. Any thread.
 */
class PiecePower(private val now: () -> Long) {
    private val _state = MutableStateFlow(PieceWatts())
    val state: StateFlow<PieceWatts> = _state.asStateFlow()

    /** An advert's [watts]; [playing]: a piece plays now, so it counts for the peak. */
    @Synchronized
    fun reading(watts: Double, playing: Boolean) {
        if (!watts.isFinite() || watts < 0.0) return
        val before = _state.value
        val peak = if (playing) maxOf(before.peak ?: watts, watts) else before.peak
        _state.value = PieceWatts(watts, now(), peak)
    }

    /** A piece starts (a new one, or the same from its top): its peak starts again. */
    @Synchronized
    fun pieceStarts() {
        _state.value = _state.value.copy(peak = null)
    }

    /** No plug: nothing to show. */
    @Synchronized
    fun clear() {
        _state.value = PieceWatts()
    }
}
