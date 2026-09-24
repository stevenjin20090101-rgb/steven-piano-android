// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

// androidx.media 1.8 marks its session classes deprecated in favour of Media3, which the
// plan leaves out; MediaSessionCompat still drives the system media controls.
@file:Suppress("DEPRECATION")

package dev.stevenjin.stevenpiano.service

import android.content.Context
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.player.PlayerState

/**
 * The media session: the lock screen, the system's media controls and headset buttons see the
 * player through it and drive it back. It mirrors [PlayerState]; [onStop] ends the service.
 * Create it, and call it, on the main thread, like the player.
 */
class MediaSessionHolder(context: Context, private val player: Player, onStop: () -> Unit) {
    private val session = MediaSessionCompat(context, TAG).apply {
        setCallback(
            object : MediaSessionCompat.Callback() {
                override fun onPlay() = player.resume()

                override fun onPause() = player.pause()

                override fun onSkipToNext() = player.next()

                override fun onSkipToPrevious() = player.previous()

                override fun onSeekTo(positionMs: Long) = player.seek(positionMs * 1_000L)

                override fun onStop() {
                    player.stop()
                    onStop()
                }
            },
        )
        setSessionActivity(PlaybackNotification.openNowPlaying(context))
        isActive = true
    }
    private var shownPieceId: Long? = null

    val token: MediaSessionCompat.Token get() = session.sessionToken

    /** Publishes the piece (when it changed) and where playback is now. */
    fun update(state: PlayerState) {
        val piece = state.piece
        if (piece?.pieceId != shownPieceId) {
            shownPieceId = piece?.pieceId
            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, piece?.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, piece?.composer)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (piece?.durationMicros ?: 0L) / 1_000L)
                    .build(),
            )
        }
        val playing = state.status == PlaybackStatus.Playing
        val code = when (state.status) {
            PlaybackStatus.Playing -> PlaybackStateCompat.STATE_PLAYING
            PlaybackStatus.Paused -> PlaybackStateCompat.STATE_PAUSED
            PlaybackStatus.Stopped -> PlaybackStateCompat.STATE_STOPPED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(ACTIONS)
                .setState(code, player.positionMicrosNow() / 1_000L, if (playing) state.tempoPct / 100f else 0f, SystemClock.elapsedRealtime())
                .build(),
        )
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    private companion object {
        const val TAG = "StevenPiano"
        const val ACTIONS = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP
    }
}
