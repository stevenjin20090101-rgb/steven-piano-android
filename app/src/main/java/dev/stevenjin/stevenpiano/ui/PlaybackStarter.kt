// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.player.Player

/** Playback started by a tap in the app: a library row, or the play button. */
class PlaybackStarter(private val player: Player) {
    fun play(pieceId: Long, queue: List<Long>) = player.play(pieceId, queue)

    fun togglePlayPause() = player.togglePlayPause()
}

@Composable
fun rememberPlaybackStarter(): PlaybackStarter {
    val player = LocalContext.current.graph.player
    return remember(player) { PlaybackStarter(player) }
}
