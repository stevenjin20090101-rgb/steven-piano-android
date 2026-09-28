// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

import java.security.MessageDigest

/**
 * The keys a piano firmware release is checked against. Embedded, never fetched: a release
 * whose signature does not check out against [author] is not offered, and the piano refuses it too
 * (it holds the same 32 bytes as `kOtaPublicKey`, BLE_OTA.md › 8).
 */
object FirmwareKeys {
    /**
     * Steven Jin's authorship key, `provenance/author_ed25519_public.pem` in both repositories, as
     * its 32 raw bytes (BLE_OTA.md › 8). Its [fingerprint] is `eab16a502f679465`, as PROVENANCE.md
     * and `Provenance` state.
     */
    val author: ByteArray get() = AUTHOR.copyOf()

    /**
     * RFC 8032 › 7.1, TEST 1's public key: a published test vector (its secret key is in the RFC),
     * so anything signed with it is a test. BLE_OTA.md › 5's worked example is signed with it. Only
     * the fake update channel of a debug build on an emulator ever checks against it.
     */
    val rfc8032Test: ByteArray get() = RFC8032_TEST_1.copyOf()

    /** The first 16 hex digits of the SHA-256 of [key]'s 32 bytes, as PROVENANCE.md prints fingerprints. */
    fun fingerprint(key: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(FINGERPRINT_DIGITS)

    /** The fingerprint [author] has, the one the whole project carries. */
    const val AUTHOR_FINGERPRINT = "eab16a502f679465"

    private const val FINGERPRINT_DIGITS = 16

    private val AUTHOR = bytes(
        0x94, 0xa0, 0xfc, 0x32, 0x52, 0x03, 0xab, 0x51, 0xdc, 0x28, 0xfb, 0xff, 0xc6, 0xc9, 0xce, 0x0f,
        0x9c, 0xda, 0x8f, 0x37, 0x60, 0xdc, 0xc0, 0x07, 0xb5, 0x6a, 0x4b, 0x65, 0xf8, 0x8f, 0x03, 0x19,
    )

    private val RFC8032_TEST_1 = bytes(
        0xd7, 0x5a, 0x98, 0x01, 0x82, 0xb1, 0x0a, 0xb7, 0xd5, 0x4b, 0xfe, 0xd3, 0xc9, 0x64, 0x07, 0x3a,
        0x0e, 0xe1, 0x72, 0xf3, 0xda, 0xa6, 0x23, 0x25, 0xaf, 0x02, 0x1a, 0x68, 0xf7, 0x07, 0x51, 0x1a,
    )

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
