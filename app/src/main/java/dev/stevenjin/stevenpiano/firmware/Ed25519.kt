// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.spec.InvalidKeySpecException
import java.security.spec.X509EncodedKeySpec

/**
 * Ed25519 signature checks (RFC 8032), for the piano's firmware releases (BLE_OTA.md › 8, 10).
 *
 * The platform's own provider is asked first from Android 13 on ([check] with `platformFirst`,
 * from `Build.VERSION.SDK_INT >= 33`, as the plan expected Ed25519 there); where it has none, and on
 * older versions, EdDSA-Java answers (`net.i2p.crypto:eddsa` 0.3.0, CC0), linked for exactly this.
 * Measured on Android 14 (API 34, 2026-09-28): Conscrypt offers X25519 (XDH) but no Ed25519
 * KeyFactory or Signature, so there too the library answers; the JDK's Ed25519 answers in unit
 * tests. The library is asked only when the platform cannot check Ed25519 at all, never a second
 * time about a signature the platform refused. Both refuse a signature whose S is not below the
 * group order (RFC 8032 › 5.1.7): the library's own check is added here. Never throws; anything
 * malformed is simply not a valid signature.
 */
object Ed25519 {
    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64

    /** Which implementation answered. */
    enum class Engine { Platform, Library }

    /** Whether [signature] is [publicKey]'s Ed25519 signature of [message]. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray, platformFirst: Boolean): Boolean =
        check(publicKey, message, signature, platformFirst).valid

    /** The answer, and the engine that gave it (the debug log says which). */
    data class Answer(val valid: Boolean, val engine: Engine)

    fun check(publicKey: ByteArray, message: ByteArray, signature: ByteArray, platformFirst: Boolean): Answer {
        if (publicKey.size != PUBLIC_KEY_BYTES || signature.size != SIGNATURE_BYTES) {
            return Answer(false, if (platformFirst) Engine.Platform else Engine.Library)
        }
        if (platformFirst) platform(publicKey, message, signature)?.let { return Answer(it, Engine.Platform) }
        return Answer(library(publicKey, message, signature), Engine.Library)
    }

    /**
     * The platform's Ed25519 (Conscrypt's on Android 13 and newer, the JDK's in unit tests): null when
     * it has none, or cannot take a raw Ed25519 key.
     */
    fun platform(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean? {
        val key: PublicKey = try {
            KeyFactory.getInstance(ALGORITHM).generatePublic(X509EncodedKeySpec(SPKI_PREFIX + publicKey))
        } catch (e: NoSuchAlgorithmException) {
            return null
        } catch (e: InvalidKeySpecException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
        return try {
            val check = Signature.getInstance(ALGORITHM)
            check.initVerify(key)
            check.update(message)
            check.verify(signature)
        } catch (e: NoSuchAlgorithmException) {
            null
        } catch (e: InvalidKeyException) {
            null   // the provider's Signature does not take its own KeyFactory's key: it can't check Ed25519
        } catch (e: SignatureException) {
            false   // a malformed signature
        } catch (e: RuntimeException) {
            false
        }
    }

    /** EdDSA-Java's Ed25519, with the check on S it leaves out. */
    fun library(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (!canonicalS(signature)) return false
        return try {
            val spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519) ?: return false
            val key = EdDSAPublicKey(EdDSAPublicKeySpec(publicKey, spec))
            val engine = EdDSAEngine(MessageDigest.getInstance(spec.hashAlgorithm))
            engine.initVerify(key)
            engine.verifyOneShot(message, signature)
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    /** S, the signature's second half read little-endian, is below the group order L (RFC 8032 › 5.1.7, step 1). */
    private fun canonicalS(signature: ByteArray): Boolean {
        if (signature.size != SIGNATURE_BYTES) return false
        val s = BigInteger(1, signature.copyOfRange(PUBLIC_KEY_BYTES, SIGNATURE_BYTES).reversedArray())
        return s < GROUP_ORDER
    }

    private const val ALGORITHM = "Ed25519"

    /** The DER head of an Ed25519 SubjectPublicKeyInfo (RFC 8410): SEQUENCE { SEQUENCE { OID 1.3.101.112 }, BIT STRING (33) }. */
    private val SPKI_PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    /** L = 2^252 + 27742317777372353535851937790883648493. */
    private val GROUP_ORDER: BigInteger = BigInteger.ONE.shiftLeft(252).add(BigInteger("27742317777372353535851937790883648493"))
}
