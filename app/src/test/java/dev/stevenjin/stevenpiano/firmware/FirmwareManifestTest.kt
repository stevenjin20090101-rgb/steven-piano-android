// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.firmware

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

/**
 * The firmware's release manifest (BLE_OTA.md › 10): read field by field, and trusted only when its
 * signature over the SHA-256 checks out against the key the app embeds.
 */
class FirmwareManifestTest {
    @Test
    fun `the spec's example reads as written`() {
        val manifest = FirmwareManifest.parse(OtaExample.manifestJson())
        assertEquals("2.1.0", manifest.version)
        assertEquals("a1b2c3d", manifest.build)
        assertEquals(
            "https://github.com/stevenjin20090101-rgb/Steven-Jin-Player-Piano/releases/download/fw-v2.1.0/firmware-2.1.0.bin",
            manifest.binUrl,
        )
        assertEquals(991_232L, manifest.sizeBytes)
        assertEquals(OtaExample.SHA256, manifest.sha256)
        assertEquals(11, manifest.minAppVersionCode)
        assertEquals("Softer pianissimo; the pedal lifts on stop.", manifest.notes)
        assertFalse(manifest.usbOnly)
        assertEquals(FirmwareVersion(2, 1, 0), manifest.parsedVersion)
        assertEquals(64, manifest.signature().size)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(OtaExample.image()), manifest.digest())
    }

    @Test
    fun `the example verifies against RFC 8032's test key, and never against the author's`() {
        val manifest = FirmwareManifest.parse(OtaExample.manifestJson())
        for (platformFirst in listOf(true, false)) {
            assertTrue(manifest.verify(FirmwareKeys.rfc8032Test, platformFirst))
            assertFalse("a test release is never the author's", manifest.verify(FirmwareKeys.author, platformFirst))
        }
    }

    @Test
    fun `a release signed with a test key pair verifies, and a tampered sha256 or signature does not`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val publicKey = pair.public.encoded.takeLast(32).toByteArray()   // the raw key ends the X.509 encoding
        val image = OtaExample.image(40_000)
        val digest = MessageDigest.getInstance("SHA-256").digest(image)
        val signature = Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(digest)
            sign()
        }
        val json = OtaExample.manifestJson {
            put("sizeBytes", image.size)
            put("sha256", digest.joinToString("") { "%02x".format(it) })
            put("sig", Base64.getEncoder().encodeToString(signature))
        }
        val manifest = FirmwareManifest.parse(json)
        for (platformFirst in listOf(true, false)) {
            assertTrue(manifest.verify(publicKey, platformFirst))
            val tampered = manifest.copy(sha256 = manifest.sha256.replaceRange(0, 1, if (manifest.sha256[0] == '0') "1" else "0"))
            assertFalse("a tampered sha256 is refused", tampered.verify(publicKey, platformFirst))
            val bent = signature.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
            assertFalse("a tampered signature is refused", manifest.copy(sig = Base64.getEncoder().encodeToString(bent)).verify(publicKey, platformFirst))
            assertFalse("the author's key never takes a test release", manifest.verify(FirmwareKeys.author, platformFirst))
        }
    }

    @Test
    fun `usbOnly and minAppVersionCode read as the release says`() {
        val usb = FirmwareManifest.parse(OtaExample.manifestJson { put("usbOnly", true) })
        assertTrue(usb.usbOnly)
        val newer = FirmwareManifest.parse(OtaExample.manifestJson { put("minAppVersionCode", 14) })
        assertTrue("above the app's: it needs a newer app", newer.needsNewerApp(appVersionCode = 13))
        assertFalse(newer.needsNewerApp(appVersionCode = 14))
        // § 10's example names 11, as firmware 2.0.0 does (§ 15): 1.6's build takes it, the build before doesn't.
        val example = FirmwareManifest.parse(OtaExample.manifestJson())
        assertFalse(example.needsNewerApp(appVersionCode = 11))
        assertTrue(example.needsNewerApp(appVersionCode = 10))
    }

    @Test
    fun `unknown fields are ignored and notes may be left out`() {
        val manifest = FirmwareManifest.parse(OtaExample.manifestJson { remove("notes"); put("channel", "stable") })
        assertEquals("", manifest.notes)
    }

    @Test
    fun `a manifest missing any required field is refused`() {
        for (field in listOf("version", "build", "binUrl", "sizeBytes", "sha256", "sig", "minAppVersionCode", "usbOnly")) {
            refused(OtaExample.manifestJson { remove(field) }, field)
            refused(OtaExample.manifestJson { put(field, JSONObject.NULL) }, "$field as null")
        }
        refused("", "empty")
        refused("[1]", "an array")
        refused("{\"version\": ", "cut short")
    }

    @Test
    fun `fields of the wrong shape are refused`() {
        refused(OtaExample.manifestJson { put("version", "2.1") }, "two numbers")
        refused(OtaExample.manifestJson { put("version", "v2.1.0") }, "a v")
        refused(OtaExample.manifestJson { put("version", "2.1.0+a1b2c3d") }, "a build in the version")
        refused(OtaExample.manifestJson { put("version", "2.1.0-" + "r".repeat(11)) }, "17 bytes")
        refused(OtaExample.manifestJson { put("version", "2.1.0 ") }, "a space")
        refused(OtaExample.manifestJson { put("build", "A1B2C3D") }, "capitals")
        refused(OtaExample.manifestJson { put("build", "a1b2c3") }, "six characters")
        refused(OtaExample.manifestJson { put("sizeBytes", 0) }, "an empty image")
        refused(OtaExample.manifestJson { put("sizeBytes", 4L * 1024 * 1024 + 1) }, "over 4 MB")
        refused(OtaExample.manifestJson { put("sizeBytes", "991232") }, "a size as a string")
        refused(OtaExample.manifestJson { put("sha256", "6d87") }, "a short hash")
        refused(OtaExample.manifestJson { put("sha256", "z".repeat(64)) }, "not hex")
        refused(OtaExample.manifestJson { put("sig", OtaExample.SIG.dropLast(4)) }, "84 characters")
        refused(OtaExample.manifestJson { put("sig", "!".repeat(88)) }, "not base64")
        refused(OtaExample.manifestJson { put("sig", Base64.getEncoder().encodeToString(ByteArray(65))) }, "65 bytes")
        refused(OtaExample.manifestJson { put("minAppVersionCode", 0) }, "versionCode 0")
        refused(OtaExample.manifestJson { put("minAppVersionCode", 13.5) }, "a fraction")
        refused(OtaExample.manifestJson { put("minAppVersionCode", "13") }, "a string")
        refused(OtaExample.manifestJson { put("usbOnly", "false") }, "usbOnly as a string")
        refused(OtaExample.manifestJson { put("usbOnly", 0) }, "usbOnly as a number")
        refused(OtaExample.manifestJson { put("notes", 12) }, "notes as a number")
    }

    @Test
    fun `a binary anywhere but the firmware repository's releases is refused`() {
        val repo = "stevenjin20090101-rgb/Steven-Jin-Player-Piano"
        refused(OtaExample.manifestJson { put("binUrl", "https://example.com/firmware-2.1.0.bin") }, "another host")
        refused(OtaExample.manifestJson { put("binUrl", "https://objects.githubusercontent.com/release/firmware-2.1.0.bin") }, "an asset host named directly")
        refused(OtaExample.manifestJson { put("binUrl", "https://github.com/stevenjin20090101-rgb/steven-piano-android/releases/download/v1.6.1/f.bin") }, "the app's repository")
        refused(OtaExample.manifestJson { put("binUrl", "https://github.com/$repo/releases/download/fw-v2.1.0/firmware-2.1.0.apk") }, "an APK")
        refused(OtaExample.manifestJson { put("binUrl", "http://github.com/$repo/releases/download/fw-v2.1.0/firmware-2.1.0.bin") }, "http")
    }

    @Test
    fun `notes are plain text, cut to 1,000 characters`() {
        val manifest = FirmwareManifest.parse(OtaExample.manifestJson { put("notes", "Line one\u0007\nLine two\t" + "x".repeat(2_000)) })
        assertTrue(manifest.notes.startsWith("Line one\nLine two"))
        assertEquals(1_000, manifest.notes.length)
    }

    private fun refused(json: String, why: String) {
        try {
            FirmwareManifest.parse(json)
            fail("accepted: $why")
        } catch (e: InvalidFirmwareManifest) {
            // expected
        }
    }
}
