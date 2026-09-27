// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import dev.stevenjin.stevenpiano.BuildConfig
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The committed `releases/latest.json` is what every installed copy reads: it must pass the app's
 * own checks against the production source, and never offer a version newer than this source
 * (between a version bump and its publishing it names the release before). `history.json` ends
 * with the same release.
 */
class ReleaseManifestTest {
    @Test
    fun `the committed manifest reads as the app reads it`() {
        val dir = releasesDir()
        val manifest = UpdateManifest.parse(File(dir, "latest.json").readText(), UpdateSource.production)
        assertTrue("latest.json names versionCode ${manifest.versionCode}, above this source's ${BuildConfig.VERSION_CODE}", manifest.versionCode <= BuildConfig.VERSION_CODE)
        assertEquals(
            "https://github.com/stevenjin20090101-rgb/steven-piano-android/releases/download/v${manifest.versionName}/steven-piano-${manifest.versionName}.apk",
            manifest.apkUrl,
        )
        val history = JSONArray(File(dir, "history.json").readText())
        val last = history.getJSONObject(history.length() - 1)
        assertEquals(manifest.versionCode, last.getInt("versionCode"))
        assertEquals(manifest.sha256, last.getString("sha256"))
        assertEquals("v${manifest.versionName}", last.getString("tag"))
    }

    /** `releases/` beside `app/`, found from the test's working folder upwards. */
    private fun releasesDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "releases")
            if (File(candidate, "latest.json").isFile) return candidate
            dir = dir.parentFile
        }
        error("releases/latest.json not found above ${System.getProperty("user.dir")}")
    }
}
