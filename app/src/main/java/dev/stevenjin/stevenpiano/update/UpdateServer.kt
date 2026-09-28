// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import dev.stevenjin.stevenpiano.net.HttpFetch
import dev.stevenjin.stevenpiano.net.HttpTransport
import dev.stevenjin.stevenpiano.net.UrlConnectionTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.IOException

/** The two requests the updater makes. Tests answer them with a fake; the app with [HttpUpdateServer]. */
interface UpdateServer {
    /** The manifest's text, at most [UpdateManifest.MAX_MANIFEST_BYTES]. IOException when it can't be had. */
    suspend fun manifest(): String

    /**
     * The file at [url], handed to [sink] a buffer at a time ([sink] may throw to stop it). IOException
     * when it can't be had; [UpdateFailure] ([UpdateFailures.MISMATCH]) when the server declares more
     * than [cap] bytes.
     */
    suspend fun download(url: String, cap: Long, sink: (buffer: ByteArray, count: Int) -> Unit)
}

/**
 * [UpdateServer] over [HttpFetch], every hop checked by [source] ([UpdateSource.allowsHop]): 10 s to
 * connect, 30 s between reads, the app's User-Agent; the manifest capped at 64 KB before it is
 * decoded. Any answer but 2xx is a failure (a private repository answers 404, which the Piano tab
 * reads as "Couldn't reach the update server."). A cancelled download stops at the next buffer.
 */
class HttpUpdateServer(
    private val source: UpdateSource,
    transport: HttpTransport = UrlConnectionTransport,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    log: ((String) -> Unit)? = null,
    /** What a file request accepts: an APK by default; Studio's models ask for [BINARY_ACCEPT]. */
    fileAccept: String = APK_ACCEPT,
) : UpdateServer {
    private val manifestFetch = HttpFetch(source::allowsHop, "application/json", readTimeoutMs = READ_TIMEOUT_MS, transport = transport, log = log)
    private val fileFetch = HttpFetch(source::allowsHop, fileAccept, readTimeoutMs = READ_TIMEOUT_MS, transport = transport, log = log)

    override suspend fun manifest(): String = withContext(io) {
        manifestFetch.exchange(source.manifestUrl) { answer ->
            if (answer.code !in 200..299) throw IOException("HTTP ${answer.code}")
            val cap = UpdateManifest.MAX_MANIFEST_BYTES
            val bytes = if (answer.contentLength > cap) null else answer.body().use { HttpFetch.readCapped(it, cap) }
            bytes?.toString(Charsets.UTF_8) ?: throw IOException("A manifest over $cap bytes")
        }
    }

    override suspend fun download(url: String, cap: Long, sink: (ByteArray, Int) -> Unit) {
        withContext(io) {
            val job = coroutineContext[Job]
            fileFetch.exchange(url) { answer ->
                if (answer.code !in 200..299) throw IOException("HTTP ${answer.code}")
                if (answer.contentLength > cap) throw UpdateFailure(UpdateFailures.MISMATCH)
                answer.body().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        if (job?.isActive == false) throw CancellationException("The download was cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n > 0) sink(buffer, n)
                    }
                }
            }
        }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 30_000
        private const val BUFFER_BYTES = 64 * 1024
        private const val APK_ACCEPT = "application/vnd.android.package-archive, application/octet-stream;q=0.9, */*;q=0.8"

        /** Any file of bytes: Studio's models (v1.7 — M23). */
        const val BINARY_ACCEPT = "application/octet-stream, */*;q=0.8"
    }
}
