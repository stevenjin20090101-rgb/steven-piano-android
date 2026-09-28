// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.firmware.FirmwareState
import dev.stevenjin.stevenpiano.firmware.FirmwareVersion
import java.util.Locale

/** What the Firmware page, the hub's row and the update's notification say (DESIGN.md › v1.6.1 — M21). */
object FirmwareCopy {
    const val CHECK = "Check for piano updates"
    const val CHECKING = "Checking for piano updates…"
    const val UP_TO_DATE = "The piano's firmware is up to date."

    /** The version row while connected to firmware older than 2.0.0 (no Device Information, no update service). */
    const val NO_VERSION = "Unknown — this firmware has no version. Flash 2.0.0 over USB once."

    /** The version row while not connected. */
    const val UNKNOWN = "Unknown"

    /** Under the Update button: what happens to the piano meanwhile. */
    const val QUIET = "The piano goes quiet for about two minutes. Keep the tablet near it."

    /** Under the Update button while no piano that can take it is connected. */
    const val CONNECT_FIRST = "Connect to the piano to update it."
    const val VERIFYING_DOWNLOAD = "Checking the download…"
    const val PIANO_VERIFYING = "The piano is checking the update…"
    const val RESTARTING = "Restarting the piano…"
    const val USB_ONLY = "This update needs a USB flash"
    const val NEEDS_NEWER_APP = "Needs a newer app"
    const val NOTIFICATION_TITLE = "Updating the piano"

    /** The hub's row while a newer release is on offer. */
    const val HUB_AVAILABLE = "Update available"

    /** The hub's row while a transfer runs. */
    const val HUB_UPDATING = "Updating…"

    /** "2.0.0 · a1b2c3d" for the piano's "2.0.0+a1b2c3d"; a version that isn't semver as it came. */
    fun version(reported: String): String = FirmwareVersion.parse(reported)?.shown ?: reported

    /** The heading of a release on offer: "Piano firmware 2.1.0 is available". */
    fun available(version: String): String = "Piano firmware $version is available"

    /** The filled button: "Update the piano to 2.1.0". */
    fun update(version: String): String = "Update the piano to $version"

    /** "Downloading · 0.9 MB": decimal megabytes, one decimal, in the locale's form. */
    fun downloading(bytes: Long, locale: Locale = Locale.getDefault()): String =
        "Downloading · ${"%.1f".format(locale, bytes.coerceAtLeast(0L) / 1_000_000.0)} MB"

    /** "Sending · 38% · about 1 min left"; the time only once a rate is known. */
    fun sending(bytes: Long, total: Long, ratePerSec: Long): String {
        val pct = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
        val left = timeLeft(total - bytes, ratePerSec)
        return "Sending · ${Format.percent(pct)}" + (left?.let { " · $it" } ?: "")
    }

    /** "about 2 min left" (to the nearest minute), "less than a minute left", or null before the rate is known. */
    fun timeLeft(bytesLeft: Long, ratePerSec: Long): String? {
        if (ratePerSec <= 0) return null
        val seconds = (bytesLeft.coerceAtLeast(0L) + ratePerSec - 1) / ratePerSec
        return if (seconds < 60) "less than a minute left" else "about ${(seconds + 30) / 60} min left"
    }

    /** "Updated to 2.1.0", or "Updated to 2.1.0 · confirming…" until the piano's self-test confirms it. */
    fun done(version: String, confirming: Boolean): String = if (confirming) "Updated to $version · confirming…" else "Updated to $version"

    /**
     * The line under Check for piano updates: what the last check found. Nothing before the first,
     * nor during a transfer (the lines below say it).
     */
    fun checkLine(state: FirmwareState): String? = when (state) {
        FirmwareState.Idle -> null
        FirmwareState.Checking -> CHECKING
        is FirmwareState.UpToDate -> UP_TO_DATE
        is FirmwareState.Failed -> if (state.check) state.message else null
        is FirmwareState.Available, is FirmwareState.UsbOnly, is FirmwareState.NeedsNewerApp -> "Version ${state.manifest?.version} is available."
        else -> null
    }

    /** The update's notification line for a transfer under way; null once it is over. */
    fun progress(state: FirmwareState): String? = when (state) {
        is FirmwareState.Downloading -> downloading(state.bytes)
        is FirmwareState.Verifying -> VERIFYING_DOWNLOAD
        is FirmwareState.Sending -> sending(state.bytes, state.total, state.ratePerSec)
        is FirmwareState.PianoVerifying -> PIANO_VERIFYING
        is FirmwareState.Restarting -> RESTARTING
        else -> null
    }

    /** How far a transfer is, 0..1, or null when it can't be said (checking, restarting). */
    fun fraction(state: FirmwareState): Float? = when (state) {
        is FirmwareState.Downloading -> if (state.total > 0) state.bytes.toFloat() / state.total else null
        is FirmwareState.Sending -> if (state.total > 0) state.bytes.toFloat() / state.total else null
        else -> null
    }
}
