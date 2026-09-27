// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.diag

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import dev.stevenjin.stevenpiano.AppFileProvider
import dev.stevenjin.stevenpiano.BuildConfig
import java.io.File

/** The Android side of diagnostics: the device's facts, the crash reports' folder, and the share sheet. */
object Diagnostics {
    /** Crash reports live here, in the app's private files (never in a backup: the data extraction rules exclude everything). */
    private const val CRASH_DIR = "diagnostics"

    /** This build on this device. */
    fun facts(): DiagnosticsText.Facts = DiagnosticsText.Facts(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        buildType = BuildConfig.BUILD_TYPE,
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        androidRelease = Build.VERSION.RELEASE.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
    )

    /** The crash reports, as the crash handler writes them (it may run before the app's graph exists). */
    fun crashReports(context: Context): CrashReports = CrashReports(
        dir = File(context.filesDir, CRASH_DIR),
        header = { DiagnosticsText.header(facts()) },
        linkTail = { LinkLog.shared.tail(CrashReports.LINK_LINES) },
    )

    /**
     * Opens the system share sheet on [zip] (application/zip), readable by the app the person picks
     * through a one-off grant; false when nothing could take it.
     */
    fun share(context: Context, zip: File): Boolean {
        val uri = try {
            AppFileProvider.uriFor(context, zip)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "Steven Piano diagnostics")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(zip.name, uri)
        val chooser = Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context.activity() == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private tailrec fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }
}
