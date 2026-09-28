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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The committed `releases/latest.json` is what every installed copy reads: it must pass the app's
 * own checks against the production source, and never offer a version newer than this source
 * (between a version bump and its publishing it names the release before). `history.json`'s last
 * published entry is the same release; after it may stand one entry drafted with the version bump
 * (`"draft": true`: this source's version and its notes), which `tools/publish-release.sh`
 * replaces with the published one.
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
        val last = history(dir).last { !it.optBoolean("draft") }
        assertEquals(manifest.versionCode, last.getInt("versionCode"))
        assertEquals(manifest.sha256, last.getString("sha256"))
        assertEquals("v${manifest.versionName}", last.getString("tag"))
    }

    @Test
    fun `a drafted release is this source's version, after every published one`() {
        val entries = history(releasesDir())
        val drafts = entries.filter { it.optBoolean("draft") }
        if (drafts.isEmpty()) return
        assertEquals("one drafted release at a time", 1, drafts.size)
        val draft = drafts.single()
        assertEquals("the draft is history.json's last entry", entries.last(), draft)
        assertEquals(BuildConfig.VERSION_CODE, draft.getInt("versionCode"))
        assertEquals(BuildConfig.VERSION_NAME, draft.getString("versionName"))
        assertEquals("v${BuildConfig.VERSION_NAME}", draft.getString("tag"))
        val notes = draft.getString("notes")
        assertTrue("the notes are ${notes.length} characters; the app shows at most 1,000", notes.isNotBlank() && notes.length <= 1000)
        for (published in entries.dropLast(1)) {
            assertTrue("${published.getString("tag")} is not older than the draft", published.getInt("versionCode") < draft.getInt("versionCode"))
        }
    }

    private fun history(dir: File): List<JSONObject> {
        val array = JSONArray(File(dir, "history.json").readText())
        return List(array.length()) { array.getJSONObject(it) }
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
