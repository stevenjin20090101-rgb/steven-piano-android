// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.IntentCompat
import dev.stevenjin.stevenpiano.BuildConfig
import dev.stevenjin.stevenpiano.graph

/**
 * Where Android's package installer reports a silent update (the device-owner path of
 * [UpdateInstaller]). Not exported: only this app's own PendingIntent reaches it. Its name must
 * stay the same in every release, because the old version's PendingIntent names the new
 * version's receiver.
 *
 * - Success heard by the old code (the process outlived the install): [UpdateState.Installed],
 *   whose Restart runs the new code.
 * - Success heard by the new code (Android replaced the running app, then started it again for
 *   this broadcast): the update is live. The download is cleared and, as the device owner may,
 *   the app opens again by itself where the person left it.
 * - Anything else: [UpdateFailures.NOT_INSTALLED] under the UPDATE row, the release still on offer.
 */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.graph
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, -1)
        val versionName = intent.getStringExtra(EXTRA_VERSION_NAME).orEmpty()
        val checker = graph.updateChecker
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> if (BuildConfig.VERSION_CODE >= versionCode) {
                Log.w(TAG, "Updated to $versionName; this is the new version")
                graph.updateDownloader.clear()
                checker.publish(UpdateState.UpToDate)
                if (graph.updateInstaller.isDeviceOwner()) reopen(context)
            } else {
                Log.w(TAG, "Updated to $versionName; restart to run it")
                checker.publish(UpdateState.Installed(versionName))
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Not expected for the device owner; if Android asks anyway, its confirmation is shown.
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                val shown = confirm != null && runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                if (!shown) fail(context, "user action needed, and the confirmation could not be shown")
            }
            else -> fail(context, "status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }

    private fun fail(context: Context, why: String) {
        Log.w(TAG, "The update was not installed ($why)")
        val checker = context.graph.updateChecker
        checker.publish(UpdateState.Failed(UpdateFailures.NOT_INSTALLED, checker.state.value.manifest))
    }

    /** The device owner may start an activity from the background: the app comes back after replacing itself. */
    private fun reopen(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        runCatching { context.startActivity(launch) }.onFailure { Log.w(TAG, "Couldn't reopen after the update: ${it.javaClass.simpleName}") }
    }

    companion object {
        private const val TAG = "Updates"
        private const val EXTRA_VERSION_CODE = "dev.stevenjin.stevenpiano.extra.UPDATE_VERSION_CODE"
        private const val EXTRA_VERSION_NAME = "dev.stevenjin.stevenpiano.extra.UPDATE_VERSION_NAME"
        private const val REQUEST_CODE = 8

        /**
         * The status callback for a session: an explicit broadcast to this receiver. Mutable on API
         * 31+ because the installer adds its status extras (EXTRA_STATUS and the rest) when it
         * sends it; explicit, so nothing else can be made to receive it.
         */
        fun statusSender(context: Context, manifest: UpdateManifest): IntentSender {
            val intent = Intent(context, UpdateResultReceiver::class.java)
                .setPackage(context.packageName)
                .putExtra(EXTRA_VERSION_CODE, manifest.versionCode)
                .putExtra(EXTRA_VERSION_NAME, manifest.versionName)
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable).intentSender
        }
    }
}
