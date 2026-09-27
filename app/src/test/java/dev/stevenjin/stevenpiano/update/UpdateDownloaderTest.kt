// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/** The download (v1.4): streamed to a part file, hashed on the way, kept only when size and SHA-256 match the manifest. */
class UpdateDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = FakeUpdateServer()
    private val bytes = Random(8).nextBytes(10_000)
    private val manifest = Manifests.manifest(sha256 = sha(bytes), sizeBytes = bytes.size.toLong())
    private val dir get() = File(tmp.root, "updates")

    private fun downloader(cap: Long = UpdateManifest.MAX_APK_BYTES) = UpdateDownloader(dir, server, cap, Dispatchers.Unconfined)

    @Test
    fun `a file whose hash matches is kept under its version's name`() = runBlocking {
        server.files[Manifests.APK] = bytes
        val seen = mutableListOf<Pair<Long, Long>>()
        val file = downloader().download(manifest) { done, total -> seen += done to total }
        assertEquals(File(dir, "1.4.apk"), file)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(listOf("1.4.apk"), dir.list()!!.toList())
        assertEquals(10, seen.size)
        assertEquals(10_000L to 10_000L, seen.last())
        assertTrue(downloader().verified(file, manifest))
    }

    @Test
    fun `a file whose hash differs is deleted and the download fails`() = runBlocking {
        server.files[Manifests.APK] = bytes.copyOf().also { it[5_000] = (it[5_000] + 1).toByte() }
        failsWith(UpdateFailures.MISMATCH) { downloader().download(manifest) }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `a file past the manifest's size, or past the cap, is cut off and deleted`() = runBlocking {
        server.files[Manifests.APK] = bytes + ByteArray(5_000)
        failsWith(UpdateFailures.MISMATCH) { downloader().download(manifest) }
        assertEquals(emptyList<String>(), dir.list()!!.toList())

        server.files[Manifests.APK] = bytes
        failsWith(UpdateFailures.MISMATCH) { downloader(cap = 4_096).download(manifest) }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        assertEquals(50L * 1024 * 1024, UpdateManifest.MAX_APK_BYTES)
    }

    @Test
    fun `a short file does not match either`() = runBlocking {
        server.files[Manifests.APK] = bytes.copyOf(9_000)
        failsWith(UpdateFailures.MISMATCH) { downloader().download(manifest) }
    }

    @Test
    fun `a dropped connection says the download stopped, no connection says the server couldn't be reached`() = runBlocking {
        server.files[Manifests.APK] = bytes
        server.breakAfter = 4_000
        failsWith(UpdateFailures.STOPPED) { downloader().download(manifest) }
        assertEquals(emptyList<String>(), dir.list()!!.toList())

        server.breakAfter = null
        server.failing = true
        failsWith(UpdateFailures.UNREACHABLE) { downloader().download(manifest) }
    }

    @Test
    fun `older downloads are cleared first, and a file changed on disk no longer verifies`() = runBlocking {
        dir.mkdirs()
        File(dir, "1.3.1.apk").writeText("old")
        File(dir, "1.4.apk.part").writeText("half")
        server.files[Manifests.APK] = bytes
        val file = downloader().download(manifest)
        assertEquals(listOf("1.4.apk"), dir.list()!!.toList())
        file.appendBytes(byteArrayOf(0))
        assertFalse(downloader().verified(file, manifest))
        downloader().clear()
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    private suspend fun failsWith(message: String, block: suspend () -> Unit) {
        try {
            block()
            fail("no failure; expected \"$message\"")
        } catch (e: UpdateFailure) {
            assertEquals(message, e.message)
        }
    }

    private fun sha(data: ByteArray): String = UpdateDownloader.hex(MessageDigest.getInstance("SHA-256").digest(data))
}
