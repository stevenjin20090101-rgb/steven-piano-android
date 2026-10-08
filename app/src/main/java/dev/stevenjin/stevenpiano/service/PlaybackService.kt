// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.service

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.Player
import dev.stevenjin.stevenpiano.player.PlayerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps playback going with the screen off. A mediaPlayback foreground service, started from
 * the app's play controls (it must be started from the foreground) or the web panel's, that holds
 * the media notification and session and a partial wake lock while playing. Nothing starts it by
 * itself since 1.20 (M54: timed plays were removed). Pausing detaches the notification; a stop ends
 * the service. Swiping the app away, or the service ending while the piano is sounding, silences
 * the piano first (pedal up, then all notes off).
 */
class PlaybackService : Service() {
    private val scope = MainScope()
    private lateinit var player: Player
    private lateinit var session: MediaSessionHolder
    private lateinit var wakeLock: PowerManager.WakeLock
    private var inForeground = false
    private var silenced = false
    private var stopJob: Job? = null
    private var refreshJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        player = graph.player
        session = MediaSessionHolder(this, player, scope, graph.library::summaries, ::stopNow)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
        scope.launch { player.state.collect(::render) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> player.togglePlayPause()
            ACTION_PREVIOUS -> player.previous()
            ACTION_NEXT -> player.next()
            ACTION_DISMISS -> {
                player.leaveChannel()   // dismissed while paused: a channel is over
                stopNow()
                return START_NOT_STICKY
            }
            else -> goForeground(player.state.value)   // started from the app: in the foreground within 5 s, always
        }
        render(player.state.value)
        return START_NOT_STICKY
    }

    /** Swiped away from Recents: silence the piano before the process can go. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        silence()
        stopNow()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (player.state.value.status != PlaybackStatus.Stopped) silence()   // a stopped player has silenced the piano already
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        session.release()
        super.onDestroy()
    }

    private fun render(state: PlayerState) {
        session.update(state)
        val playing = state.status == PlaybackStatus.Playing
        // Between pieces of a queue the player is stopped for 1.5 s, then plays whatever follows:
        // the next piece, the top again (Repeat all) or the same piece (Repeat one).
        val advancing = state.status == PlaybackStatus.Stopped && state.piece != null && state.problem == null &&
            state.queue.advancesAtEnd
        when {
            playing || state.loading || advancing -> {
                if (!advancing) cancelStop()
                goForeground(state)
                keepAwake(true)
                if (advancing) stopAfter(ADVANCE_GRACE_MS)
            }
            state.status == PlaybackStatus.Paused -> {
                cancelStop()
                keepAwake(false)
                if (inForeground) {
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                    inForeground = false
                }
                post(state)
            }
            else -> {
                keepAwake(false)
                stopAfter(IDLE_GRACE_MS)   // a play command may be on its way to the scheduler
            }
        }
    }

    private fun goForeground(state: PlayerState) {
        val notification = PlaybackNotification.build(this, state, session.token)
        try {
            ServiceCompat.startForeground(this, PlaybackNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            inForeground = true
        } catch (e: RuntimeException) {   // resumed from the background, where Android may refuse
            Log.w(TAG, "Playback continues outside the foreground: ${e.message}")
            post(state)
        }
    }

    /** The wake lock, and a session refresh so the lock screen's position stays true after seeks. */
    private fun keepAwake(on: Boolean) {
        if (!on) {
            refreshJob?.cancel()
            refreshJob = null
            if (wakeLock.isHeld) wakeLock.release()
            return
        }
        wakeLock.acquire(WAKE_LOCK_MS)
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            while (isActive) {
                delay(REFRESH_MS)
                wakeLock.acquire(WAKE_LOCK_MS)
                session.update(player.state.value)
            }
        }
    }

    private fun post(state: PlayerState) {
        val allowed = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        if (allowed) NotificationManagerCompat.from(this).notify(PlaybackNotification.ID, PlaybackNotification.build(this, state, session.token))
    }

    private fun stopAfter(ms: Long) {
        if (stopJob?.isActive == true) return
        stopJob = scope.launch {
            delay(ms)
            stopNow()
        }
    }

    private fun cancelStop() {
        stopJob?.cancel()
        stopJob = null
    }

    private fun stopNow() {
        cancelStop()
        keepAwake(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(PlaybackNotification.ID)   // a detached one stays otherwise
        inForeground = false
        stopSelf()
    }

    private fun silence() {
        if (silenced) return
        silenced = true
        player.stopAndFlush(FLUSH_MS)
    }

    companion object {
        const val ACTION_START = "dev.stevenjin.stevenpiano.action.START"
        const val ACTION_TOGGLE = "dev.stevenjin.stevenpiano.action.TOGGLE"
        const val ACTION_PREVIOUS = "dev.stevenjin.stevenpiano.action.PREVIOUS"
        const val ACTION_NEXT = "dev.stevenjin.stevenpiano.action.NEXT"
        const val ACTION_DISMISS = "dev.stevenjin.stevenpiano.action.DISMISS"

        private const val TAG = "PlaybackService"
        private const val WAKE_LOCK_TAG = "StevenPiano:playback"
        private const val WAKE_LOCK_MS = 10 * 60_000L
        private const val REFRESH_MS = 5_000L
        private const val IDLE_GRACE_MS = 1_000L
        private const val ADVANCE_GRACE_MS = 6_000L
        private const val FLUSH_MS = 300L

        /** From the app's play controls, which are in the foreground, as starting this service requires. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java).setAction(ACTION_START))
        }

        fun pendingAction(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
