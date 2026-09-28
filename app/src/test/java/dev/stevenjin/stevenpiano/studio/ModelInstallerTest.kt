// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio

import dev.stevenjin.stevenpiano.update.FakeUpdateServer
import dev.stevenjin.stevenpiano.update.UpdateSource
import dev.stevenjin.stevenpiano.update.VerifiedDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * A model's install end to end (v1.7 — M23): the models' list, its checks against the pins, the file
 * from the address the list gives, verified, into the store; every failure one line in words.
 */
class ModelInstallerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val bytes = Random(7).nextBytes(700_000)
    private val file = "tiny-v1.onnx"
    private val url = "https://github.com/${UpdateSource.REPOSITORY}/releases/download/models/$file"
    private val tiny = ModelEntry(
        name = "tiny", version = 1, file = file, url = url, sizeBytes = bytes.size.toLong(),
        sha256 = VerifiedDownloader.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        licence = "CC0-1.0", attribution = "tiny", title = "Tiny", licenceLabel = "CC0", use = "",
    )
    private val server = FakeUpdateServer(list(entry())).apply { chunk = 64 * 1024 }
    private val store get() = ModelStore(File(tmp.root, "models"), listOf(tiny))

    private fun entry(sha: String = tiny.sha256, at: String = url) = JSONObject()
        .put("name", "tiny").put("version", 1).put("file", file).put("url", at)
        .put("sizeBytes", bytes.size).put("sha256", sha).put("licence", "CC0-1.0").put("attribution", "Tiny model")

    private fun list(vararg entries: JSONObject) = JSONObject().put("models", JSONArray(entries.toList())).toString()

    private fun installer(store: ModelStore = this.store) =
        ModelInstaller(UpdateSource.models, server, store, VerifiedDownloader(server, Dispatchers.Unconfined) { 10L shl 30 })

    @Test
    fun `the list is read, the file downloaded to its pinned hash, and the model is installed`() = runBlocking {
        server.files[url] = bytes
        val store = store
        var last = 0L
        val installed = installer(store).install(tiny) { done, _ -> last = done }
        assertEquals(1, server.manifestCalls)
        assertEquals(listOf(url), server.downloads)
        assertArrayEquals(bytes, installed.readBytes())
        assertEquals(store.file(tiny), installed)
        assertEquals(700_000L, last)
        assertEquals(setOf("tiny"), store.installed.value)
    }

    @Test
    fun `every failure is one line in words`() = runBlocking {
        server.failing = true
        failsWith(StudioFailures.UNREACHABLE) { installer().install(tiny) }
        server.failing = false
        server.manifestText = "not json"
        failsWith(StudioFailures.UNREADABLE) { installer().install(tiny) }
        server.manifestText = list(entry(at = "https://evil.example/$file"))
        failsWith(StudioFailures.UNREADABLE) { installer().install(tiny) }
        server.manifestText = list()
        failsWith(StudioFailures.NOT_OFFERED) { installer().install(tiny) }
        server.manifestText = list(entry())
        server.files[url] = bytes.copyOf().also { it[1] = (it[1] + 1).toByte() }
        failsWith(StudioFailures.MISMATCH) { installer().install(tiny) }
        server.files[url] = bytes
        server.breakAfter = 200_000
        failsWith(StudioFailures.STOPPED) { installer().install(tiny) }
        assertEquals(emptySet<String>(), store.refresh())
    }

    @Test
    fun `a list naming another hash for the real transcription model is refused before anything is downloaded`() = runBlocking {
        val forged = JSONObject()
            .put("name", "transcription").put("version", 1).put("file", "transcription-v1.onnx")
            .put("url", ModelCatalogue.transcription.url).put("sizeBytes", ModelCatalogue.transcription.sizeBytes)
            .put("sha256", "00".repeat(32)).put("licence", "CC-BY-4.0").put("attribution", "forged")
        server.manifestText = list(forged, entry())
        server.files[url] = bytes
        failsWith(StudioFailures.UNREADABLE) { installer().install(tiny) }
        assertEquals(emptyList<String>(), server.downloads)
    }

    @Test
    fun `the file is held to the pinned hash, whatever the list says of a version the build doesn't pin`() = runBlocking {
        val other = Random(8).nextBytes(700_000)
        server.manifestText = list(entry(sha = VerifiedDownloader.hex(MessageDigest.getInstance("SHA-256").digest(other))))
        server.files[url] = other
        failsWith(StudioFailures.MISMATCH) { installer().install(tiny) }
    }

    private suspend fun failsWith(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("no failure; expected \"$message\"")
        } catch (e: StudioFailure) {
            assertEquals(message, e.message)
        }
    }
}
