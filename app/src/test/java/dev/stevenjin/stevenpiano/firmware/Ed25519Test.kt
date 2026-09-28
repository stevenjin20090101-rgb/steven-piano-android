// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wrapper against RFC 8032 › 7.1's vectors, through both engines: the platform's (here the
 * JDK's, as Android 13's own on the tablet) and EdDSA-Java's (Android 8 to 12).
 */
class Ed25519Test {
    private class Vector(val publicKey: String, val message: String, val signature: String)

    private val vectors = listOf(
        Vector(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ),
        Vector(
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        ),
        Vector(
            "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025",
            "af82",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
        ),
    )

    @Test
    fun `both engines accept RFC 8032's signatures`() {
        for (v in vectors) {
            assertEquals(v.message, true, Ed25519.platform(hex(v.publicKey), hex(v.message), hex(v.signature)))
            assertTrue(v.message, Ed25519.library(hex(v.publicKey), hex(v.message), hex(v.signature)))
        }
    }

    @Test
    fun `both engines refuse a signature with one bit changed, another message or another key`() {
        for (v in vectors) {
            val key = hex(v.publicKey)
            val message = hex(v.message)
            val signature = hex(v.signature)
            for (bit in listOf(0, 7, 255, 256, 511)) {
                val bent = signature.copyOf().also { it[bit / 8] = (it[bit / 8].toInt() xor (1 shl (bit % 8))).toByte() }
                assertEquals("bit $bit", false, Ed25519.platform(key, message, bent))
                assertFalse("bit $bit", Ed25519.library(key, message, bent))
            }
            val other = message + 0x00
            assertEquals(false, Ed25519.platform(key, other, signature))
            assertFalse(Ed25519.library(key, other, signature))
        }
        val wrongKey = hex(vectors[1].publicKey)
        assertFalse(Ed25519.verify(wrongKey, ByteArray(0), hex(vectors[0].signature), platformFirst = false))
        assertFalse(Ed25519.verify(wrongKey, ByteArray(0), hex(vectors[0].signature), platformFirst = true))
    }

    @Test
    fun `a signature whose S is not below the group order is refused by both`() {
        // TEST 1's signature with L added to S: the same point, a second encoding (RFC 8032 › 5.1.7).
        val malleated = hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901554c8c7872aa064e049dbb3013fbf29380d25bf5f0595bbe24655141438e7a101b",
        )
        val key = hex(vectors[0].publicKey)
        assertEquals(false, Ed25519.platform(key, ByteArray(0), malleated))
        assertFalse(Ed25519.library(key, ByteArray(0), malleated))
    }

    @Test
    fun `wrong lengths are refused before anything is asked, and nothing throws`() {
        val v = vectors[0]
        for (platformFirst in listOf(true, false)) {
            assertFalse(Ed25519.verify(hex(v.publicKey).copyOf(31), ByteArray(0), hex(v.signature), platformFirst))
            assertFalse(Ed25519.verify(hex(v.publicKey), ByteArray(0), hex(v.signature).copyOf(63), platformFirst))
            assertFalse(Ed25519.verify(ByteArray(32), ByteArray(0), hex(v.signature), platformFirst))   // not a point
            assertFalse(Ed25519.verify(ByteArray(32) { 0xFF.toByte() }, ByteArray(0), ByteArray(64), platformFirst))
        }
    }

    @Test
    fun `the platform is asked first where it has Ed25519, the library otherwise`() {
        val v = vectors[1]
        assertEquals(Ed25519.Answer(true, Ed25519.Engine.Platform), Ed25519.check(hex(v.publicKey), hex(v.message), hex(v.signature), platformFirst = true))
        assertEquals(Ed25519.Answer(true, Ed25519.Engine.Library), Ed25519.check(hex(v.publicKey), hex(v.message), hex(v.signature), platformFirst = false))
    }

    companion object {
        fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
