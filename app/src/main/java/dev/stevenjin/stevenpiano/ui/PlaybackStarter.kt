// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.stevenjin.stevenpiano.channels.ChannelPlayer
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.service.PlaybackService

/**
 * Playback started by a tap in the app: a library row, a playlist's Play or Shuffle, a channel's
 * card, a row menu's Play next or Add to queue, or the transport. Anything that may start
 * the piano also starts the playback service, which must be started from the foreground; the
 * first time, the notification permission is asked for (API 33+), in context.
 */
class PlaybackStarter(
    private val context: Context,
    private val player: Player,
    private val channels: ChannelPlayer,
    private val askForNotifications: () -> Unit,
) {
    fun play(pieceId: Long, queue: List<Long>) {
        player.play(pieceId, queue)
        started()
    }

    /** A playlist's (or a composer's) Play, in order, or Shuffle. */
    fun playAll(pieceIds: List<Long>, shuffle: Boolean) {
        if (pieceIds.isEmpty()) return
        player.playAll(pieceIds, shuffle)
        started()
    }

    /** Channel [key], endlessly, from a fresh shuffle of its pool (a card's tap). False when its pool is too small to play. */
    fun playChannel(key: String): Boolean {
        if (!channels.play(key)) return false
        started()
        return true
    }

    /** Right after the current piece; with nothing queued yet it plays now. */
    fun playNext(pieceIds: List<Long>) {
        if (player.playNext(pieceIds)) started()
    }

    /** At the end of the queue; with nothing queued yet it plays now. */
    fun addToQueue(pieceIds: List<Long>) {
        if (player.addToQueue(pieceIds)) started()
    }

    fun togglePlayPause() {
        if (player.state.value.status == PlaybackStatus.Playing) {
            player.pause()
        } else {
            player.resume()
            started()
        }
    }

    fun previous() {
        player.previous()
        started()
    }

    fun next() {
        player.next()
        started()
    }

    private fun started() {
        askForNotifications()
        PlaybackService.start(context)
    }

    companion object {
        /** Asked once per process at most; Android itself stops asking after two refusals. */
        private var askedForNotifications = false

        fun shouldAskForNotifications(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedForNotifications &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

        fun markAsked() {
            askedForNotifications = true
        }
    }
}

@Composable
fun rememberPlaybackStarter(): PlaybackStarter {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }   // playback goes on either way
    return remember(context) {
        PlaybackStarter(context.applicationContext, context.graph.player, context.graph.channelPlayer) {
            if (PlaybackStarter.shouldAskForNotifications(context)) {
                PlaybackStarter.markAsked()
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
