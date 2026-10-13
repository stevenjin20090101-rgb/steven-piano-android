// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

import dev.stevenjin.stevenpiano.update.UpdateSource
import org.json.JSONObject

/**
 * BLE_OTA.md › 5's worked example, reproducible anywhere: an image of [SIZE] bytes whose byte i is
 * `(0xE9 + 7·i) & 0xFF`, version [VERSION], signed with RFC 8032 TEST 1's key
 * ([FirmwareKeys.rfc8032Test], a published test vector). The unit tests check the app against it,
 * and the fake update channel of a debug build on an emulator serves it (with § 10's example
 * manifest). Anything signed with that key is a test: the real piano refuses it, and a release
 * build checks nothing against it.
 */
object OtaExample {
    const val SIZE = 991_232
    const val VERSION = "2.1.0"
    const val BUILD = "a1b2c3d"
    const val SHA256 = "6d872b9645d71995871a346d906e64c1b4ea4d92dd7f88cccea75f2792b212c6"
    const val SIG = "J3gBHESXwswpxAhbRmiZDS5Y8YbLk16zxgRBJvCaeZO7OOxPPGPmBjuBkoh0v3ytKS/w9PeTDJNBjoe4yi3mCg=="
    const val BIN_URL = "https://github.com/${UpdateSource.FIRMWARE_REPOSITORY}/releases/download/fw-v2.1.0/firmware-2.1.0.bin"
    const val NOTES = "Softer pianissimo; the pedal lifts on stop."

    /** The version the example's piano runs before the update (§ 13's run 7). */
    const val RUNNING = "2.0.0+a1b2c3d"

    /** The image: byte i is (0xE9 + 7·i) & 0xFF. */
    fun image(size: Int = SIZE): ByteArray = ByteArray(size) { ((0xE9 + 7 * it) and 0xFF).toByte() }

    /** § 10's example manifest (build, minAppVersionCode and notes illustrative), with [edit] applied. */
    fun manifestJson(edit: JSONObject.() -> Unit = {}): String = JSONObject()
        .put("version", VERSION)
        .put("build", BUILD)
        .put("binUrl", BIN_URL)
        .put("sizeBytes", SIZE)
        .put("sha256", SHA256)
        .put("sig", SIG)
        .put("minAppVersionCode", 11)
        .put("notes", NOTES)
        .put("usbOnly", false)
        .apply(edit)
        .toString()
}
