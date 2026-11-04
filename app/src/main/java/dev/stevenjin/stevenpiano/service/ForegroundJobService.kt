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
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

/** The service's type from Android 10, where types began; before it, none is passed. */
private val DATA_SYNC = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

/**
 * What the job services (Artwork, Firmware, Import, Library, Studio, Update) have in common: their scope, going into the
 * foreground as their notification [notificationId] with their [foregroundType], going out of it, and the time Android
 * gives them running out. What each does when Android refuses the foreground, its notification and its channel's words
 * stay its own.
 */
abstract class ForegroundJobService(private val notificationId: Int, private val foregroundType: Int = DATA_SYNC) : Service() {
    protected val scope = MainScope()

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 14's limit for short services; not expected for dataSync, handled the same way. */
    override fun onTimeout(startId: Int) = stopForTimeout()

    override fun onTimeout(startId: Int, fgsType: Int) = stopForTimeout()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Android's time for this service's type ran out. */
    protected abstract fun stopForTimeout()

    /** Throws what Android throws when it refuses the foreground. */
    protected fun startInForeground(notification: Notification) {
        ServiceCompat.startForeground(this, notificationId, notification, foregroundType)
    }

    /** Out of the foreground, the notification gone. */
    protected fun leaveForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    /** Out of the foreground, and the service stops. */
    protected fun finish() {
        leaveForeground()
        stopSelf()
    }
}

/** A job service's notification channel [id]: low importance, no badge. */
internal fun createJobChannel(context: Context, id: String, name: String, description: String) {
    val channel = NotificationChannelCompat.Builder(id, NotificationManagerCompat.IMPORTANCE_LOW)
        .setName(name)
        .setDescription(description)
        .setShowBadge(false)
        .build()
    NotificationManagerCompat.from(context).createNotificationChannel(channel)
}
