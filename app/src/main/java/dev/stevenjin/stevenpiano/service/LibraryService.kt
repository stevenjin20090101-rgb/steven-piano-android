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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.stevenjin.stevenpiano.MainActivity
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.library.PackState
import dev.stevenjin.stevenpiano.ui.LibraryCopy
import dev.stevenjin.stevenpiano.ui.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Steven's library arriving (v1.10 — M27): a dataSync foreground service holding the whole load, from the
 * tap to the last piece: the pack's manifest, its zip into `cacheDir/library` (checked against the
 * manifest's size and SHA-256), then its pieces through the app's importer, with one notification
 * ("Loading Steven's library", "23 of 61 MB", then "Imported 204 of 1,726") and Cancel, on the imports'
 * channel. The work is [dev.stevenjin.stevenpiano.library.LibraryPack.run]; this holds the foreground for
 * it, so the load goes on with the screen off or the app left. The import runs here rather than in
 * [ImportService]: Android 12+ may refuse a second foreground service started from the background, where a
 * 61 MB download often ends. When Android refuses this one (the console's `library.load` while the app is
 * in the background), the load runs in the app's process without the notification. Cancel, or Android's
 * time for data sync running out ([onTimeout]), stops it; what came in stays, and the pack stays on offer.
 */
class LibraryService : Service() {
    private val scope = MainScope()
    private var job: Job? = null
    private var lastPostedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel() ?: stopSelf(startId)
            return START_NOT_STICKY
        }
        val pack = graph.libraryPack
        val everything = intent?.getBooleanExtra(EXTRA_EVERYTHING, false) == true
        try {
            ServiceCompat.startForeground(this, ID, notificationFor(pack.state.value, graph.importProgress.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: RuntimeException) {   // refused (the app went to the background meanwhile): the load goes on without the notification
            Log.w(TAG, "Loading the library without the foreground service: ${e.javaClass.simpleName}")
            if (job?.isActive != true) graph.appScope.launch { pack.run(everything) }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        job = scope.launch {
            val progress = launch { combine(pack.state, graph.importProgress) { state, import -> state to import }.collect { (state, import) -> post(state, import) } }
            try {
                pack.run(everything)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {   // the pack words its own failures; this is anything else
                Log.w(TAG, "The library's load stopped: ${e.javaClass.simpleName}")
            } finally {
                progress.cancel()
                finish()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) = cancelForTimeout()

    /** Android 15's six hours a day for data sync ran out: the load stops; the pack stays on offer. */
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
    private fun post(state: PackState, import: ImportProgress) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPostedAt < UPDATE_MS) return
        lastPostedAt = now
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(this).notify(ID, notificationFor(state, import))
    }

    private fun notificationFor(state: PackState, import: ImportProgress): Notification {
        val builder = NotificationCompat.Builder(this, ImportService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_piano)
            .setContentTitle(LibraryCopy.NOTIFICATION_TITLE)
            .setContentText(LibraryCopy.notificationText(state, import))
            .setContentIntent(openLibrary())
            .addAction(0, "Cancel", cancel())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when {
            state is PackState.Downloading && state.total > 0 ->
                builder.setProgress(PROGRESS_MAX, (state.done.coerceIn(0, state.total) * PROGRESS_MAX / state.total).toInt(), false)
            state == PackState.Importing && import.total > 0 -> builder.setProgress(import.total, import.done, false)
            else -> builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun openLibrary(): PendingIntent = PendingIntent.getActivity(
        this,
        OPEN_REQUEST,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, Route.Library.path)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancel(): PendingIntent = PendingIntent.getService(
        this,
        CANCEL_REQUEST,
        Intent(this, LibraryService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "Library"
        private const val ID = 9
        private const val OPEN_REQUEST = 11
        private const val CANCEL_REQUEST = 12
        private const val UPDATE_MS = 400L
        private const val PROGRESS_MAX = 1_000
        private const val ACTION_CANCEL = "dev.stevenjin.stevenpiano.action.CANCEL_LIBRARY"
        private const val EXTRA_EVERYTHING = "dev.stevenjin.stevenpiano.extra.EVERYTHING"

        /**
         * Starts the load ([dev.stevenjin.stevenpiano.library.LibraryPack.load] calls it). When Android refuses a
         * foreground service (the app in the background), the load runs in the app's process without the
         * notification.
         */
        fun start(context: Context, everything: Boolean) {
            val intent = Intent(context, LibraryService::class.java).putExtra(EXTRA_EVERYTHING, everything)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {   // ForegroundServiceStartNotAllowedException on Android 12+
                Log.w(TAG, "Loading the library without the foreground service: ${e.javaClass.simpleName}")
                val graph = context.graph
                graph.appScope.launch { graph.libraryPack.run(everything) }
            }
        }
    }
}
