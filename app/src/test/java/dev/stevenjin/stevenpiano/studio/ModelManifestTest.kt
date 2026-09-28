// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.update.UpdateSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The models' list (v1.7 — M23): the committed `releases/models.json` reads as the app reads it and
 * matches the hashes pinned in [ModelCatalogue]; every field is checked, and a list that names another
 * hash for a version this build knows is refused whole.
 */
class ModelManifestTest {
    private val repo = UpdateSource.REPOSITORY
    private fun asset(file: String) = "https://github.com/$repo/releases/download/models/$file"

    private fun entry(
        name: String = "transcription",
        version: Int = 1,
        edit: JSONObject.() -> Unit = {},
    ): JSONObject {
        val pin = ModelCatalogue.all.firstOrNull { it.name == name && it.version == version }
        return JSONObject()
            .put("name", name)
            .put("version", version)
            .put("file", "$name-v$version.onnx")
            .put("url", asset("$name-v$version.onnx"))
            .put("sizeBytes", pin?.sizeBytes ?: 1_000_000L)
            .put("sha256", pin?.sha256 ?: "ab".repeat(32))
            .put("licence", "CC-BY-4.0")
            .put("attribution", "Piano transcription model — Kong et al., ByteDance, CC BY 4.0, Zenodo 4034264")
            .apply(edit)
    }

    private fun list(vararg entries: JSONObject): String = JSONObject().put("models", JSONArray(entries.toList())).toString()

    private fun refused(text: String, source: UpdateSource = UpdateSource.models) {
        try {
            ModelManifest.parse(text, source)
            fail("accepted: $text")
        } catch (e: InvalidModelManifest) {
            // as expected
        }
    }

    @Test
    fun `the committed models json reads as the app reads it, and carries the pinned sizes and hashes`() {
        val manifest = ModelManifest.parse(releasesFile("models.json").readText(), UpdateSource.models)
        for (model in ModelCatalogue.all) {
            val entry = manifest.entryFor(model) ?: error("models.json has no ${model.file}")
            assertEquals(model.file, entry.file)
            assertEquals(model.url, entry.url)
            assertEquals(model.sizeBytes, entry.sizeBytes)
            assertEquals(model.sha256, entry.sha256)
            assertEquals(model.licence, entry.licence)
            assertEquals("About shows the list's own credit", model.attribution, entry.attribution)
        }
        assertEquals("transcription-v1.onnx", ModelCatalogue.transcription.file)
        assertEquals(124_511_036L, ModelCatalogue.transcription.sizeBytes)
        assertEquals(173_193_820L, ModelCatalogue.composer.sizeBytes)
    }

    @Test
    fun `a list that names another hash or size for a version this build knows is refused whole`() {
        refused(list(entry { put("sha256", "00".repeat(32)) }))
        refused(list(entry { put("sizeBytes", 124_511_037L) }))
        refused(list(entry(), entry("composer") { put("sha256", "ff".repeat(32)) }))
        // A later version is simply another entry: this build does not know it, so it is not refused (nor offered).
        val later = ModelManifest.parse(list(entry(), entry(version = 2) { put("sha256", "cd".repeat(32)) }), UpdateSource.models)
        assertEquals(2, later.entries.size)
        assertEquals(1, later.entryFor(ModelCatalogue.transcription)!!.version)
        assertNull(later.entryFor(ModelCatalogue.composer))
    }

    @Test
    fun `unknown fields are ignored and the hash is kept lower-case`() {
        val manifest = ModelManifest.parse(
            list(entry { put("sha256", ModelCatalogue.transcription.sha256.uppercase()); put("inputs", JSONArray()); put("source", "Zenodo") }),
            UpdateSource.models,
        )
        assertEquals(ModelCatalogue.transcription.sha256, manifest.entries.single().sha256)
    }

    @Test
    fun `each field is checked`() {
        refused("not json")
        refused("[]")
        refused("{}")
        refused("""{"models": {}}""")
        refused("""{"models": [1]}""")
        for (bad in listOf("", "Transcription", "1model", "a".repeat(33), "trans/cription")) refused(list(entry { put("name", bad) }))
        for (bad in listOf<Any>(0, 1001, "1", 1.5, JSONObject.NULL)) refused(list(entry { put("version", bad) }))
        refused(list(entry { remove("version") }))
        refused(list(entry { put("file", "transcription.onnx") }))
        refused(list(entry { put("file", "../transcription-v1.onnx") }))
        for (bad in listOf(
            asset("composer-v1.onnx"),
            "https://github.com/$repo/releases/download/v1.6.2/transcription-v1.onnx",
            "http://github.com/$repo/releases/download/models/transcription-v1.onnx",
            "https://github.com/someone/else/releases/download/models/transcription-v1.onnx",
            "https://objects.githubusercontent.com/transcription-v1.onnx",
            asset("transcription-v1.onnx") + "?x=1",
            "",
        )) {
            refused(list(entry { put("url", bad) }))
        }
        for (bad in listOf<Any>(0, -1, UpdateSource.MAX_MODEL_BYTES + 1, "124511036")) refused(list(entry(version = 7) { put("sizeBytes", bad) }))
        for (bad in listOf("abc", "zz".repeat(32), "ab".repeat(33))) refused(list(entry(version = 7) { put("sha256", bad) }))
        for (bad in listOf("", "CC BY 4.0", "x".repeat(41))) refused(list(entry { put("licence", bad) }))
        refused(list(entry { put("attribution", "") }))
        refused(list(entry { put("attribution", "x".repeat(ModelManifest.MAX_ATTRIBUTION + 1)) }))
        refused(list(entry(), entry()))
        refused(list(*Array(ModelManifest.MAX_MODELS + 1) { entry(version = it + 2) { put("sha256", "cd".repeat(32)) } }))
    }

    @Test
    fun `an attribution loses its control characters`() {
        val manifest = ModelManifest.parse(list(entry(version = 5) { put("attribution", "Kong\u0000 et al.\n") }), UpdateSource.models)
        assertEquals("Kong et al.", manifest.entries.single().attribution)
    }

    @Test
    fun `the emulator's server names files on its own origin, still pinned`() {
        val local = UpdateSource.localModels("http://10.0.2.2:8766/models.json")!!
        val here = entry { put("url", "http://10.0.2.2:8766/transcription-v1.onnx") }
        assertEquals("http://10.0.2.2:8766/transcription-v1.onnx", ModelManifest.parse(list(here), local).entries.single().url)
        refused(list(entry()), local)
        refused(list(here { put("sha256", "00".repeat(32)) }), local)
        refused(list(here), UpdateSource.models)
    }

    private operator fun JSONObject.invoke(edit: JSONObject.() -> Unit): JSONObject = JSONObject(toString()).apply(edit)

    /** A file of `releases/` beside `app/`, found from the test's working folder upwards. */
    private fun releasesFile(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "releases/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("releases/$name not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun `the catalogue's names are the files' own`() {
        for (model in ModelCatalogue.all) {
            assertEquals("${model.name}-v${model.version}.onnx", model.file)
            assertTrue(UpdateSource.models.allowsModel(model.url))
            assertEquals(model, ModelCatalogue.named(model.name))
        }
        assertNull(ModelCatalogue.named("aria"))
    }
}
