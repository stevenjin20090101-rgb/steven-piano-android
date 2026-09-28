// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

/**
 * The verified download (v1.7 — M23, [UpdateDownloader]'s way made general for Studio's models): a
 * `.part` file hashed on the way, kept under its name only at the expected size and SHA-256, never past
 * its cap, with the free space kept beside it; progress in steps; the rest of the folder untouched.
 */
class VerifiedDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = FakeUpdateServer().apply { chunk = 64 * 1024 }
    private val bytes = Random(23).nextBytes(1_000_000)
    private val url = "https://github.com/stevenjin20090101-rgb/steven-piano-android/releases/download/models/small-v1.onnx"
    private val dir get() = File(tmp.root, "models")
    private var free = 10L * 1024 * 1024 * 1024

    private fun downloader() = VerifiedDownloader(server, Dispatchers.Unconfined) { free }

    private fun target(
        sizeBytes: Long = bytes.size.toLong(),
        sha256: String = sha(bytes),
        cap: Long = 1024L * 1024 * 1024,
        margin: Long = 256L * 1024 * 1024,
        every: Long = 256L * 1024,
    ) = VerifiedDownloader.Target(url, dir, "small-v1.onnx", sizeBytes, sha256, cap, margin, every)

    @Test
    fun `a file that matches is kept under its name, with progress every 256 KB, and the folder's other files stay`() = runBlocking {
        dir.mkdirs()
        File(dir, "composer-v1.onnx").writeText("another model")
        server.files[url] = bytes
        val seen = mutableListOf<Long>()
        val file = downloader().download(target()) { done, total ->
            assertEquals(1_000_000L, total)
            seen += done
        }
        assertEquals(File(dir, "small-v1.onnx"), file)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(setOf("small-v1.onnx", "composer-v1.onnx"), dir.list()!!.toSet())
        assertEquals("every 256 KB, at the buffer that crosses it, then the end", listOf(262_144L, 524_288L, 786_432L, 1_000_000L), seen)
    }

    @Test
    fun `a hash that differs, a size past the expected one or the cap, or a short file, deletes the part`() = runBlocking {
        server.files[url] = bytes.copyOf().also { it[500_000] = (it[500_000] + 1).toByte() }
        failsWith(DownloadProblem.Mismatch) { downloader().download(target()) }
        assertEquals(emptyList<String>(), dir.list()!!.toList())

        server.files[url] = bytes + ByteArray(100_000)
        failsWith(DownloadProblem.Mismatch) { downloader().download(target()) }
        server.files[url] = bytes
        failsWith(DownloadProblem.Mismatch) { downloader().download(target(cap = 500_000)) }
        server.files[url] = bytes.copyOf(900_000)
        failsWith(DownloadProblem.Mismatch) { downloader().download(target()) }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
    }

    @Test
    fun `a dropped connection is Stopped, none at all Unreachable`() = runBlocking {
        server.files[url] = bytes
        server.breakAfter = 300_000
        failsWith(DownloadProblem.Stopped) { downloader().download(target()) }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        server.breakAfter = null
        server.failing = true
        failsWith(DownloadProblem.Unreachable) { downloader().download(target()) }
        server.failing = false
        server.files.clear()
        failsWith(DownloadProblem.Unreachable) { downloader().download(target()) }
    }

    @Test
    fun `too little room for the file and its margin asks nothing of the server`() = runBlocking {
        server.files[url] = bytes
        free = 1_000_000L + 256L * 1024 * 1024 - 1
        failsWith(DownloadProblem.NoRoom) { downloader().download(target()) }
        assertEquals(emptyList<String>(), server.downloads)
        free = 1_000_000L + 256L * 1024 * 1024
        downloader().download(target())
        free = 0   // unknown: tried
        downloader().download(target())
        assertEquals(2, server.downloads.size)
    }

    @Test
    fun `a cancel deletes the part, and a part left from before is started again`() = runBlocking {
        server.files[url] = bytes
        dir.mkdirs()
        File(dir, "small-v1.onnx.part").writeBytes(ByteArray(10))
        try {
            downloader().download(target()) { done, _ -> if (done > 300_000) throw CancellationException("Cancel") }
            fail("not cancelled")
        } catch (e: CancellationException) {
            // as expected
        }
        assertEquals(emptyList<String>(), dir.list()!!.toList())
        val file = downloader().download(target())
        assertEquals(1_000_000L, file.length())
    }

    @Test
    fun `the server's own refusal of a file declared past the cap is a mismatch`() = runBlocking {
        val refusing = object : UpdateServer by server {
            override suspend fun download(url: String, cap: Long, sink: (ByteArray, Int) -> Unit) {
                throw UpdateFailure(UpdateFailures.MISMATCH)
            }
        }
        try {
            VerifiedDownloader(refusing, Dispatchers.Unconfined) { free }.download(target())
            fail()
        } catch (e: DownloadFailure) {
            assertEquals(DownloadProblem.Mismatch, e.problem)
        }
        assertTrue(dir.list()!!.isEmpty())
    }

    private suspend fun failsWith(problem: DownloadProblem, block: suspend () -> Unit) {
        try {
            block()
            fail("no failure; expected $problem")
        } catch (e: DownloadFailure) {
            assertEquals(problem, e.problem)
        }
    }

    private fun sha(data: ByteArray): String = VerifiedDownloader.hex(MessageDigest.getInstance("SHA-256").digest(data))
}
