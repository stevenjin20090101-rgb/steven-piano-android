// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.player

import dev.stevenjin.stevenpiano.midi.NoteList

enum class PlaybackStatus { Stopped, Playing, Paused }

/** The ranges Now playing and the Piano tab offer. */
object PlaybackLimits {
    val TempoPct = 25..200
    val Transpose = -12..12
    val VelocityPct = 50..150
}

/** The piece in the player: what Now playing shows. [notes] feed the note canvas. */
data class NowPlaying(
    val pieceId: Long,
    val title: String,
    val composer: String,
    val durationMicros: Long,
    val notes: NoteList,
)

/** Everything about playback except the moving position, which [Player.positionMicrosNow] reads per frame. */
data class PlayerState(
    val status: PlaybackStatus = PlaybackStatus.Stopped,
    val piece: NowPlaying? = null,
    val loading: Boolean = false,
    val tempoPct: Int = 100,
    val transpose: Int = 0,
    val velocityPct: Int = 100,
    val fold: Boolean = true,
    val skipDrums: Boolean = true,
    val queueIndex: Int = -1,
    val queueSize: Int = 0,
    /** Why the last piece could not be played, in plain English; null when all is well. */
    val problem: String? = null,
)
