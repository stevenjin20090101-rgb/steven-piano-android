// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.update.UpdateState
import java.util.Locale

/** What the updater says: the Piano tab's UPDATE row, the line under Check now, and the download's notification. */
object UpdateCopy {
    /** The UPDATE row's first line for a release on offer. */
    fun available(versionName: String): String = "Steven Piano $versionName is available"

    /** Downloaded and matched, waiting for Android's installer (the person backed out of it, or it could not open). */
    fun ready(versionName: String): String = "Steven Piano $versionName is ready to install"

    /** The device owner's install, handed to Android. */
    fun installing(versionName: String): String = "Installing Steven Piano $versionName…"

    /** Installed silently while this process kept the old code. */
    fun installed(versionName: String): String = "Updated to $versionName; restart to use it"

    /** The progress row: "Downloading 1.4 · 1.2 of 2.3 MB". */
    fun downloading(versionName: String, bytes: Long, total: Long, locale: Locale = Locale.getDefault()): String =
        "Downloading $versionName · ${megabytes(bytes, total, locale)}"

    /** "1.2 of 2.3 MB", in decimal megabytes as Android counts them, one decimal. */
    fun megabytes(bytes: Long, total: Long, locale: Locale = Locale.getDefault()): String =
        "${oneDecimal(bytes, locale)} of ${oneDecimal(total, locale)} MB"

    fun notificationTitle(versionName: String?): String =
        if (versionName == null) "Downloading Steven Piano" else "Downloading Steven Piano $versionName"

    /** On a tablet that is not the device owner, until the person allows this app to install updates (Install unknown apps). */
    const val ALLOW_INSTALLS = "Allow this app to install updates"

    const val UP_TO_DATE = "Steven Piano is up to date."
    const val CHECKING = "Checking for updates…"

    /**
     * The line under Check now: what the last check found. Nothing before the first check, nor
     * while a release is on offer beyond naming it (the UPDATE row above says the rest).
     */
    fun checkLine(state: UpdateState): String? = when (state) {
        UpdateState.Idle -> null
        UpdateState.Checking -> CHECKING
        UpdateState.UpToDate -> UP_TO_DATE
        is UpdateState.Failed -> state.manifest?.let { "Version ${it.versionName} is available." } ?: state.message
        is UpdateState.Installed -> null
        else -> state.manifest?.let { "Version ${it.versionName} is available." }
    }

    private fun oneDecimal(bytes: Long, locale: Locale): String = "%.1f".format(locale, bytes.coerceAtLeast(0L) / 1_000_000.0)
}
