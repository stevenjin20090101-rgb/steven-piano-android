// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.library

import dev.stevenjin.stevenpiano.update.UpdateSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Steven's library pack's manifest (v1.10 — M27): `releases/library.json` as the app reads it, every field
 * checked, at most 4 KB, the zip only where the library's release keeps it.
 */
class LibraryManifestTest {
    private val repo = UpdateSource.REPOSITORY
    private val sha = "358cc000dd00edaf6c683517e3f4f0cfc935bdf98f2b7880126a9e30607c7e2f"

    private fun manifest(version: Int = 1) = JSONObject()
        .put("version", version)
        .put("file", "library-v$version.zip")
        .put("url", "https://github.com/$repo/releases/download/library/library-v$version.zip")
        .put("sizeBytes", 61_237_277L)
        .put("sha256", sha)
        .put("pieces", 1_726)
        .put("notes", "Steven's library: 1,726 piano pieces.")
        .put("licences", JSONArray(listOf("MAESTRO CC BY-NC-SA 4.0", "piano-midi.de CC BY-SA 3.0 DE", "Mutopia public domain")))

    private fun parse(json: JSONObject, source: UpdateSource = UpdateSource.library) = LibraryManifest.parse(json.toString(), source)

    private fun refused(why: String, json: JSONObject, source: UpdateSource = UpdateSource.library) {
        try {
            parse(json, source)
            fail("$why: accepted")
        } catch (e: InvalidLibraryManifest) {
            // refused, as it should be
        }
    }

    @Test
    fun `the committed manifest reads as the app reads it`() {
        val committed = LibraryManifest.parse(File(releasesDir(), "library.json").readText(), UpdateSource.library)
        assertEquals("library-v${committed.version}.zip", committed.file)
        assertEquals("https://github.com/$repo/releases/download/library/${committed.file}", committed.url)
        assertTrue(committed.sizeBytes in 1..UpdateSource.MAX_LIBRARY_BYTES)
        assertEquals(64, committed.sha256.length)
        assertEquals(listOf("MAESTRO CC BY-NC-SA 4.0", "piano-midi.de CC BY-SA 3.0 DE", "Mutopia public domain"), committed.licences)
        assertTrue(File(releasesDir(), "library.json").length() <= LibraryManifest.MAX_BYTES)
    }

    @Test
    fun `a good manifest reads field by field`() {
        val read = parse(manifest().put("sha256", sha.uppercase()).put("extra", "ignored"))
        assertEquals(
            LibraryManifest(
                version = 1,
                file = "library-v1.zip",
                url = "https://github.com/$repo/releases/download/library/library-v1.zip",
                sizeBytes = 61_237_277L,
                sha256 = sha,
                pieces = 1_726,
                notes = "Steven's library: 1,726 piano pieces.",
                licences = listOf("MAESTRO CC BY-NC-SA 4.0", "piano-midi.de CC BY-SA 3.0 DE", "Mutopia public domain"),
            ),
            read,
        )
        val bare = parse(manifest().apply { remove("notes"); remove("licences") })
        assertEquals("", bare.notes)
        assertEquals(emptyList<String>(), bare.licences)
    }

