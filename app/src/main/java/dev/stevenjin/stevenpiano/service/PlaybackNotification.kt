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

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.app.NotificationCompat.MediaStyle
import dev.stevenjin.stevenpiano.MainActivity
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.PlayerState
import dev.stevenjin.stevenpiano.ui.Route

/**
 * The playback notification (channel "playback"): the piece's title and composer, previous ·
 * play/pause · next, in the media style with the monochrome roll glyph. Tapping it opens Now
 * playing; dismissing it while paused ends playback.
 */
object PlaybackNotification {
    const val CHANNEL_ID = "playback"
    const val ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName("Playback")
            .setDescription("The piece playing on the piano, with play and pause.")
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun build(context: Context, state: PlayerState, session: MediaSessionCompat.Token): Notification {
        val playing = state.status == PlaybackStatus.Playing
        val piece = state.piece
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_piano)
            .setContentTitle(piece?.title ?: context.getString(R.string.app_name))
            .setContentText(piece?.composer?.ifBlank { null })
            .setContentIntent(openNowPlaying(context))
            .setDeleteIntent(PlaybackService.pendingAction(context, PlaybackService.ACTION_DISMISS))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_skip_previous, "Previous", PlaybackService.pendingAction(context, PlaybackService.ACTION_PREVIOUS))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                if (playing) "Pause" else "Play",
                PlaybackService.pendingAction(context, PlaybackService.ACTION_TOGGLE),
            )
            .addAction(R.drawable.ic_skip_next, "Next", PlaybackService.pendingAction(context, PlaybackService.ACTION_NEXT))
            .setStyle(MediaStyle().setMediaSession(session).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    /** Opens the app on the Now playing tab. */
    fun openNowPlaying(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, Route.NowPlaying.path)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
