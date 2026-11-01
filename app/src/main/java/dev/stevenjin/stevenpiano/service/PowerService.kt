// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.stevenjin.stevenpiano.MainActivity
import dev.stevenjin.stevenpiano.R
import dev.stevenjin.stevenpiano.ui.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The power watch's service (v1.39 — M77; DESIGN.md › v1.39 — M77): from 1.39 the plug keeps the piano on all day, and the
 * tablet's watches over it (the rail keeper and the power watch, which live in `AppGraph`) are the safety layer, so the
 * process must outlive the screen and the app in the background. This service does nothing but keep it alive, in the
 * foreground as `connectedDevice` (the plug is a Bluetooth device the app watches), with a quiet ongoing notification,
 * "Watching the piano's power". It runs whenever a plug is paired (`AppGraph.followPowerWatch` starts and stops it);
 * `START_STICKY`, so Android brings it back after it kills the process. Not exported: only the app starts it.
 */
class PowerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, ID, notification(), CONNECTED_DEVICE)
        } catch (e: RuntimeException) {   // refused (no Bluetooth permission yet, or from the background): the watches go on in the process
            Log.w(TAG, "The power watch runs without its foreground service: ${e.javaClass.simpleName}")
            running.value = false
            stopSelf(startId)
            return START_NOT_STICKY
        }
        running.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        running.value = false
        super.onDestroy()
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_piano)
        .setContentTitle(TITLE)
        .setContentIntent(openPiano())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun openPiano(): PendingIntent = PendingIntent.getActivity(
        this,
        ID,
        Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, Route.Piano.path)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "PowerService"
        const val CHANNEL_ID = "power"
        private const val ID = 14

        /** The notification's one line. */
        const val TITLE = "Watching the piano's power"

        /** `connectedDevice` from Android 10, where types began; before it, none is passed. */
        private val CONNECTED_DEVICE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0

        private val running = MutableStateFlow(false)

        /** Whether the service runs in the foreground now (the System pages' "Runs reliably"). */
        val state: StateFlow<Boolean> = running.asStateFlow()

        fun createChannel(context: Context) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Power watch")
                .setDescription("Shows while the app watches the piano's power plug, so Android keeps it running.")
                .setShowBadge(false)
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        /**
         * Starts the watch. Android lets a foreground service start from the background only in some cases (the device owner,
         * no battery limits); otherwise the activity coming to the front starts it. False when Android refused.
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, PowerService::class.java))
            true
        } catch (e: IllegalStateException) {   // ForegroundServiceStartNotAllowedException on Android 12+
            Log.w(TAG, "Android refused to start the power watch from the background")
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PowerService::class.java))
        }
    }
}
