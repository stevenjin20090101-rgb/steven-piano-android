// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

import dev.stevenjin.stevenpiano.Provenance
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * The key embedded for firmware releases is the author's, the one the whole project is signed
 * with: its fingerprint is `Provenance`'s, its bytes are this repository's public key file's, and,
 * once the firmware carries `include/ota_pubkey.h`, the piano's.
 */
class PinnedKeyTest {
    @Test
    fun `the embedded key's fingerprint is the project's`() {
        assertEquals("eab16a502f679465", FirmwareKeys.fingerprint(FirmwareKeys.author))
        assertEquals(FirmwareKeys.AUTHOR_FINGERPRINT, FirmwareKeys.fingerprint(FirmwareKeys.author))
        assertTrue("Provenance names the same fingerprint", Provenance.TAG.endsWith("fp ${FirmwareKeys.AUTHOR_FINGERPRINT}"))
        assertTrue(Provenance.text.endsWith(FirmwareKeys.AUTHOR_FINGERPRINT))
    }

    @Test
    fun `the embedded key is the repository's public key file, byte for byte`() {
        val pem = upwards("provenance/author_ed25519_public.pem")
        assumeTrue("provenance/author_ed25519_public.pem not found above the test's folder", pem != null)
        val der = Base64.getDecoder().decode(pem!!.readLines().filterNot { it.startsWith("-----") }.joinToString(""))
        assertEquals("an Ed25519 SubjectPublicKeyInfo is 44 bytes", 44, der.size)
        assertArrayEquals(der.copyOfRange(12, 44), FirmwareKeys.author)
    }

    @Test
    fun `the piano embeds the same key, once its firmware carries it`() {
        val header = upwards("firmware/include/ota_pubkey.h")
        assumeTrue("firmware/include/ota_pubkey.h is not there yet (firmware 2.0.0 adds it)", header != null)
        val body = Regex("""kOtaPublicKey\s*\[\s*32\s*]\s*=\s*\{([^}]*)}""").find(header!!.readText())?.groupValues?.get(1).orEmpty()
        val embedded = Regex("0x([0-9a-fA-F]{2})").findAll(body).map { it.groupValues[1].toInt(16).toByte() }.toList().toByteArray()
        assertArrayEquals(FirmwareKeys.author, embedded)
    }

    @Test
    fun `the test key is never the author's, and the accessors hand out copies`() {
        assertNotEquals(FirmwareKeys.fingerprint(FirmwareKeys.author), FirmwareKeys.fingerprint(FirmwareKeys.rfc8032Test))
        FirmwareKeys.author[0] = 0
        assertEquals("eab16a502f679465", FirmwareKeys.fingerprint(FirmwareKeys.author))
    }

    private fun upwards(path: String): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
