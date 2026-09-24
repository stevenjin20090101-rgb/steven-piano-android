// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ble

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Which runtime permissions reaching the piano needs. API 31+: Bluetooth scan (declared
 * `neverForLocation`) and connect. API 26-30: fine location, and Location Services on.
 * The Piano tab asks for [missing] with a one-line reason.
 */
object BlePermissions {
    @SuppressLint("InlinedApi")   // the API 31 names are only returned for API 31 and up
    fun required(sdkInt: Int): List<String> =
        if (sdkInt >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missing(sdkInt: Int, isGranted: (String) -> Boolean): List<String> = required(sdkInt).filterNot(isGranted)

    fun missing(context: Context): List<String> = missing(Build.VERSION.SDK_INT) {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Before API 31, Android finds Bluetooth LE devices only while Location Services are on. */
    fun needsLocationServices(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.S
}
