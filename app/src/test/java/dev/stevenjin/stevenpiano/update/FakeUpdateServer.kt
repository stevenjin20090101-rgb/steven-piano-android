// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.update

import java.io.IOException

/**
 * The update server for tests: [manifestText] is what the manifest request answers ([failing] makes
 * it throw as a dead connection does); [files] by URL, handed over in [chunk]-byte buffers, with
 * [breakAfter] bytes cutting the connection there. Every call is counted.
 */
class FakeUpdateServer(var manifestText: String = Manifests.json()) : UpdateServer {
    var failing = false
    val files = mutableMapOf<String, ByteArray>()
    var chunk = 1_000
    var breakAfter: Int? = null
    var manifestCalls = 0
        private set
    val downloads = mutableListOf<String>()

    override suspend fun manifest(): String {
        manifestCalls++
        if (failing) throw IOException("Unable to resolve host \"raw.githubusercontent.com\"")
        return manifestText
    }

    override suspend fun download(url: String, cap: Long, sink: (ByteArray, Int) -> Unit) {
        downloads += url
        if (failing) throw IOException("Unable to resolve host \"github.com\"")
        val bytes = files[url] ?: throw IOException("HTTP 404")
        var at = 0
        while (at < bytes.size) {
            val end = minOf(bytes.size, at + chunk)
            breakAfter?.let { if (end > it) throw IOException("Connection reset") }
            val buffer = bytes.copyOfRange(at, end)
            sink(buffer, buffer.size)
            at = end
        }
    }
}
