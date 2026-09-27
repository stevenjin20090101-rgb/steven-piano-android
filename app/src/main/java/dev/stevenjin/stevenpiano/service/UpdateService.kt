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
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.stevenjin.stevenpiano.MainActivity
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.Route
import dev.stevenjin.stevenpiano.ui.UpdateCopy
import dev.stevenjin.stevenpiano.update.UpdateManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A release's download: a small dataSync foreground service with the notification "Downloading
 * Steven Piano 1.4", its progress, and Cancel (channel "updates"), so the file keeps coming with
 * the screen off. Started by the Piano tab's Update button, while the app is in the foreground.
 * When the file has arrived and matched, the service stops and the release is handed to Android
 * ([dev.stevenjin.stevenpiano.update.Updater.install]). Cancel, or Android's time for data sync
 * running out ([onTimeout]), stops the download; the release stays on offer.
 */
class UpdateService : Service() {
    private val scope = MainScope()
    private var job: Job? = null
    private var lastPostedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel() ?: stopSelf(startId)
            return START_NOT_STICKY
        }
        val updater = graph.updater
        val manifest = updater.state.value.manifest
        try {
            ServiceCompat.startForeground(this, ID, notificationFor(manifest, 0L), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: RuntimeException) {   // refused (the app went to the background meanwhile): download without the notification
            Log.w(TAG, "Downloading without the foreground service: ${e.javaClass.simpleName}")
            if (manifest != null && job?.isActive != true) graph.appScope.launch { updater.downloadAndInstall(manifest, applicationContext) }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (manifest == null || job?.isActive == true) {
            if (job?.isActive != true) finish()
            return START_NOT_STICKY
        }
        job = scope.launch {
            try {
                updater.downloadAndInstall(manifest, this@UpdateService) { bytes, _ -> post(manifest, bytes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {   // the updater reports its own failures; this is anything else
                Log.w(TAG, "The update stopped: ${e.javaClass.simpleName}")
            } finally {
                finish()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) = cancelForTimeout()

    /** Android 15's six hours a day for data sync ran out: the download stops; the release stays on offer. */
    override fun onTimeout(startId: Int, fgsType: Int) = cancelForTimeout()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun cancelForTimeout() {
        job?.cancel()
        finish()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** At most a few updates a second: Android drops notifications that change faster. */
    private fun post(manifest: UpdateManifest, bytes: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPostedAt < UPDATE_MS && bytes < manifest.sizeBytes) return
        lastPostedAt = now
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(this).notify(ID, notificationFor(manifest, bytes))
    }

    private fun notificationFor(manifest: UpdateManifest?, bytes: Long): Notification {
        val total = manifest?.sizeBytes ?: 0L
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_piano)
            .setContentTitle(UpdateCopy.notificationTitle(manifest?.versionName))
            .setContentText(if (manifest == null) null else UpdateCopy.megabytes(bytes, total))
            .setProgress(PROGRESS_MAX, if (total > 0) (bytes * PROGRESS_MAX / total).toInt() else 0, total == 0L)
            .setContentIntent(openPiano())
            .addAction(0, "Cancel", cancel())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun openPiano(): PendingIntent = PendingIntent.getActivity(
        this,
        4,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, Route.Piano.path)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancel(): PendingIntent = PendingIntent.getService(
        this,
        5,
        Intent(this, UpdateService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "updates"
        private const val ID = 4
        private const val TAG = "Updates"
        private const val UPDATE_MS = 400L
        private const val PROGRESS_MAX = 1_000
        private const val ACTION_CANCEL = "dev.stevenjin.stevenpiano.action.CANCEL_UPDATE"

        fun createChannel(context: Context) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Updates")
                .setDescription("Progress while a new version of Steven Piano downloads.")
                .setShowBadge(false)
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        /** Downloads the release on offer. Call it while the app is in the foreground (the Update button). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, UpdateService::class.java))
            } catch (e: IllegalStateException) {   // ForegroundServiceStartNotAllowedException on Android 12+
                Log.w(TAG, "Downloading without the foreground service: ${e.javaClass.simpleName}")
                val graph = context.graph
                val manifest = graph.updater.state.value.manifest ?: return
                graph.appScope.launch { graph.updater.downloadAndInstall(manifest, context.applicationContext) }
            }
        }
    }
}
