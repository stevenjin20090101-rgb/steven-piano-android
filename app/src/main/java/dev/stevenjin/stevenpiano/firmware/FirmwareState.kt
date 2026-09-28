// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

/**
 * Where the piano's firmware update is (plan M21): `Idle → Checking → UpToDate | Available →
 * Downloading → Verifying → Sending → PianoVerifying → Restarting → Done | Failed`, with the two
 * releases this app can't send ([UsbOnly], [NeedsNewerApp]). The Firmware page shows it; the hub's
 * row reads "Update available" while one is [offered].
 */
sealed interface FirmwareState {
    /** The release this state is about, when one is known. */
    val manifest: FirmwareManifest? get() = null

    /**
     * A transfer is under way, from its download to the piano's return: no check replaces it, the
     * player stays locked, and the foreground service keeps the app going.
     */
    val busy: Boolean
        get() = this is Downloading || this is Verifying || this is Sending || this is PianoVerifying || this is Restarting

    /** Cancel still stops it: before END (the point of no return). */
    val cancellable: Boolean get() = this is Downloading || this is Verifying || this is Sending

    /** A newer release than the piano's is known and not installed (still on offer after a failure Retry may mend). */
    val offered: Boolean
        get() = this is Available || this is UsbOnly || this is NeedsNewerApp || (this is Failed && manifest != null && (retryable || check))

    /** Nothing asked yet in this process. */
    data object Idle : FirmwareState

    /** Asking GitHub, with no release known yet. */
    data object Checking : FirmwareState

    /** The piano runs [version] (the release, "2.1.0"), the latest or newer. */
    data class UpToDate(val version: String) : FirmwareState

    /** A newer release the app can send. */
    data class Available(override val manifest: FirmwareManifest) : FirmwareState

    /** A newer release that changes what a Bluetooth update can't carry: USB only (BLE_OTA.md › 10). */
    data class UsbOnly(override val manifest: FirmwareManifest) : FirmwareState

    /** A newer release that asks for a newer app than this one ([FirmwareManifest.minAppVersionCode]). */
    data class NeedsNewerApp(override val manifest: FirmwareManifest) : FirmwareState

    /** [bytes] of [total] downloaded. */
    data class Downloading(override val manifest: FirmwareManifest, val bytes: Long, val total: Long) : FirmwareState

    /** The download's SHA-256 and signature being checked. */
    data class Verifying(override val manifest: FirmwareManifest) : FirmwareState

    /** [bytes] of [total] taken by the piano, at [ratePerSec] bytes a second (0 until the first window). */
    data class Sending(override val manifest: FirmwareManifest, val bytes: Long, val total: Long, val ratePerSec: Long) : FirmwareState

    /** END sent: the piano is checking the image. */
    data class PianoVerifying(override val manifest: FirmwareManifest) : FirmwareState

    /** The piano said OK and restarts; the app waits for it to come back (a minute at most). */
    data class Restarting(override val manifest: FirmwareManifest) : FirmwareState

    /**
     * The piano came back running [version] ("2.1.0"); [confirming] while its self-test hasn't
     * confirmed the new image yet (`!ota=pending`, BLE_OTA.md › 9).
     */
    data class Done(val version: String, val confirming: Boolean = false) : FirmwareState

    /**
     * [message] is the one line the page shows, [hint] a second one when there is something to
     * do; [retryable]: Retry is offered. [check]: a check failed (the line goes under Check for
     * piano updates, and a release already known stays on offer). [rolledBack]: the piano came back
     * on its old firmware.
     */
    data class Failed(
        val message: String,
        val retryable: Boolean,
        override val manifest: FirmwareManifest? = null,
        val check: Boolean = false,
        val hint: String? = null,
        val rolledBack: Boolean = false,
    ) : FirmwareState
}

/**
 * The piano the updater sees: not connected, or connected with the version it reports ([text],
 * "2.0.0+a1b2c3d", and [version] parsed) and whether it has the update service ([updatable]).
 * Firmware older than 2.0.0 has neither a version nor the service.
 */
sealed interface FirmwarePiano {
    data object NotConnected : FirmwarePiano

    data class Connected(val text: String?, val version: FirmwareVersion?, val updatable: Boolean) : FirmwarePiano {
        /** Firmware older than 2.0.0: no version, no update service; it needs the one USB flash. */
        val tooOld: Boolean get() = text == null && !updatable
    }
}

/** The tablet's battery as the updater weighs it: [percent] 0-100 (null when unknown), and whether it is [charging]. */
data class PowerState(val percent: Int?, val charging: Boolean)

/**
 * What the updater needs of the player (`Player` in the app; a fake in tests): nothing plays from
 * [lock] to [unlock], and [stopForUpdate] stops playback and waits until the stop sequence
 * (CC64 = 0, CC123) has been written to the piano.
 */
interface FirmwarePlayer {
    fun lock(reason: String)

    fun unlock()

    /** True when the stop sequence was written within [timeoutMs]. */
    suspend fun stopForUpdate(timeoutMs: Long): Boolean
}

/** What the updater says, in the app's words (DESIGN.md › v1.6 — M21): one line, no red. */
object FirmwareFailures {
    /** Any failure once the piano was asked (an ERR, a lost link, a silence): the old slot is untouched. */
    const val DIDNT_FINISH = "The update didn't finish. The piano kept its old firmware."

    const val NOT_CONNECTED = "Connect to the piano first."

    /** The piano's firmware has no update service (older than 2.0.0). */
    const val NOT_UPDATABLE = "This piano's firmware can't be updated from the app yet. Flash 2.0.0 over USB once."

    const val OFFLINE = "Checking for piano updates needs an internet connection."
    const val UNREACHABLE = "Couldn't reach the update server."
    const val UNREADABLE = "The piano update information couldn't be read."

    /** The manifest's signature does not check out against the embedded key: never offered. */
    const val UNSIGNED = "The piano update isn't signed by Steven Jin, so it isn't offered."

    /** The download's size, hash or signature differs from the release's. */
    const val MISMATCH = "The download didn't match the release; try again."
    const val STOPPED = "The download stopped; try again."
    const val LOW_BATTERY = "Charge the tablet to 20% or plug it in, then try again."

    /** OK came, but the piano did not come back within the minute. */
    const val DIDNT_COME_BACK = "The piano hasn't come back after restarting. Check that it's on; the app will look for it again."

    /** ERR 10: crash-loop safe mode is on for this boot. */
    const val SAFE_MODE_HINT = "The piano is in its safe mode. Switch it off and on, then try again."

    /** ERR 6 or 8: the piano refused this release itself. */
    const val REFUSED_HINT = "The piano refused this release."

    /** The piano came back on its old firmware: the new one did not confirm itself. */
    fun rolledBack(reported: String): String = "The piano restarted but reports $reported — it rolled back."
}
