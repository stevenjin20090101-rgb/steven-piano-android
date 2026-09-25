// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.net

import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Wikipedia for tests: [pages] by exact title, [searches] by exact query, [files] by URL. Every
 * call is recorded in [calls] with the (virtual) time it started and ended, taking [latencyMs].
 * [failing] makes calls throw as a dead connection does; each entry of [busy] makes one call
 * answer 429 with that Retry-After.
 */
class FakeWikipedia(private val now: () -> Long = { 0L }, private val latencyMs: Long = 0) : WikiApi {
    data class Call(val kind: String, val arg: String, val startedAt: Long, val endedAt: Long)

    val pages = mutableMapOf<String, WikiSummary>()
    val searches = mutableMapOf<String, List<String>>()
    val files = mutableMapOf<String, ByteArray>()
    val busy = ArrayDeque<Long?>()
    var failing = false

    /** Runs as a call starts: a test can change the world mid-fetch (going offline, say). */
    var onCall: (Call) -> Unit = {}
    private val log = mutableListOf<Call>()
    val calls: List<Call> get() = log.toList()
    val kinds: List<String> get() = log.map { "${it.kind} ${it.arg}" }

    fun page(
        title: String,
        extract: String? = "$title is a page.",
        type: String = "standard",
        description: String? = null,
        image: String? = null,
    ): FakeWikipedia = apply {
        pages[title] = WikiSummary(title, type, description, extract, "https://en.wikipedia.org/wiki/${title.replace(' ', '_')}", image)
        image?.let { files[it] = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3) }
    }

    override suspend fun summary(title: String): WikiSummary? = call("summary", title) { pages[title] }

    override suspend fun search(query: String): List<String> = call("search", query) { searches[query].orEmpty() }

    override suspend fun download(url: String, maxBytes: Int): ByteArray? = call("download", url) { files[url]?.takeIf { it.size <= maxBytes } }

    private suspend fun <T> call(kind: String, arg: String, answer: () -> T): T {
        val start = now()
        onCall(Call(kind, arg, start, start))
        if (latencyMs > 0) delay(latencyMs)
        log += Call(kind, arg, start, now())
        if (failing) throw IOException("Unable to resolve host \"en.wikipedia.org\"")
        if (busy.isNotEmpty()) throw WikiBusyException(429, busy.removeFirst())
        return answer()
    }
}
