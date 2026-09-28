// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The firmware's allow-list (v1.6.1 — M21; BLE_OTA.md › 10 and 15): the manifest at one address
 * exactly, binaries only among its repository's release assets (and GitHub's two asset hosts for
 * the redirect), a cap of its own, and no overlap with the app's own updates.
 */
class UpdateSourceTest {
    private val repo = "stevenjin20090101-rgb/Steven-Jin-Player-Piano"
    private val manifest = "https://raw.githubusercontent.com/$repo/main/releases/latest.json"
    private val binary = "https://github.com/$repo/releases/download/fw-v2.1.0/firmware-2.1.0.bin"

    /** The repository's name before GitHub renamed it: the app follows no redirect from it. */
    private val oldRepo = "stevenjin20090101-rgb/" + listOf("esp32", "player", "piano").joinToString("-")

    @Test
    fun `the firmware manifest is one address exactly`() {
        assertEquals(manifest, UpdateSource.FIRMWARE_MANIFEST_URL)
        assertTrue(UpdateSource.allowsFirmwareManifest(manifest))
        assertTrue("the host's case does not matter", UpdateSource.allowsFirmwareManifest("https://RAW.githubusercontent.com/$repo/main/releases/latest.json"))
        assertTrue("port 443 said out loud", UpdateSource.allowsFirmwareManifest("https://raw.githubusercontent.com:443/$repo/main/releases/latest.json"))
        for (refused in listOf(
            "http://raw.githubusercontent.com/$repo/main/releases/latest.json",
            "https://raw.githubusercontent.com:8443/$repo/main/releases/latest.json",
            "https://raw.githubusercontent.com/$oldRepo/main/releases/latest.json",
            "https://raw.githubusercontent.com/stevenjin20090101-rgb/steven-piano-android/main/releases/latest.json",
            "https://raw.githubusercontent.com/$repo/dev/releases/latest.json",
            "https://raw.githubusercontent.com/$repo/main/releases/history.json",
            "https://raw.githubusercontent.com/$repo/main/releases/latest.json?x=1",
            "https://raw.githubusercontent.com/$repo/main/releases/latest.json#top",
            "https://raw.githubusercontent.com/$repo/main/releases/../releases/latest.json",
            "https://raw.githubusercontent.com/stevenjin20090101-rgb/steven-jin-player-piano/main/releases/latest.json",
            "https://evil.example@raw.githubusercontent.com/$repo/main/releases/latest.json",
            "https://raw.githubusercontent.com.evil.example/$repo/main/releases/latest.json",
            "https://raw.githubusercontent.com\\@evil.example/$repo/main/releases/latest.json",
            "https://github.com/$repo/raw/main/releases/latest.json",
            "not a url",
        )) {
            assertFalse(refused, UpdateSource.allowsFirmwareManifest(refused))
        }
    }

    @Test
    fun `firmware binaries come from the repository's releases, and GitHub's asset hosts for the redirect`() {
        assertTrue(UpdateSource.allowsFirmwareBinary(binary))
        assertTrue(UpdateSource.allowsFirmwareBinary("https://objects.githubusercontent.com/github-production-release-asset-2e65be/1?X-Amz-Signature=abc"))
        assertTrue(UpdateSource.allowsFirmwareBinary("https://release-assets.githubusercontent.com/github-production-release-asset/2/3?sp=r&sig=x"))
        for (refused in listOf(
            "http://github.com/$repo/releases/download/fw-v2.1.0/firmware-2.1.0.bin",
            "https://github.com:8443/$repo/releases/download/fw-v2.1.0/firmware-2.1.0.bin",
            "https://github.com/$oldRepo/releases/download/fw-v2.1.0/firmware-2.1.0.bin",
            "https://github.com/stevenjin20090101-rgb/steven-piano-android/releases/download/v1.6.1/steven-piano-1.6.1.apk",
            "https://github.com/someone-else/Steven-Jin-Player-Piano/releases/download/fw-v2.1.0/firmware-2.1.0.bin",
            "https://github.com/$repo/releases/download/fw-v2.1.0/sub/firmware-2.1.0.bin",
            "https://github.com/$repo/releases/download/../download/fw-v2.1.0/firmware-2.1.0.bin",
            "https://github.com/$repo/releases/download/fw-v2.1.0/..",
            "https://github.com/$repo/releases/download//firmware-2.1.0.bin",
            "https://github.com/$repo/archive/main.zip",
            "https://raw.githubusercontent.com/$repo/main/firmware.bin",
            "https://example.com/firmware-2.1.0.bin",
            "http://objects.githubusercontent.com/github-production-release-asset/1",
            "https://evil.example@github.com/$repo/releases/download/fw-v2.1.0/firmware-2.1.0.bin",
        )) {
            assertFalse(refused, UpdateSource.allowsFirmwareBinary(refused))
        }
    }

    @Test
    fun `a manifest may name only a bin release asset of the firmware's repository`() {
        assertTrue(UpdateSource.allowsFirmwareFile(binary))
        for (refused in listOf(
            "https://objects.githubusercontent.com/github-production-release-asset/1.bin",
            "https://release-assets.githubusercontent.com/firmware-2.1.0.bin",
            "https://github.com/$repo/releases/download/fw-v2.1.0/firmware-2.1.0.apk",
            "https://github.com/$repo/releases/download/fw-v2.1.0/firmware-2.1.0.bin?download=1",
            "https://github.com/$repo/releases/download/fw-v2.1.0/firmware-2.1.0.bin#x",
            "https://github.com/$oldRepo/releases/download/fw-v2.1.0/firmware-2.1.0.bin",
            "http://github.com/$repo/releases/download/fw-v2.1.0/firmware-2.1.0.bin",
        )) {
            assertFalse(refused, UpdateSource.allowsFirmwareFile(refused))
        }
    }

    @Test
    fun `the firmware source reaches its manifest, its binaries and the asset hosts, and names no APK`() {
        val source = UpdateSource.firmware
        assertEquals(manifest, source.manifestUrl)
        assertTrue(source.isProduction)
        assertTrue(source.allowsHop(manifest))
        assertTrue(source.allowsHop(binary))
        assertTrue(source.allowsHop("https://objects.githubusercontent.com/any/path?sig=1"))
        assertFalse(source.allowsHop(UpdateSource.MANIFEST_URL))
        assertFalse(source.allowsHop("https://github.com/stevenjin20090101-rgb/steven-piano-android/releases/download/v1.6.1/steven-piano-1.6.1.apk"))
        assertFalse(source.allowsApk(binary))
        assertFalse(source.allowsApk("https://github.com/$repo/releases/download/fw-v2.1.0/app.apk"))
    }

    @Test
    fun `the app's own source never reaches the firmware's addresses`() {
        val app = UpdateSource.production
        assertFalse(app.allowsHop(manifest))
        assertFalse(app.allowsHop(binary))
        assertFalse(app.allowsApk(binary))
        assertTrue("the app's own manifest is still reachable", app.allowsHop(UpdateSource.MANIFEST_URL))
    }

    @Test
    fun `a firmware binary has a cap of its own, apart from the APK's`() {
        assertEquals(4L * 1024 * 1024, UpdateSource.MAX_FIRMWARE_BYTES)
        assertNotEquals(UpdateManifest.MAX_APK_BYTES, UpdateSource.MAX_FIRMWARE_BYTES)
        assertTrue("the firmware of 2026-09-24 fits (966,912 bytes)", 966_912L <= UpdateSource.MAX_FIRMWARE_BYTES)
    }
}
