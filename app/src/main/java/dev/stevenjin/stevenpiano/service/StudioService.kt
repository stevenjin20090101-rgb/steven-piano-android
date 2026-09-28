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
import dev.stevenjin.stevenpiano.studio.JobKind
import dev.stevenjin.stevenpiano.studio.JobState
import dev.stevenjin.stevenpiano.studio.StudioJob
import dev.stevenjin.stevenpiano.ui.Route
import dev.stevenjin.stevenpiano.ui.StudioCopy
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Studio's jobs in the foreground (v1.7 — M23): a `dataSync` service, started whenever a job is queued
 * (a model's download, a recording's transcription), that keeps the process going with the screen off
 * or the app in the background, and shows the job running in a low-importance notification (channel
 * "studio"): what it is, its line and its progress, and Cancel. The jobs themselves run in the app
 * ([dev.stevenjin.stevenpiano.studio.Studio], one at a time, on their own background-priority thread,
 * with a partial wake lock around each): this service only follows them, and stops when none waits,
 * leaving one line of how the last one ended. Android's timeout ([onTimeout]) cancels them. A refused
 * start (the app in the background) leaves the jobs running in the app's process, without the
 * notification.
 */
class StudioService : Service() {
    private val scope = MainScope()
    private var watching: Job? = null
    private var lastPostedAt = 0L
    private var lastShown: StudioJob? = null
    private val seen = HashSet<Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val studio = graph.studio
        if (intent?.action == ACTION_CANCEL) {
            studio.jobs.running?.let { studio.cancel(it.id) }
            return START_NOT_STICKY
        }
        val current = shown(studio.jobs.jobs.value)
        try {
            ServiceCompat.startForeground(this, ID, notificationFor(current), DATA_SYNC)
        } catch (e: RuntimeException) {   // refused: the jobs go on in the app's process, without the notification
            Log.w(TAG, "Studio without the foreground service: ${e.javaClass.simpleName}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (watching?.isActive != true) {
            watching = scope.launch {
                studio.jobs.jobs.collect { jobs ->
                    jobs.filter { !it.state.finished }.forEach { seen += it.id }
                    val job = shown(jobs)
                    if (job != null) post(job) else finish(jobs)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) = cancelForTimeout()

    /** Android's time for data sync ran out: every job stops, and the service ends. */
    override fun onTimeout(startId: Int, fgsType: Int) = cancelForTimeout()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun cancelForTimeout() {
        val studio = graph.studio
        studio.jobs.jobs.value.filter { !it.state.finished }.forEach { studio.cancel(it.id) }
        finish(studio.jobs.jobs.value)
    }

    /** The job to show: the one running, else the first waiting; null when none is left. */
    private fun shown(jobs: List<StudioJob>): StudioJob? =
        jobs.firstOrNull { it.state == JobState.Running } ?: jobs.firstOrNull { it.state == JobState.Queued }

    /** None is left: the notification goes, one line says how the last job this service followed ended, and the service stops. */
    private fun finish(jobs: List<StudioJob>) {
        watching?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        jobs.lastOrNull { it.id in seen && it.state.finished && it.state != JobState.Cancelled }?.let { notify(RESULT_ID, result(it)) }
        stopSelf()
    }

    /** At most a few updates a second, but a new job or step at once: Android drops notifications that change faster. */
    private fun post(job: StudioJob) {
        val now = SystemClock.elapsedRealtime()
        val before = lastShown
        val changed = before == null || before.id != job.id || before.step != job.step || before.state != job.state
        if (!changed && now - lastPostedAt < UPDATE_MS) return
        lastPostedAt = now
        lastShown = job
        notify(ID, notificationFor(job))
    }

    private fun notify(id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(this).notify(id, notification)
    }

    private fun notificationFor(job: StudioJob?): Notification {
        val review = graph.studio.review
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_piano)
            .setContentTitle(job?.let(StudioCopy::notificationTitle) ?: "Studio")
            .setContentText(job?.let { StudioCopy.jobLine(it, review.undecided.value, review.discardedNow.value) })
            .setContentIntent(open(Route.Piano))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val progress = job?.progress
        builder.setProgress(PROGRESS_MAX, ((progress ?: 0f) * PROGRESS_MAX).toInt(), job?.state == JobState.Running && progress == null)
        if (job != null) builder.addAction(0, "Cancel", cancel())
        return builder.build()
    }

    private fun result(job: StudioJob): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_piano)
        .setContentTitle(StudioCopy.ended(job))
        .setContentText(
            when {
                job.state == JobState.Failed -> job.error
                job.kind == JobKind.Transcribe -> StudioCopy.LISTEN
                else -> null
            },
        )
        .setContentIntent(open(if (job.kind == JobKind.Transcribe && job.state == JobState.Done) Route.Library else Route.Piano))
        .setAutoCancel(true)
        .setSilent(true)
        .build()

    private fun open(tab: Route): PendingIntent = PendingIntent.getActivity(
        this,
        if (tab == Route.Library) OPEN_LIBRARY_REQUEST else OPEN_PIANO_REQUEST,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, tab.path)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancel(): PendingIntent = PendingIntent.getService(
        this,
        CANCEL_REQUEST,
        Intent(this, StudioService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "studio"
        private const val ID = 8
        private const val RESULT_ID = 9
        private const val OPEN_PIANO_REQUEST = 8
        private const val OPEN_LIBRARY_REQUEST = 9
        private const val CANCEL_REQUEST = 10
        private const val TAG = "Studio"
        private const val UPDATE_MS = 400L
        private const val PROGRESS_MAX = 1_000
        private const val ACTION_CANCEL = "dev.stevenjin.stevenpiano.action.CANCEL_STUDIO"

        /** The service's type from Android 10, where types began; before it, none is passed. */
        private val DATA_SYNC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

        fun createChannel(context: Context) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Studio")
                .setDescription("Progress while a model downloads or a recording is transcribed, and how it ended.")
                .setShowBadge(false)
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        /** Follows the jobs Studio has just been given. Android refuses it from the background (the jobs run all the same). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, StudioService::class.java))
            } catch (e: IllegalStateException) {   // ForegroundServiceStartNotAllowedException on Android 12+
                Log.w(TAG, "Studio without the foreground service: ${e.javaClass.simpleName}")
            }
        }
    }
}
