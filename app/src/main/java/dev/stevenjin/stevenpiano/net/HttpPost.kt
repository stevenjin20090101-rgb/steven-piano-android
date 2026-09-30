// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.net

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One POST's answer: its status and its body, cut off past the caller's cap (null then). */
class PostAnswer(val code: Int, val body: ByteArray?)

/** Sends one POST of [body] to [url] with [headers]; never follows a redirect. */
fun interface PostTransport {
    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, body: ByteArray, connectTimeoutMs: Int, readTimeoutMs: Int, cap: Int): PostAnswer
}

/** `HttpURLConnection`: the platform's TLS, no caches, no redirects followed. */
object UrlConnectionPost : PostTransport {
    override fun post(url: String, headers: Map<String, String>, body: ByteArray, connectTimeoutMs: Int, readTimeoutMs: Int, cap: Int): PostAnswer {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false
            useCaches = false
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream: InputStream? = if (code >= 400) connection.errorStream else connection.inputStream
            val answer = stream?.use { HttpFetch.readCapped(it, cap) } ?: ByteArray(0)
            return PostAnswer(code, answer)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * A small HTTPS POST beside [HttpFetch] (v1.10 — M26: the tablet's enrolment with Steven Piano
 * Cloud): the address must pass [allowed] before anything is sent ([RefusedRequestException]
 * otherwise); JSON goes out with the app's user agent; a redirect is an answer like any other, never
 * followed; at most [cap] bytes of the answer are read. Blocking: call it on an I/O thread.
 */
class HttpPost(
    private val allowed: (String) -> Boolean,
    private val transport: PostTransport = UrlConnectionPost,
    private val cap: Int = DEFAULT_CAP,
    private val connectTimeoutMs: Int = HttpFetch.CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val userAgent: String = HttpFetch.USER_AGENT,
) {
    fun postJson(url: String, json: String): PostAnswer {
        if (!allowed(url)) throw RefusedRequestException(HttpFetch.hostOf(url))
        val headers = mapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Accept" to "application/json",
            "User-Agent" to userAgent,
        )
        return transport.post(url, headers, json.toByteArray(Charsets.UTF_8), connectTimeoutMs, readTimeoutMs, cap)
    }

    companion object {
        /** An answer's body, at most: an enrolment's is a few hundred bytes. */
        const val DEFAULT_CAP = 16 * 1024
        const val READ_TIMEOUT_MS = 15_000
    }
}
