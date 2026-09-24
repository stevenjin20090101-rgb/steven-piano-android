// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.nowplaying

import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.ui.components.SongClock
import dev.stevenjin.stevenpiano.ui.theme.Motion

/**
 * The position Now playing draws at: the player's own, except for the one orchestrated moment.
 * When play is pressed the roll eases from still to moving over 320 ms (ease-out) and meets the
 * music exactly at the end, so the picture never lags the piano. Reduced motion: no ease, a cut.
 */
internal class RollClock(private val player: Player) : SongClock {
    private var easeFrom = 0L
    private var easeStart = 0L
    private var easing = false

    /** Called with the first frame after playing starts. */
    fun easeIn(frameNanos: Long) {
        easeFrom = player.positionMicrosAt(frameNanos)
        easeStart = frameNanos
        easing = true
    }

    fun cut() {
        easing = false
    }

    override fun positionAt(frameNanos: Long): Long {
        val actual = player.positionMicrosAt(frameNanos)
        if (!easing) return actual
        val t = (frameNanos - easeStart) / EASE_NANOS
        if (t >= 1f) return actual
        return easeFrom + ((actual - easeFrom) * Motion.EaseOut.transform(t.coerceAtLeast(0f))).toLong()
    }

    private companion object {
        const val EASE_NANOS = Motion.RollStartMs * 1_000_000f
    }
}