    @Test
    fun `each field is checked`() {
        refused("version 0", manifest().put("version", 0).put("file", "library-v0.zip"))
        refused("version past 10,000", manifest(10_001))
        refused("a version in words", manifest().put("version", "1"))
        refused("a version with a fraction", manifest().put("version", 1.5))
        refused("another file name", manifest().put("file", "library.zip"))
        refused("the file of another version", manifest().put("file", "library-v2.zip"))
        refused("a url of another file", manifest().put("url", "https://github.com/$repo/releases/download/library/library-v2.zip"))
        refused("the models' release", manifest().put("url", "https://github.com/$repo/releases/download/models/library-v1.zip"))
        refused("a version tag", manifest().put("url", "https://github.com/$repo/releases/download/v1.10/library-v1.zip"))
        refused("another host", manifest().put("url", "https://evil.example/library-v1.zip"))
        refused("an asset host named directly", manifest().put("url", "https://release-assets.githubusercontent.com/library-v1.zip"))
        refused("plain HTTP", manifest().put("url", "http://github.com/$repo/releases/download/library/library-v1.zip"))
        refused("a query", manifest().put("url", "https://github.com/$repo/releases/download/library/library-v1.zip?x=1"))
        refused("no size", manifest().put("sizeBytes", 0))
        refused("past the cap", manifest().put("sizeBytes", UpdateSource.MAX_LIBRARY_BYTES + 1))
        refused("a short hash", manifest().put("sha256", sha.drop(1)))
        refused("a hash that is not hex", manifest().put("sha256", sha.replaceFirst('3', 'z')))
        refused("no pieces", manifest().put("pieces", 0))
        refused("more pieces than a zip may hold", manifest().put("pieces", 20_001))
        refused("notes that are not text", manifest().put("notes", 5))
        refused("licences that are not a list", manifest().put("licences", "MAESTRO"))
        refused("a licence that is not text", manifest().put("licences", JSONArray(listOf(1))))
        refused("an empty licence", manifest().put("licences", JSONArray(listOf(" "))))
        refused("a licence line too long", manifest().put("licences", JSONArray(listOf("x".repeat(121)))))
        refused("too many licences", manifest().put("licences", JSONArray((1..9).map { "L$it" })))
        for (field in listOf("version", "file", "url", "sizeBytes", "sha256", "pieces")) refused("no $field", manifest().apply { remove(field) })
    }

    @Test
    fun `not a manifest, or larger than 4 KB, is refused`() {
        for (text in listOf("", "not json", "[1, 2]", "{\"version\": ")) {
            try {
                LibraryManifest.parse(text, UpdateSource.library)
                fail("\"$text\" accepted")
            } catch (e: InvalidLibraryManifest) {
                // refused
            }
        }
        refused("over 4 KB", manifest().put("padding", "x".repeat(4_000)))
        val justUnder = manifest().put("padding", "")
        val room = LibraryManifest.MAX_BYTES - justUnder.toString().toByteArray().size
        parse(justUnder.put("padding", "x".repeat(room)))   // 4,096 bytes exactly still reads
    }

    @Test
    fun `notes are plain text, cut to 1,000 characters`() {
        val read = parse(manifest().put("notes", "  One\u0007 line\nand another  "))
        assertEquals("One line\nand another", read.notes)
        assertEquals(1_000, parse(manifest().put("notes", "n".repeat(1_500))).notes.length)
        assertEquals("a line's control characters go", "MAESTRO", parse(manifest().put("licences", JSONArray(listOf("MAES\u0000TRO")))).licences.single())
    }

    @Test
    fun `the emulator's manifest may name a zip on its own server, or the published pack`() {
        val local = UpdateSource.localLibrary("http://10.0.2.2:8767/library.json")!!
        val onServer = parse(manifest(2).put("url", "http://10.0.2.2:8767/library-v2.zip"), local)
        assertEquals("http://10.0.2.2:8767/library-v2.zip", onServer.url)
        assertEquals(1, parse(manifest(), local).version)
        refused("another port", manifest(2).put("url", "http://10.0.2.2:8768/library-v2.zip"), local)
        refused("the production source never takes the emulator's", manifest(2).put("url", "http://10.0.2.2:8767/library-v2.zip"))
        refused("the models' source never reads a pack", manifest(), UpdateSource.models)
    }

    /** `releases/` beside `app/`, found from the test's working folder upwards. */
    private fun releasesDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "releases")
            if (File(candidate, "library.json").isFile) return candidate
            dir = dir.parentFile
        }
        error("releases/library.json not found above ${System.getProperty("user.dir")}")
    }
}
