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
 * The firmware's allow-list (v1.6 — M21; BLE_OTA.md › 10 and 15): the manifest at one address
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

    private val appRepo = "stevenjin20090101-rgb/steven-piano-android"
    private val models = "https://raw.githubusercontent.com/$appRepo/main/releases/models.json"
    private val model = "https://github.com/$appRepo/releases/download/models/transcription-v1.onnx"

    @Test
    fun `the models' list is one address exactly, and names only onnx assets of the release tagged models`() {
        assertEquals(models, UpdateSource.MODELS_MANIFEST_URL)
        assertTrue(UpdateSource.allowsModelManifest(models))
        assertTrue(UpdateSource.allowsModelManifest("https://RAW.githubusercontent.com:443/$appRepo/main/releases/models.json"))
        for (refused in listOf(
            "http://raw.githubusercontent.com/$appRepo/main/releases/models.json",
            "https://raw.githubusercontent.com/$appRepo/main/releases/latest.json",
            "https://raw.githubusercontent.com/$appRepo/dev/releases/models.json",
            "https://raw.githubusercontent.com/$appRepo/main/releases/models.json?x=1",
            "https://raw.githubusercontent.com/$appRepo/main/releases/../releases/models.json",
            "https://raw.githubusercontent.com/$repo/main/releases/models.json",
            "https://evil.example@raw.githubusercontent.com/$appRepo/main/releases/models.json",
        )) {
            assertFalse(refused, UpdateSource.allowsModelManifest(refused))
        }
        val source = UpdateSource.models
        assertTrue(source.allowsModel(model))
        assertTrue(source.allowsModel("https://github.com/$appRepo/releases/download/models/composer-v1.onnx"))
        for (refused in listOf(
            "https://github.com/$appRepo/releases/download/v1.6.2/transcription-v1.onnx",
            "https://github.com/$appRepo/releases/download/models/steven-piano-1.6.2.apk",
            "https://github.com/$appRepo/releases/download/models/transcription-v1.onnx?download=1",
            "https://github.com/$appRepo/releases/download/models/transcription-v1.onnx#x",
            "https://github.com/$appRepo/releases/download/models/sub/transcription-v1.onnx",
            "https://github.com/$appRepo/releases/download/models/..",
            "https://github.com/someone-else/steven-piano-android/releases/download/models/transcription-v1.onnx",
            "https://github.com/$repo/releases/download/models/transcription-v1.onnx",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1.onnx",
            "http://github.com/$appRepo/releases/download/models/transcription-v1.onnx",
            "https://github.com:8443/$appRepo/releases/download/models/transcription-v1.onnx",
        )) {
            assertFalse(refused, source.allowsModel(refused))
        }
    }

    @Test
    fun `the models' source reaches its list, its files and the asset hosts, and nothing of the app's or the firmware's`() {
        val source = UpdateSource.models
        assertEquals(models, source.manifestUrl)
        assertTrue(source.isProduction)
        assertTrue(source.allowsHop(models))
        assertTrue(source.allowsHop(model))
        assertTrue(source.allowsHop("https://objects.githubusercontent.com/any/path?sig=1"))
        assertTrue(source.allowsHop("https://release-assets.githubusercontent.com/github-production-release-asset/2/3?sp=r"))
        assertFalse(source.allowsHop(UpdateSource.MANIFEST_URL))
        assertFalse(source.allowsHop("https://github.com/$appRepo/releases/download/v1.6.2/steven-piano-1.6.2.apk"))
        assertFalse(source.allowsHop(manifest))
        assertFalse(source.allowsHop(binary))
        assertFalse(source.allowsApk(model))
        assertFalse("the app's own source never names a model as its APK", UpdateSource.production.allowsApk(model))
        assertFalse("nor does the firmware's", UpdateSource.firmware.allowsHop(model))
        assertFalse(UpdateSource.production.allowsModel(model))
        assertFalse(UpdateSource.firmware.allowsModel(model))
    }

    @Test
    fun `the emulator's models server is one origin, onnx files only`() {
        val local = UpdateSource.localModels("http://10.0.2.2:8766/models.json")!!
        assertFalse(local.isProduction)
        assertTrue(local.allowsHop("http://10.0.2.2:8766/models.json"))
        assertTrue(local.allowsModel("http://10.0.2.2:8766/transcription-v1.onnx"))
        assertFalse(local.allowsModel("http://10.0.2.2:8766/app.apk"))
        assertFalse(local.allowsModel("http://10.0.2.2:8767/transcription-v1.onnx"))
        assertFalse(local.allowsModel(model))
        assertFalse(local.allowsApk("http://10.0.2.2:8766/app.apk"))
        assertEquals(null, UpdateSource.localModels("not a url"))
    }

    @Test
    fun `the models' list may name sf2 sounds too, of the same release, and nothing else new`() {
        val source = UpdateSource.models
        val sound = "https://github.com/$appRepo/releases/download/models/upright-piano-kw-v1.sf2"
        assertTrue(source.allowsModel(sound, ".sf2"))
        assertTrue(UpdateSource.allowsModelFile(sound, ".sf2"))
        assertFalse("a sound is never a model", source.allowsModel(sound))
        assertFalse(source.allowsModel("https://github.com/$appRepo/releases/download/models/transcription-v1.onnx", ".sf2"))
        assertFalse(source.allowsModel("https://github.com/$appRepo/releases/download/v1.8/upright-piano-kw-v1.sf2", ".sf2"))
        assertFalse(source.allowsModel("https://github.com/$appRepo/releases/download/models/piano.zip", ".zip"))
        assertFalse(source.allowsModel("http://github.com/$appRepo/releases/download/models/upright-piano-kw-v1.sf2", ".sf2"))
        assertFalse(UpdateSource.production.allowsModel(sound, ".sf2"))
        val local = UpdateSource.localModels("http://10.0.2.2:8766/models.json")!!
        assertTrue(local.allowsModel("http://10.0.2.2:8766/upright-piano-kw-v1.sf2", ".sf2"))
        assertFalse(local.allowsModel("http://10.0.2.2:8766/upright-piano-kw-v1.sf2"))
        assertEquals("the SoundFont's cap", 200L * 1024 * 1024, UpdateSource.MAX_SOUND_BYTES)
    }

    @Test
    fun `a model has a cap of its own, 1 GB`() {
        assertEquals(1024L * 1024 * 1024, UpdateSource.MAX_MODEL_BYTES)
        assertTrue("the composer is 173 MB", 173_193_820L <= UpdateSource.MAX_MODEL_BYTES)
    }

    @Test
    fun `a firmware binary has a cap of its own, apart from the APK's`() {
        assertEquals(4L * 1024 * 1024, UpdateSource.MAX_FIRMWARE_BYTES)
        assertNotEquals(UpdateManifest.MAX_APK_BYTES, UpdateSource.MAX_FIRMWARE_BYTES)
        assertTrue("the firmware of 2026-09-24 fits (966,912 bytes)", 966_912L <= UpdateSource.MAX_FIRMWARE_BYTES)
    }
}
