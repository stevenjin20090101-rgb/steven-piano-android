// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.diag.AppReading

/**
 * What keeps the app running on the school tablet (v1.39 — M77; DESIGN.md › v1.39 — M77): the app is the [deviceOwner]
 * (Settings can't force-stop it, and kiosk mode can lock the tablet), Android lets it run without battery limits
 * ([batteryUnrestricted]), and the power watch's service runs while a plug is paired ([powerWatch]; null with none paired,
 * nothing to watch). [ok] when all are there.
 */
data class Reliability(val deviceOwner: Boolean, val batteryUnrestricted: Boolean, val powerWatch: Boolean?) {
    val ok: Boolean get() = deviceOwner && batteryUnrestricted && powerWatch != false

    companion object {
        /** From the app's reading; null until it says whether the app is the device owner and its battery limits. */
        fun of(app: AppReading?): Reliability? {
            val owner = app?.deviceOwner ?: return null
            val battery = app.batteryUnrestricted ?: return null
            return Reliability(owner, battery, app.powerWatch)
        }
    }
}

/** The System pages' "Runs reliably" (v1.39 — M77), with its battery rows: the words, one rule for the tablet and the panel. */
object ReliableCopy {
    const val LABEL = "Runs reliably"

    /** "Yes" or "No", then each part: "No · not device owner · battery restricted · watching the plug". */
    fun value(r: Reliability): String = listOfNotNull(
        if (r.ok) "Yes" else "No",
        if (r.deviceOwner) "device owner" else "not device owner",
        if (r.batteryUnrestricted) "battery unrestricted" else "battery restricted",
        when (r.powerWatch) {
            true -> "watching the plug"
            false -> "not watching the plug"
            null -> null
        },
    ).joinToString(" · ")

    /** The fix for the first part missing, in one line; null when nothing is. */
    fun fix(r: Reliability): String? = when {
        !r.deviceOwner -> FIX_OWNER
        !r.batteryUnrestricted -> FIX_BATTERY
        r.powerWatch == false -> FIX_WATCH
        else -> null
    }

    const val FIX_OWNER = "Make the app the device owner: Piano › Kiosk shows how."
    const val FIX_BATTERY = "Let it run without battery limits: Allow, below."
    const val FIX_WATCH = "Open the app once: the power watch starts with it."

    /** The battery row's button: Android's own dialog. */
    const val ALLOW = "Allow"

    /** The battery row's note, as Android says now. */
    fun batteryNote(unrestricted: Boolean?): String = when (unrestricted) {
        true -> "Battery: unrestricted. Android lets the app run in the background."
        false -> "Battery: restricted. Android may stop the app in the background."
        null -> "Battery: reading…"
    }

    /** The app's page in Android's settings, for Lenovo's own switch. */
    const val APP_SETTINGS = "App settings"
    const val BACKGROUND_NOTE = "On a Lenovo tablet, also turn on Battery › Allow background activity there."
}
