// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/** Studio's models on the device (v1.7 — M23): where they live, when one counts as there, its hash checked once per process. */
class ModelStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = Random(23).nextBytes(20_000)
    private val small = model("small", bytes)
    private val other = model("other", Random(5).nextBytes(3_000))
    private val dir get() = File(tmp.root, "models")
    private var hashed = 0

    private fun store() = ModelStore(dir, listOf(small, other)) { file -> hashed++; VerifiedDownloader.sha256Of(file) }

    private fun model(name: String, content: ByteArray) = ModelEntry(
        name = name,
        version = 1,
        file = "$name-v1.onnx",
        url = "https://example.invalid/$name-v1.onnx",
        sizeBytes = content.size.toLong(),
        sha256 = VerifiedDownloader.hex(MessageDigest.getInstance("SHA-256").digest(content)),
        licence = "CC0-1.0",
        attribution = name,
        title = name,
        licenceLabel = "CC0",
        use = "",
    )

    private fun put(model: ModelEntry, content: ByteArray = bytes) {
        dir.mkdirs()
        File(dir, model.file).writeBytes(content)
    }

    @Test
    fun `a model lives at filesDir models name-v-version onnx, and counts as there at its pinned size`() {
        val store = store()
        assertEquals(File(dir, "small-v1.onnx"), store.file(small))
        assertEquals("transcription-v1.onnx", ModelStore(dir).file(ModelCatalogue.transcription).name)
        assertFalse(store.isInstalled(small))
        assertEquals(emptySet<String>(), store.refresh())
        put(small, bytes.copyOf(19_999))
        assertFalse("the wrong size is not the model", store.isInstalled(small))
        put(small)
        assertTrue(store.isInstalled(small))
        assertEquals(setOf("small"), store.refresh())
        assertEquals(setOf("small"), store.installed.value)
        assertEquals(20_000L, store.sizeOnDisk())
    }

    @Test
    fun `opening checks the hash once per process, and a file that no longer matches is deleted`() {
        put(small)
        val store = store()
        store.refresh()
        assertEquals(store.file(small), store.open(small))
        assertEquals(1, hashed)
        assertEquals(store.file(small), store.open(small))
        assertEquals("the second open needs no second hash", 1, hashed)

        val fresh = store()
        put(small, bytes.copyOf().also { it[100] = (it[100] + 1).toByte() })
        assertNull("same size, another hash", fresh.open(small))
        assertFalse(fresh.file(small).exists())
        assertEquals(emptySet<String>(), fresh.installed.value)
        assertNull("not there at all", fresh.open(other))
    }

    @Test
    fun `a model that has just been downloaded and verified needs no second check`() {
        val store = store()
        put(small)
        store.downloaded(small)
        assertEquals(setOf("small"), store.installed.value)
        assertEquals(store.file(small), store.open(small))
        assertEquals(0, hashed)
    }

    @Test
    fun `remove takes the file and any half download, and the check again`() {
        val store = store()
        put(small)
        store.downloaded(small)
        File(dir, "small-v1.onnx.part").writeText("half")
        store.remove(small)
        assertFalse(store.file(small).exists())
        assertFalse(File(dir, "small-v1.onnx.part").exists())
        assertEquals(emptySet<String>(), store.installed.value)
        put(small)
        store.refresh()
        store.open(small)
        assertEquals("a model put back is checked again", 1, hashed)
    }

    @Test
    fun `at start, half downloads and files no model names are swept away`() {
        put(small)
        File(dir, "other-v1.onnx.part").writeText("half")
        File(dir, "small-v0.onnx").writeText("an older version")
        val store = store()
        store.sweep()
        assertEquals(listOf("small-v1.onnx"), dir.list()!!.toList())
        assertEquals(setOf("small"), store.installed.value)
    }
}
