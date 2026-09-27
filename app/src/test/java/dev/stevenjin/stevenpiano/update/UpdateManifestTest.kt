// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The update manifest (v1.4): read field by field, and the file it names must be a release asset of the app's repository. */
class UpdateManifestTest {
    private val source = UpdateSource.production

    @Test
    fun `a valid manifest reads as written`() {
        val manifest = UpdateManifest.parse(Manifests.json(), source)
        assertEquals(8, manifest.versionCode)
        assertEquals("1.4", manifest.versionName)
        assertEquals("Updates from GitHub.\nDiagnostics to share.", manifest.notes)
        assertEquals(Manifests.APK, manifest.apkUrl)
        assertEquals(Manifests.SHA.lowercase(), manifest.sha256)
        assertEquals(2_400_000L, manifest.sizeBytes)
        assertEquals(26, manifest.minSdk)
    }

    @Test
    fun `unknown fields are ignored, notes and minSdk may be left out`() {
        val manifest = UpdateManifest.parse(Manifests.json { remove("notes"); remove("minSdk"); put("channel", "stable") }, source)
        assertEquals("", manifest.notes)
        assertEquals(0, manifest.minSdk)
    }

    @Test
    fun `a manifest missing any required field is refused`() {
        for (field in listOf("versionCode", "versionName", "apkUrl", "sha256", "sizeBytes")) {
            refused(Manifests.json { remove(field) }, field)
            refused(Manifests.json { put(field, JSONObject.NULL) }, "$field as null")
        }
        refused("", "empty")
        refused("not json", "text")
        refused("[1, 2]", "an array")
    }

    @Test
    fun `fields of the wrong shape are refused`() {
        refused(Manifests.json { put("versionCode", "8") }, "versionCode as a string")
        refused(Manifests.json { put("versionCode", 8.5) }, "a fractional versionCode")
        refused(Manifests.json { put("versionCode", 0) }, "versionCode 0")
        refused(Manifests.json { put("versionName", "../1.4") }, "a versionName that is a path")
        refused(Manifests.json { put("versionName", "") }, "an empty versionName")
        refused(Manifests.json { put("sha256", "abc") }, "a short hash")
        refused(Manifests.json { put("sha256", "z".repeat(64)) }, "a hash that is not hex")
        refused(Manifests.json { put("sizeBytes", 0) }, "an empty file")
        refused(Manifests.json { put("sizeBytes", 50L * 1024 * 1024 + 1) }, "a file over 50 MB")
        refused(Manifests.json { put("minSdk", -1) }, "a negative minSdk")
    }

    @Test
    fun `a file on another host is refused`() {
        refused(Manifests.json { put("apkUrl", "https://example.com/steven-piano-1.4.apk") }, "another host")
        refused(Manifests.json { put("apkUrl", "https://objects.githubusercontent.com/github-production-release-asset/1.apk") }, "an asset host named directly")
        refused(Manifests.json { put("apkUrl", "https://raw.githubusercontent.com/stevenjin20090101-rgb/steven-piano-android/main/app.apk") }, "the manifest host")
        refused(Manifests.json { put("apkUrl", "https://github.com.evil.example/stevenjin20090101-rgb/steven-piano-android/releases/download/v1.4/a.apk") }, "a look-alike host")
        refused(Manifests.json { put("apkUrl", "https://evil.example@github.com/stevenjin20090101-rgb/steven-piano-android/releases/download/v1.4/a.apk") }, "user info")
    }

    @Test
    fun `a file outside this repository's releases is refused`() {
        val repo = "https://github.com/stevenjin20090101-rgb/steven-piano-android"
        refused(Manifests.json { put("apkUrl", "https://github.com/someone-else/steven-piano-android/releases/download/v1.4/steven-piano-1.4.apk") }, "another owner")
        refused(Manifests.json { put("apkUrl", "$repo/raw/main/steven-piano-1.4.apk") }, "not a release asset")
        refused(Manifests.json { put("apkUrl", "$repo/releases/download/v1.4/../../../../evil/app.apk") }, "dot segments")
        refused(Manifests.json { put("apkUrl", "$repo/releases/download/v1.4/steven-piano-1.4.apk?x=1") }, "a query")
        refused(Manifests.json { put("apkUrl", "$repo/releases/download/v1.4/steven-piano-1.4.zip") }, "not an APK")
        refused(Manifests.json { put("apkUrl", "$repo/releases/download/steven-piano-1.4.apk") }, "no tag")
    }

