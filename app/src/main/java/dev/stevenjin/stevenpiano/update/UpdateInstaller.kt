// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Hands a downloaded, verified release to Android, which checks its signature against the
 * installed app's (the release key) and refuses anything else.
 *
 * When the app is the tablet's device owner ([isDeviceOwner], set up once with `adb shell dpm
 * set-device-owner`, README › School tablet) the file goes into a [PackageInstaller] session,
 * locked to this app's own package name, committed with no tap needed; the result comes back to
 * [UpdateResultReceiver]. Before the commit the piano is silenced ([silence]): Android stops the
 * running app when it replaces it. Otherwise Android's own installer opens on the file (a
 * FileProvider content URI with a one-off read grant) and asks the person to confirm; Android asks
 * first for "Install unknown apps" if this app does not have it yet ([canRequestInstalls]).
 */
class UpdateInstaller(context: Context, private val silence: suspend () -> Unit) {
    private val app = context.applicationContext

    /** How a hand-over went. */
    enum class Outcome {
        /** Committed silently: the result arrives at [UpdateResultReceiver]. */
        Silent,

        /** Android's installer is showing its confirmation. */
        Prompted,

        /** Android refused the session, or no installer could be opened. */
        Failed,
    }

    /** Whether this app is the device's owner (the school tablet's one-time setup): updates install without a tap. */
    fun isDeviceOwner(): Boolean = try {
        app.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(app.packageName) == true
    } catch (e: RuntimeException) {
        false
    }

    /** Whether Android lets this app open its installer ("Install unknown apps"); the device owner needs no such permission. */
    fun canRequestInstalls(): Boolean = isDeviceOwner() || try {
        app.packageManager.canRequestPackageInstalls()
    } catch (e: RuntimeException) {
        false
    }

    /** The system page where the person allows this app to install updates. */
    fun installPermissionSettings(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))

    /** Hands [file], already verified against [manifest], to Android; [launcher] opens the installer (an activity when there is one). */
    suspend fun install(file: File, manifest: UpdateManifest, launcher: Context = app): Outcome =
        if (isDeviceOwner()) installSilently(file, manifest) else openInstaller(file, launcher)

    private suspend fun installSilently(file: File, manifest: UpdateManifest): Outcome {
        silence()
        return withContext(Dispatchers.IO) {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)   // the session installs this app and no other
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            var sessionId = -1
            try {
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite(SESSION_FILE, 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out, BUFFER_BYTES) }
                        session.fsync(out)
                    }
                    session.commit(UpdateResultReceiver.statusSender(app, manifest))
                }
                Log.w(TAG, "Update ${manifest.versionName} handed to the package installer (device owner)")
                Outcome.Silent
            } catch (e: IOException) {
                abandon(installer, sessionId, e)
            } catch (e: RuntimeException) {   // SecurityException, IllegalStateException, IllegalArgumentException
                abandon(installer, sessionId, e)
            }
        }
    }

    private fun abandon(installer: PackageInstaller, sessionId: Int, e: Exception): Outcome {
        Log.w(TAG, "The update session failed: ${e.javaClass.simpleName}")
        if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
        return Outcome.Failed
    }

    private fun openInstaller(file: File, launcher: Context): Outcome {
        val uri = try {
            FileProvider.getUriForFile(app, authority(app), file)
        } catch (e: IllegalArgumentException) {
            return Outcome.Failed
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            launcher.startActivity(intent)
            Outcome.Prompted
        } catch (e: ActivityNotFoundException) {
            Outcome.Failed
        } catch (e: SecurityException) {
            Outcome.Failed
        }
    }

    /**
     * Restart after a silent update (ProcessPhoenix-style): [beforeExit] (the piano is silenced),
     * then the launcher's activity starts in a fresh task and this process ends, so the next one
     * runs the new code.
     */
    fun restart(from: Context, beforeExit: () -> Unit) {
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) ?: return
        runCatching(beforeExit)
        from.startActivity(launch)
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val TAG = "Updates"
        private const val SESSION_FILE = "steven-piano.apk"
        private const val BUFFER_BYTES = 64 * 1024
        const val APK_TYPE = "application/vnd.android.package-archive"

        /** The app's FileProvider: `cacheDir/updates/` (the downloads) and `cacheDir/diagnostics/` (the share), nothing else. */
        fun authority(context: Context): String = "${context.packageName}.files"
    }
}
