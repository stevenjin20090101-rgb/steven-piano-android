// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.piano

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tablet's half of the piano's Bluetooth lock (v1.25 — M58): X25519 through the app's wrapper against RFC 7748
 * (§ 5.2's two vectors and its iterations, § 6.1's exchange), and the claim and the proof against the firmware's own
 * vector (`lock selftest`, `tools/lock-check/check.py`: § 6.1's keys with the app as Alice and the piano as Bob).
 */
class LockCryptoTest {
    private fun bytes(hex: String): ByteArray = LockCrypto.unhex(hex, hex.length / 2)!!

    private fun x25519(scalar: String, u: String): String = LockCrypto.hex(LockCrypto.x25519(bytes(scalar), bytes(u)))

    @Test
    fun `RFC 7748 5_2 - the two test vectors`() {
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            x25519("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4", "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
        )
        assertEquals(
            "the u-coordinate's top bit is masked",
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            x25519("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d", "e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"),
        )
    }

    @Test
    fun `RFC 7748 5_2 - after one iteration and after 1,000`() {
        var k = bytes("0900000000000000000000000000000000000000000000000000000000000000")
        var u = k.copyOf()
        for (i in 1..1_000) {
            val next = LockCrypto.x25519(k, u)
            u = k
            k = next
            if (i == 1) assertEquals("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079", LockCrypto.hex(k))
        }
        assertEquals("684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51", LockCrypto.hex(k))
    }

    @Test
    fun `RFC 7748 6_1 - Alice and Bob reach the same secret`() {
        val nine = "0900000000000000000000000000000000000000000000000000000000000000"
        assertEquals(ALICE_PUBLIC, x25519(ALICE_SECRET, nine))
        assertEquals(BOB_PUBLIC, x25519(BOB_SECRET, nine))
        assertEquals(SHARED, x25519(ALICE_SECRET, BOB_PUBLIC))
        assertEquals(SHARED, x25519(BOB_SECRET, ALICE_PUBLIC))
    }

    @Test
    fun `the firmware's vector - the app as Alice derives K, and proves it over the nonce 00 to 0f`() {
        val app = LockCrypto.KeyPair(bytes(ALICE_SECRET), bytes(ALICE_PUBLIC))
        val key = LockCrypto.claimKey(app, bytes(BOB_PUBLIC))!!
        assertEquals("44ce429a81459d2c24076dd06e1e652e55561440eca70d806ea03ca80ee6dab9", LockCrypto.hex(key))
        assertArrayEquals("the piano's side, the same K", key, LockCrypto.deriveKey(bytes(SHARED), bytes(ALICE_PUBLIC), bytes(BOB_PUBLIC)))
        val nonce = ByteArray(LockCrypto.NONCE_BYTES) { it.toByte() }
        assertEquals("e25c89e2d232a4e45066cd17c84cc28e35751430a984ee99ef5efe912afa2036", LockCrypto.hex(LockCrypto.authMac(key, nonce)))
        val other = nonce.copyOf().also { it[15] = 0x10 }
        assertNotEquals("another nonce, another proof", LockCrypto.hex(LockCrypto.authMac(key, nonce)), LockCrypto.hex(LockCrypto.authMac(key, other)))
    }

    @Test
    fun `a fresh key pair claims a fresh piano, both sides agreeing`() {
        val app = LockCrypto.newKeyPair()
        val piano = LockCrypto.newKeyPair()
        assertEquals(LockCrypto.KEY_BYTES, app.public.size)
        assertArrayEquals(LockCrypto.x25519(app.secret, bytes("09" + "00".repeat(31))), app.public)
        val appSide = LockCrypto.claimKey(app, piano.public)!!
        val pianoSide = LockCrypto.deriveKey(LockCrypto.x25519(piano.secret, app.public), app.public, piano.public)
        assertArrayEquals(pianoSide, appSide)
        assertFalse("two claims never share a key", LockCrypto.newKeyPair().public.contentEquals(app.public))
    }

    @Test
    fun `an all-zero shared secret is refused - low-order and wrong-sized piano keys give no K`() {
        val app = LockCrypto.newKeyPair()
        for (point in LOW_ORDER) assertNull(point, LockCrypto.claimKey(app, bytes(point)))
        assertNull(LockCrypto.claimKey(app, ByteArray(31)))
        assertTrue(LockCrypto.allZero(ByteArray(32)))
        assertFalse(LockCrypto.allZero(ByteArray(32).also { it[31] = 1 }))
    }

    @Test
    fun `hex is lowercase out, and strict in`() {
        assertEquals("00ff7f80", LockCrypto.hex(byteArrayOf(0, -1, 0x7F, -128)))
        assertArrayEquals(byteArrayOf(-85, -51), LockCrypto.unhex("ABcd", 2))
        for (bad in listOf("abc", "abcdef", "abcg", "ab d", "ab٣٤", "")) assertNull(bad, LockCrypto.unhex(bad, 2))
        assertNull(LockCrypto.unhex(null, 2))
    }

    private companion object {
        const val ALICE_SECRET = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
        const val ALICE_PUBLIC = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        const val BOB_SECRET = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
        const val BOB_PUBLIC = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
        const val SHARED = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

        /** Points whose X25519 result is all zero (the firmware's list, tools/lock-check): the piano refuses them as bad keys. */
        val LOW_ORDER = listOf(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
        )
    }
}