    @Test
    fun `plain HTTP, and HTTPS on another port, are refused`() {
        refused(Manifests.json { put("apkUrl", Manifests.APK.replace("https://", "http://")) }, "http")
        refused(Manifests.json { put("apkUrl", Manifests.APK.replace("github.com", "github.com:8443")) }, "port 8443")
        assertNotNull(UpdateManifest.parse(Manifests.json { put("apkUrl", Manifests.APK.replace("github.com", "github.com:443")) }, source))
    }

    @Test
    fun `notes are plain text, capped`() {
        val manifest = UpdateManifest.parse(Manifests.json { put("notes", "A\u0007B\nC" + "x".repeat(2_000)) }, source)
        assertTrue(manifest.notes.startsWith("AB\nC"))
        assertEquals(UpdateManifest.MAX_NOTES, manifest.notes.length)
    }

    @Test
    fun `the production source reaches the four GitHub hosts and nothing else`() {
        assertEquals(setOf("raw.githubusercontent.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"), UpdateSource.HOSTS)
        assertTrue(source.allowsHop(UpdateSource.MANIFEST_URL))
        assertEquals("https://raw.githubusercontent.com/stevenjin20090101-rgb/steven-piano-android/main/releases/latest.json", source.manifestUrl)
        assertTrue(source.allowsHop(Manifests.APK))
        assertTrue(source.allowsHop("https://release-assets.githubusercontent.com/github-production-release-asset/1?sp=r&sig=x"))
        assertFalse(source.allowsHop("https://raw.githubusercontent.com/someone-else/repo/main/releases/latest.json"))
        assertFalse(source.allowsHop("https://github.com/stevenjin20090101-rgb/steven-piano-android"))
        assertFalse(source.allowsHop("https://api.github.com/repos/stevenjin20090101-rgb/steven-piano-android/releases/latest"))
        assertFalse(source.allowsHop("http://raw.githubusercontent.com/stevenjin20090101-rgb/steven-piano-android/main/releases/latest.json"))
        assertTrue(source.isProduction)
    }

    @Test
    fun `a local source keeps to its one origin, plain HTTP allowed`() {
        val local = UpdateSource.local("http://10.0.2.2:8765/latest.json")!!
        assertFalse(local.isProduction)
        assertTrue(local.allowsHop("http://10.0.2.2:8765/latest.json"))
        assertTrue(local.allowsApk("http://10.0.2.2:8765/steven-piano-9.apk"))
        assertFalse(local.allowsHop("http://10.0.2.2:8766/latest.json"))
        assertFalse(local.allowsHop("https://10.0.2.2:8765/latest.json"))
        assertFalse(local.allowsApk("http://10.0.2.2:8765/../steven-piano-9.apk"))
        assertFalse(local.allowsApk(Manifests.APK))
        val manifest = UpdateManifest.parse(Manifests.json { put("apkUrl", "http://10.0.2.2:8765/steven-piano-9.apk") }, local)
        assertEquals("http://10.0.2.2:8765/steven-piano-9.apk", manifest.apkUrl)
        refused(Manifests.json { put("apkUrl", "http://10.0.2.2:8765/steven-piano-9.apk") }, "a local file from production")
        assertNull(UpdateSource.local("ftp://10.0.2.2/latest.json"))
        assertNull(UpdateSource.local("latest.json"))
    }

    private fun refused(text: String, why: String) {
        try {
            UpdateManifest.parse(text, source)
            fail("accepted: $why")
        } catch (e: InvalidManifest) {
            // refused, as it should be
        }
    }
}

/** A manifest as the publishing script writes it, and edits of it. */
object Manifests {
    const val APK = "https://github.com/stevenjin20090101-rgb/steven-piano-android/releases/download/v1.4/steven-piano-1.4.apk"
    const val SHA = "3F2A9C0B1D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8"

    fun json(edit: JSONObject.() -> Unit = {}): String = JSONObject()
        .put("versionCode", 8)
        .put("versionName", "1.4")
        .put("notes", "Updates from GitHub.\nDiagnostics to share.")
        .put("apkUrl", APK)
        .put("sha256", SHA)
        .put("sizeBytes", 2_400_000)
        .put("minSdk", 26)
        .apply(edit)
        .toString()

    fun manifest(
        versionCode: Int = 8,
        versionName: String = "1.4",
        sha256: String = SHA.lowercase(),
        sizeBytes: Long = 2_400_000,
        minSdk: Int = 26,
        apkUrl: String = APK,
    ) = UpdateManifest(versionCode, versionName, "Updates from GitHub.\nDiagnostics to share.", apkUrl, sha256, sizeBytes, minSdk)
}
