// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.net

import dev.stevenjin.stevenpiano.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URL
import java.util.Locale

/** What a request carries besides its address: its headers and how long it may take. */
class HttpRequest(val headers: Map<String, String>, val connectTimeoutMs: Int, val readTimeoutMs: Int)

/** One answer from a server, open until it is closed. */
interface HttpExchange : Closeable {
    /** The status code (reading it is what sends the request). */
    val code: Int

    /** A response header, or null. */
    fun header(name: String): String?

    /** The body's declared length in bytes, or -1 when the server did not say. */
    val contentLength: Long

    fun body(): InputStream
}

/** Opens one request to one address; redirects are never followed here ([HttpFetch] follows them, checking each). */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun open(url: String, request: HttpRequest): HttpExchange
}

/** `HttpURLConnection`: the platform's TLS, no caches, and redirects left to [HttpFetch]. */
object UrlConnectionTransport : HttpTransport {
    override fun open(url: String, request: HttpRequest): HttpExchange {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = request.connectTimeoutMs
            readTimeout = request.readTimeoutMs
            instanceFollowRedirects = false
            useCaches = false
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return object : HttpExchange {
            override val code: Int get() = connection.responseCode
            override fun header(name: String): String? = connection.getHeaderField(name)
            override val contentLength: Long get() = connection.contentLengthLong
            override fun body(): InputStream = connection.inputStream
            override fun close() = connection.disconnect()
        }
    }
}

/** A request, or a redirect, to an address the caller's allow-list refuses. Nothing was sent to it. */
class RefusedRequestException(host: String) : IOException("Refused a request to $host")

/**
 * The app's HTTP, shared by the Wikipedia client and the updater: every hop, the first request and
 * each redirect, must pass [allowed] before anything is sent (redirects are followed by hand, at
 * most [MAX_REDIRECTS]); every request carries the app's [USER_AGENT] and, when given, [accept];
 * [connectTimeoutMs] to connect and [readTimeoutMs] between reads. What to do with the answer, and
 * how much of a body to read ([readCapped]), is the caller's: [exchange] hands it the final,
 * non-redirect response and closes it after. Blocking: call it on an I/O thread. [log], when given
 * (debug builds), hears each hop with its wall-clock time; release builds log no address.
 */
class HttpFetch(
    private val allowed: (String) -> Boolean,
    accept: String? = null,
    connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int,
    private val transport: HttpTransport = UrlConnectionTransport,
    userAgent: String = USER_AGENT,
    private val log: ((String) -> Unit)? = null,
) {
    private val request = HttpRequest(
        headers = buildMap {
            put("User-Agent", userAgent)
            if (accept != null) put("Accept", accept)
        },
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
    )

    /**
     * Requests [start], following redirects that pass the allow-list, and gives the final answer to
     * [onAnswer]. Throws [RefusedRequestException] for an address the allow-list refuses (a
     * redirect's included), and IOException for a redirect without a Location, more than
     * [MAX_REDIRECTS] of them, or a failed connection.
     */
    fun <T> exchange(start: String, onAnswer: (HttpExchange) -> T): T {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            if (!allowed(url)) throw RefusedRequestException(hostOf(url))
            log?.invoke("GET $url · User-Agent: ${request.headers["User-Agent"]} · at ${System.currentTimeMillis()} ms")
            transport.open(url, request).use { answer ->
                val code = answer.code
                if (code in REDIRECT_CODES) {
                    val location = answer.header("Location") ?: throw IOException("HTTP $code without a Location")
                    url = URL(URL(url), location).toString()
                    return@repeat
                }
                return onAnswer(answer)
            }
        }
        throw IOException("More than $MAX_REDIRECTS redirects")
    }

    companion object {
        /**
         * Every request says who is asking (Wikimedia answers 403 without it), with the version and
         * where the app is published.
         */
        val USER_AGENT = "StevenPiano/${BuildConfig.VERSION_NAME} (https://github.com/stevenjin20090101-rgb/steven-piano-android)"
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 10_000
        private val REDIRECT_CODES = 300..399

        /** The whole stream, or null once it passes [cap] bytes (nothing past the cap is kept). */
        fun readCapped(input: InputStream, cap: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return out.toByteArray()
                if (out.size() + n > cap) return null
                out.write(buffer, 0, n)
            }
        }

        /** The host of an absolute URL, lower-cased, for messages; "" when there is none or it carries user info. */
        fun hostOf(url: String): String {
            if ('\\' in url) return ""
            val uri = try {
                URI(url)
            } catch (e: URISyntaxException) {
                return ""
            }
            return if (uri.rawUserInfo != null) "" else uri.host?.lowercase(Locale.ROOT).orEmpty()
        }
    }
}
