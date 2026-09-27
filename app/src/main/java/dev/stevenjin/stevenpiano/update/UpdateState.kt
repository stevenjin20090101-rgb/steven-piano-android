// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import java.io.File

/**
 * Where the updater is. The Piano tab's UPDATE row shows whenever a state carries a [manifest]
 * (a newer release is known) or the update is [Installed]; "Check now" says the rest.
 */
sealed interface UpdateState {
    /** The release this state is about, when a newer one is known. */
    val manifest: UpdateManifest? get() = null

    /** A download or an install is under way, or done and waiting for Restart: a check never replaces it. */
    val busy: Boolean get() = this is Downloading || this is ReadyToInstall || this is Installing || (this is Installed && restartNeeded)

    /** Nothing asked yet in this process. */
    data object Idle : UpdateState

    /** Asking the server, with no newer release known yet. */
    data object Checking : UpdateState

    /** The server's release is this one, or older. */
    data object UpToDate : UpdateState

    data class Available(override val manifest: UpdateManifest) : UpdateState

    /** [bytes] of [total] downloaded. */
    data class Downloading(override val manifest: UpdateManifest, val bytes: Long, val total: Long) : UpdateState

    /** Downloaded, and its SHA-256 matched the manifest's: Android's installer comes next. */
    data class ReadyToInstall(override val manifest: UpdateManifest, val file: File) : UpdateState

    /** Handed to Android's package installer with no tap needed (the app is the device owner). */
    data class Installing(override val manifest: UpdateManifest) : UpdateState

    /**
     * Installed silently. [restartNeeded] while this process still runs the old code (Restart runs
     * the new one); false when Android replaced the running app and this is the new version, opened
     * again by itself (what Android 14 does: it stops an app it replaces).
     */
    data class Installed(val version: String, val restartNeeded: Boolean = true) : UpdateState

    /** [message] is the one line the Piano tab shows; [manifest] is the release still on offer, if one is known. */
    data class Failed(val message: String, override val manifest: UpdateManifest? = null) : UpdateState
}

/** A failure the person is told about in [message], one line, in words. */
class UpdateFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What the updater says when something goes wrong (DESIGN.md › v1.4 › Updates: one line, in words, no red). */
object UpdateFailures {
    /** The manifest or the file could not be fetched: no network path, a server error, the repository still private (404). */
    const val UNREACHABLE = "Couldn't reach the update server."

    /** The file's size or SHA-256 differs from the manifest's: it was deleted before anything else. */
    const val MISMATCH = "The download didn't match the release; try again."

    /** The connection dropped part-way through the file. */
    const val STOPPED = "The download stopped; try again."

    /** Check now while the device is offline. */
    const val OFFLINE = "Checking for updates needs an internet connection."

    /** The manifest is not one, or a field of it is wrong (a publishing mistake). */
    const val UNREADABLE = "The update information couldn't be read."

    /** Not enough free storage for the file. */
    const val NO_ROOM = "There isn't enough free space for the update."

    /** Android's package installer refused the file or could not be opened. */
    const val NOT_INSTALLED = "The update couldn't be installed."

    /** The release needs a newer Android than this device has. */
    fun needsNewerAndroid(versionName: String): String = "Steven Piano $versionName needs a newer version of Android."
}
