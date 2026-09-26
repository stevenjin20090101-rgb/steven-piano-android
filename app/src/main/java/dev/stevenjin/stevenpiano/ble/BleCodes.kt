// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

/**
 * Bluetooth's numbers and names, made readable for the link's log (`adb logcat -s PianoLink:W`):
 * a GATT status or a scan error with what it usually means, and a name from the air made safe for
 * one log line.
 */
object BleCodes {
    /** Longest advertised name the log quotes; longer ones are cut. */
    const val MAX_LOGGED_NAME = 40

    /** [status] from onConnectionStateChange and friends, with its meaning when it is a common one. */
    fun gattStatus(status: Int): String = when (status) {
        0 -> "0 (success)"
        5 -> "5 (insufficient authentication)"
        8 -> "8 (connection timeout: the piano went out of range or off)"
        19 -> "19 (the piano ended the connection)"
        22 -> "22 (this device ended the connection)"
        34 -> "34 (link-layer response timeout)"
        62 -> "62 (the connection could not be established)"
        133 -> "133 (GATT_ERROR: Android's catch-all)"
        137 -> "137 (authentication failed)"
        else -> "$status"
    }

    /** [code] from ScanCallback.onScanFailed, or the link's own two for a missing scanner or adapter. */
    fun scanFailure(code: Int): String = when (code) {
        1 -> "1 (already started)"
        2 -> "2 (the app could not register the scan)"
        3 -> "3 (internal error)"
        4 -> "4 (feature unsupported)"
        5 -> "5 (out of hardware resources)"
        6 -> "6 (scanning too frequently)"
        PianoScanner.NO_SCANNER -> "$code (no scanner: Bluetooth is off)"
        PianoScanner.NO_ADAPTER -> "$code (no Bluetooth adapter)"
        else -> "$code"
    }

    /**
     * A device's name as the log shows it: quoted, "(no name)" when missing. Names come from the air,
     * so control characters (a line break could forge a log line) become "?" and at most
     * [MAX_LOGGED_NAME] characters are kept.
     */
    fun name(name: String?): String {
        if (name == null) return "(no name)"
        val safe = buildString {
            for (c in name) {
                if (length >= MAX_LOGGED_NAME) {
                    append('…')
                    break
                }
                append(if (c.isISOControl() || c == ' ' || c == ' ') '?' else c)
            }
        }
        return "\"$safe\""
    }
}
