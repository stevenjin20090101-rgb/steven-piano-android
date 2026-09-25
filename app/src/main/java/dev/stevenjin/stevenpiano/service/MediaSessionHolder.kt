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
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import dev.stevenjin.stevenpiano.data.db.PieceSummary
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.player.QueueSnapshot
import dev.stevenjin.stevenpiano.player.RepeatMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The media session: the lock screen, the system's media controls, headset buttons and car
 * displays see the player through it and drive it back. It mirrors [PlayerState]: the piece, the
 * position, the shuffle and repeat modes, and a window of the queue (at most 50 entries, a Binder
 * limit), named through [names] (the library, off the main thread). [onStop] ends the service.
 * Create it, and call it, on the main thread, like the player.
 */
class MediaSessionHolder(
    context: Context,
    private val player: Player,
    private val scope: CoroutineScope,
    private val names: suspend (Collection<Long>) -> Map<Long, PieceSummary>,
    onStop: () -> Unit,
) {
    private val session = MediaSessionCompat(context, TAG).apply {
        setCallback(
            object : MediaSessionCompat.Callback() {
                override fun onPlay() = player.resume()

                override fun onPause() = player.pause()

                override fun onSkipToNext() = player.next()

                override fun onSkipToPrevious() = player.previous()

                override fun onSkipToQueueItem(id: Long) = player.skipToQueueEntry(id)

                override fun onSeekTo(positionMs: Long) = player.seek(positionMs * 1_000L)

                override fun onSetShuffleMode(shuffleMode: Int) = player.setShuffle(shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE)

                override fun onSetRepeatMode(repeatMode: Int) = player.setRepeat(repeatOf(repeatMode))

                override fun onStop() {
                    player.stop()
                    onStop()
                }
            },
        )
        setSessionActivity(PlaybackNotification.openNowPlaying(context))
        setQueueTitle(QUEUE_TITLE)
        isActive = true
    }
    private var shownPieceId: Long? = null
    private var shownShuffle: Boolean? = null
    private var shownRepeat: RepeatMode? = null
    private var shownWindow: List<Long>? = null
    private var queueJob: Job? = null

    val token: MediaSessionCompat.Token get() = session.sessionToken

    /** Publishes the piece (when it changed), the modes and the queue (when they changed), and where playback is now. */
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
        val queue = state.queue
        if (queue.shuffle != shownShuffle) {
            shownShuffle = queue.shuffle
            session.setShuffleMode(if (queue.shuffle) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
        }
        if (queue.repeat != shownRepeat) {
            shownRepeat = queue.repeat
            session.setRepeatMode(repeatModeOf(queue.repeat))
        }
        publishQueue(queue)
        val playing = state.status == PlaybackStatus.Playing
        val code = when (state.status) {
            PlaybackStatus.Playing -> PlaybackStateCompat.STATE_PLAYING
            PlaybackStatus.Paused -> PlaybackStateCompat.STATE_PAUSED
            PlaybackStatus.Stopped -> PlaybackStateCompat.STATE_STOPPED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(ACTIONS)
                .setActiveQueueItemId(queue.currentUid ?: MediaSessionCompat.QueueItem.UNKNOWN_ID.toLong())
                .setState(code, player.positionMicrosNow() / 1_000L, if (playing) state.tempoPct / 100f else 0f, SystemClock.elapsedRealtime())
                .build(),
        )
    }

    fun release() {
        queueJob?.cancel()
        session.isActive = false
        session.release()
    }

    /** The window of the queue around the current entry, each named by title and composer; ids are the entries' uids. */
    private fun publishQueue(queue: QueueSnapshot) {
        val range = queue.window()
        val uids = queue.uids.slice(range)
        if (uids == shownWindow) return
        shownWindow = uids
        val ids = queue.ids.slice(range)
        queueJob?.cancel()
        queueJob = scope.launch {
            val found = try {
                names(ids)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {   // the queue still works without names
                emptyMap()
            }
            session.setQueue(
                uids.indices.map { i ->
                    val summary = found[ids[i]]
                    val description = MediaDescriptionCompat.Builder()
                        .setMediaId(ids[i].toString())
                        .setTitle(summary?.title)
                        .setSubtitle(summary?.composerShort?.ifBlank { null })
                        .build()
                    MediaSessionCompat.QueueItem(description, uids[i])
                },
            )
        }
    }

    private companion object {
        const val TAG = "StevenPiano"
        const val QUEUE_TITLE = "Up next"
        const val ACTIONS = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
            PlaybackStateCompat.ACTION_SET_REPEAT_MODE

        fun repeatModeOf(mode: RepeatMode): Int = when (mode) {
            RepeatMode.OFF -> PlaybackStateCompat.REPEAT_MODE_NONE
            RepeatMode.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
            RepeatMode.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
        }

        fun repeatOf(mode: Int): RepeatMode = when (mode) {
            PlaybackStateCompat.REPEAT_MODE_ONE -> RepeatMode.ONE
            PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
    }
}
