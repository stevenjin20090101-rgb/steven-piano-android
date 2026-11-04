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
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.stevenjin.stevenpiano.MainActivity
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.data.art.ArtworkProgress
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.ArtworkCopy
import dev.stevenjin.stevenpiano.ui.Route
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Composers' portraits and notes, and pieces' album covers (v1.15 — M40), fetched in the background:
 * a dataSync foreground service with the notification "Fetching artwork and notes" and its progress
 * (channel "artwork"). Started after an import that brought pieces in (from the import service, while
 * it is still in the foreground, since Android 12 refuses most starts from the background), when the
 * app opens with composers or covers due, and by the `+` sheet's "Fetch artwork and notes for every
 * composer" ([start] with force). It queues the composers that are due, then (Album covers on) the
 * covers, follows the one artwork worker, and stops once the worker is idle. When Android's time limit for data sync runs out ([onTimeout]) it
 * stops cleanly; whatever was left is fetched on the next start.
 */
class ArtworkService : ForegroundJobService(ID) {
    private var watcher: Job? = null
    private var pending = 0
    private var latestStartId = 0
    private var lastPostedAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val artwork = graph.artwork
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
        try {
            startInForeground(notificationFor(artwork.progress.value))
        } catch (e: RuntimeException) {   // refused (the app went to the background meanwhile): fetch without the notification
            Log.w(TAG, "Fetching artwork without the foreground service: ${e.message}")
            graph.appScope.launch { artwork.requestDue(force) }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        pending++
        scope.launch {
            try {
                artwork.requestDue(force)
            } finally {
                pending--
            }
            if (watcher?.isActive != true) {
                watcher = scope.launch { artwork.progress.collect { if (it.idle) finishIfDone() else post(it) } }
            } else if (artwork.progress.value.idle) {
                finishIfDone()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15's six hours a day for data sync ran out: stop now; the rest waits for the next start. */
    override fun stopForTimeout() {
        graph.artwork.cancelBackground()
        finish()
    }

    /** The worker is idle: stop, unless another start is still queueing its composers. */
    private fun finishIfDone() {
        if (pending > 0) return
        leaveForeground()
        stopSelf(latestStartId)
    }

    /** At most a few updates a second: Android drops notifications that change faster. */
    private fun post(progress: ArtworkProgress) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPostedAt < UPDATE_MS) return
        lastPostedAt = now
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(this).notify(ID, notificationFor(progress))
    }

    private fun notificationFor(progress: ArtworkProgress): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_piano)
            .setContentTitle("Fetching artwork and notes")
            .setContentText(ArtworkCopy.notification(progress))
            .setProgress(progress.total, progress.done, progress.total == 0)
            .setContentIntent(openLibrary())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    private fun openLibrary(): PendingIntent = PendingIntent.getActivity(
        this,
        2,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, Route.Library.path)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "artwork"
        private const val ID = 3
        private const val TAG = "ArtworkService"
        private const val UPDATE_MS = 400L
        private const val EXTRA_FORCE = "dev.stevenjin.stevenpiano.extra.FORCE"

        fun createChannel(context: Context) {
            createJobChannel(context, CHANNEL_ID, "Artwork", "Progress while composers' portraits and notes come from Wikipedia, and album covers from Apple.")
        }

        /**
         * Fetches every composer that is due; with [force], those found missing before too. Call it
         * while the app or one of its foreground services is in the foreground. When Android refuses
         * the service anyway, the fetch runs in the app's process without its notification.
         */
        fun start(context: Context, force: Boolean) {
            val intent = Intent(context, ArtworkService::class.java).putExtra(EXTRA_FORCE, force)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {   // ForegroundServiceStartNotAllowedException on Android 12+
                Log.w(TAG, "Fetching artwork without the foreground service: ${e.message}")
                val graph = context.graph
                graph.appScope.launch { graph.artwork.requestDue(force) }
            }
        }
    }
}
