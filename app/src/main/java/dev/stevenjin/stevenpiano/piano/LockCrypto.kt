// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

import com.google.crypto.tink.subtle.X25519
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The tablet's half of the piano's Bluetooth lock (v1.25 — M58; firmware 2.1.0, `firmware/docs/BLE_LOCK.md` › Protocol):
 * - **the claim:** a fresh X25519 key pair (Tink's pure-Java X25519: Android's own "XDH" can't be relied on down to
 *   minSdk 26); the shared secret with the piano's public key, an all-zero one refused; then
 *   `K = SHA-256("steven-piano-lock-v1" ‖ shared ‖ app public ‖ piano public)`, the piano's own derivation;
 * - **the proof:** `HMAC-SHA256(K, "steven-piano-auth-v1" ‖ nonce)` for the connection's 16-byte nonce.
 * Keys and MACs travel as lowercase hex, 64 digits for 32 bytes. Pure and thread-safe; checked against RFC 7748 and
 * the firmware's own vector (`lock selftest`) in LockCryptoTest.
 */
object LockCrypto {
    /** A key, a public key, a shared secret, K and a MAC: 32 bytes each. */
    const val KEY_BYTES = 32

    /** The piano's `!nonce`: 16 bytes, 32 hex digits. */
    const val NONCE_BYTES = 16

    private val CLAIM_LABEL = "steven-piano-lock-v1".toByteArray(Charsets.US_ASCII)
    private val AUTH_LABEL = "steven-piano-auth-v1".toByteArray(Charsets.US_ASCII)

    /** One claim's key pair: [secret] never leaves the app, [public] goes to the piano. */
    class KeyPair(val secret: ByteArray, val public: ByteArray)

    /** A fresh key pair for one claim (a clamped random scalar, and its public key). */
    fun newKeyPair(): KeyPair {
        val secret = X25519.generatePrivateKey()
        return KeyPair(secret, X25519.publicFromPrivate(secret))
    }

    /** RFC 7748's X25519([scalar], [u]); throws [GeneralSecurityException] for a [u] Tink refuses (a low-order point). */
    fun x25519(scalar: ByteArray, u: ByteArray): ByteArray = X25519.computeSharedSecret(scalar, u)

    /**
     * K from a claim the piano answered with [pianoPublic] (`!claim=`): null for a public key of the wrong size, one Tink
     * refuses, or an all-zero shared secret (the piano refuses those too: `claim refused — bad key`).
     */
    fun claimKey(app: KeyPair, pianoPublic: ByteArray): ByteArray? {
        if (pianoPublic.size != KEY_BYTES) return null
        val shared = try {
            x25519(app.secret, pianoPublic)
        } catch (e: GeneralSecurityException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (allZero(shared)) return null
        return deriveKey(shared, app.public, pianoPublic).also { shared.fill(0) }
    }

    /** `K = SHA-256("steven-piano-lock-v1" ‖ shared ‖ app public ‖ piano public)`: both sides' derivation. */
    fun deriveKey(shared: ByteArray, appPublic: ByteArray, pianoPublic: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(CLAIM_LABEL)
        sha.update(shared)
        sha.update(appPublic)
        sha.update(pianoPublic)
        return sha.digest()
    }

    /** The proof for this connection: HMAC-SHA256([key], "steven-piano-auth-v1" ‖ [nonce]). */
    fun authMac(key: ByteArray, nonce: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        mac.update(AUTH_LABEL)
        mac.update(nonce)
        return mac.doFinal()
    }

    /** Whether every byte is zero, looking at all of them whatever comes first. */
    fun allZero(bytes: ByteArray): Boolean {
        var seen = 0
        for (b in bytes) seen = seen or b.toInt()
        return seen == 0
    }

    /** [bytes] as lowercase hex, as the console carries them. */
    fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            append(DIGITS[(b.toInt() shr 4) and 0xF])
            append(DIGITS[b.toInt() and 0xF])
        }
    }

    /** Exactly [bytes] bytes from [text]'s 2 × [bytes] hex digits (either case), or null for anything else. */
    fun unhex(text: String?, bytes: Int): ByteArray? {
        if (text == null || text.length != bytes * 2) return null
        val out = ByteArray(bytes)
        for (i in 0 until bytes) {
            val high = digit(text[2 * i])
            val low = digit(text[2 * i + 1])
            if (high < 0 || low < 0) return null
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }

    /** An ASCII hex digit's value, or -1 (no other script's digits). */
    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + TEN
        in 'A'..'F' -> c - 'A' + TEN
        else -> -1
    }

    private const val HMAC = "HmacSHA256"
    private const val TEN = 10
    private const val DIGITS = "0123456789abcdef"
}
