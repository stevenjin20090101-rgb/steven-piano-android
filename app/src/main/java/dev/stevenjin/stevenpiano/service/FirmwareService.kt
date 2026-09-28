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
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.stevenjin.stevenpiano.MainActivity
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.firmware.FirmwareFailures
import dev.stevenjin.stevenpiano.firmware.FirmwareState
import dev.stevenjin.stevenpiano.graph
import dev.stevenjin.stevenpiano.ui.FirmwareCopy
import dev.stevenjin.stevenpiano.ui.Route
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The piano's firmware update, in the foreground (v1.6.1 — M21): a `connectedDevice` service (the
 * app talks to the piano over Bluetooth throughout), started by the Firmware page's Update or Retry
 * while the app is in the foreground. It keeps the process going with the screen off or the app in
 * the background, holds a partial wake lock for at most [WAKE_LOCK_MS], and shows the transfer in a
 * low-importance notification (channel "firmware"): "Updating the piano", the step and its progress,
 * and Cancel until END. The transfer itself runs in the app's scope ([dev.stevenjin.stevenpiano.firmware.FirmwareUpdater]):
 * this service only follows it, and stops when it is over, leaving one line of how it ended. Cancel
 * and Android's timeout ([onTimeout]) cancel it cleanly (ABORT before END). A refused start (the
 * app went to the background, or a Bluetooth permission missing) leaves the transfer running
 * in-process without the notification.
 */
class FirmwareService : Service() {
    private val scope = MainScope()
    private var watching: Job? = null
    private var lastPostedAt = 0L
    private var sawBusy = false
    private val wakeLock: PowerManager.WakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val updater = graph.firmwareUpdater
        if (intent?.action == ACTION_CANCEL) {
            updater.cancel()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, ID, notificationFor(updater.state.value), CONNECTED_DEVICE)
        } catch (e: RuntimeException) {   // refused: the update goes on in the app's process, without the notification
            Log.w(TAG, "Updating the piano without the foreground service: ${e.javaClass.simpleName}")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!wakeLock.isHeld) wakeLock.acquire(WAKE_LOCK_MS)
        if (watching?.isActive != true) {
            watching = scope.launch {
                updater.state.collect { state ->
                    if (state.busy) {
                        sawBusy = true
                        post(state)
                    } else {
                        finish(state)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int) = cancelForTimeout()

    /** Android's time for this type ran out: the update is cancelled cleanly (ABORT before END), and the service ends. */
    override fun onTimeout(startId: Int, fgsType: Int) = cancelForTimeout()

    override fun onDestroy() {
        if (wakeLock.isHeld) wakeLock.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun cancelForTimeout() {
        graph.firmwareUpdater.cancel()
        finish(graph.firmwareUpdater.state.value)
    }

    /** The transfer is over: the notification goes, one line says how it ended (not after a cancel), and the service stops. */
    private fun finish(state: FirmwareState) {
        watching?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (wakeLock.isHeld) wakeLock.release()
        if (sawBusy) outcome(state)?.let { line -> notify(RESULT_ID, result(line)) }
        stopSelf()
    }

    private fun outcome(state: FirmwareState): String? = when (state) {
        is FirmwareState.Done -> FirmwareCopy.done(state.version, state.confirming)
        is FirmwareState.Failed -> if (state.rolledBack || state.message == FirmwareFailures.DIDNT_COME_BACK) state.message else FirmwareFailures.DIDNT_FINISH
        else -> null
    }

    /** At most a few updates a second: Android drops notifications that change faster. */
    private fun post(state: FirmwareState) {
        val now = SystemClock.elapsedRealtime()
        val stepChanged = state !is FirmwareState.Downloading && state !is FirmwareState.Sending
        if (!stepChanged && now - lastPostedAt < UPDATE_MS) return
        lastPostedAt = now
        notify(ID, notificationFor(state))
    }

    private fun notify(id: Int, notification: Notification) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) NotificationManagerCompat.from(this).notify(id, notification)
    }

    private fun notificationFor(state: FirmwareState): Notification {
        val fraction = FirmwareCopy.fraction(state)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_piano)
            .setContentTitle(FirmwareCopy.NOTIFICATION_TITLE)
            .setContentText(FirmwareCopy.progress(state))
            .setProgress(PROGRESS_MAX, ((fraction ?: 0f) * PROGRESS_MAX).toInt(), fraction == null)
            .setContentIntent(openPiano())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (state.cancellable) builder.addAction(0, "Cancel", cancel())
        return builder.build()
    }

    private fun result(line: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_piano)
        .setContentTitle(line)
        .setContentIntent(openPiano())
        .setAutoCancel(true)
        .setSilent(true)
        .build()

    private fun openPiano(): PendingIntent = PendingIntent.getActivity(
        this,
        OPEN_REQUEST,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, Route.Piano.path)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancel(): PendingIntent = PendingIntent.getService(
        this,
        CANCEL_REQUEST,
        Intent(this, FirmwareService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_ID = "firmware"
        private const val ID = 6
        private const val RESULT_ID = 7
        private const val OPEN_REQUEST = 6
        private const val CANCEL_REQUEST = 7
        private const val TAG = "Firmware"
        private const val WAKE_LOCK_TAG = "StevenPiano:firmware"

        /** A transfer takes about two minutes; ten is the ceiling on the wake lock, whatever happens. */
        private const val WAKE_LOCK_MS = 10 * 60_000L
        private const val UPDATE_MS = 400L
        private const val PROGRESS_MAX = 1_000
        private const val ACTION_CANCEL = "dev.stevenjin.stevenpiano.action.CANCEL_FIRMWARE"

        /** The service's type from Android 10, where types began; before it, none is passed. */
        private val CONNECTED_DEVICE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0

        fun createChannel(context: Context) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Piano firmware")
                .setDescription("Progress while the piano's firmware updates, and how it ended.")
                .setShowBadge(false)
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        /** Follows the update the Firmware page has just started. Call it while the app is in the foreground. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, FirmwareService::class.java))
            } catch (e: IllegalStateException) {   // ForegroundServiceStartNotAllowedException on Android 12+
                Log.w(TAG, "Updating the piano without the foreground service: ${e.javaClass.simpleName}")
            }
        }
    }
}
