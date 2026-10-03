// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import dev.stevenjin.stevenpiano.web.WebApi
import dev.stevenjin.stevenpiano.web.securityHeaders
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.util.StringTokenizer

/**
 * A browser's request as the relay carried it, shaped as NanoHTTPD's own session so the web
 * panel's server answers it through its one route table ([dev.stevenjin.stevenpiano.web.WebServer.serveRelayed];
 * BUILD_SPEC.md › v1.10 — M26). [rawPath] and [query] arrive still percent-encoded and are decoded
 * exactly as NanoHTTPD 2.3.1 decodes a request line: the path with `URLDecoder` (UTF-8), the query
 * split on `&` and `=` with each part decoded, a name without `=` taking "". A path that doesn't
 * start with `/`, holds anything but printable ASCII, or whose escapes (or the query's) don't decode
 * reads as no [getUri] (null: the server refuses it 400, as [dev.stevenjin.stevenpiano.web.RequestHead]
 * does), and a method NanoHTTPD doesn't know as no [getMethod] (501). Only the headers the relay
 * forwards are kept ([RelayProtocol.FORWARDED_HEADERS]), their names lower-cased; the cookies come
 * from the `cookie` header. [body] is the request's body ([BodyPipe]), and the address the browser's
 * as the relay saw it. Nothing here parses a body ([parseBody] is not supported: no route reads a form).
 */
class RelayedSession(
    method: String,
    rawPath: String,
    query: String,
    headers: Map<String, String>,
    address: String,
    private val body: InputStream,
) : NanoHTTPD.IHTTPSession {
    private val method: NanoHTTPD.Method? = NanoHTTPD.Method.values().firstOrNull { it.name == method }
    private val headers: Map<String, String> = headers.entries
        .associate { (name, value) -> name.lowercase() to value }
        .filterKeys { it in RelayProtocol.FORWARDED_HEADERS }
    private val address: String = address.takeIf { ADDRESS.matches(it) } ?: UNKNOWN
    private val queryText: String = query
    private val parameters: Map<String, List<String>>?
    private val uri: String?

    init {
        val decodedParameters = decodeParameters(query)
        val decodedPath = if (rawPath.startsWith('/') && rawPath.all { it in ' '..'~' } && query.all { it in ' '..'~' }) decode(rawPath) else null
        parameters = decodedParameters
        uri = if (decodedParameters == null) null else decodedPath
    }

    override fun execute() = throw UnsupportedOperationException("A relayed request is answered through WebServer.serveRelayed")

    override fun getCookies(): NanoHTTPD.CookieHandler = COOKIES.CookieHandler(headers)

    override fun getHeaders(): Map<String, String> = headers

    override fun getInputStream(): InputStream = body

    override fun getMethod(): NanoHTTPD.Method? = method

    @Deprecated("NanoHTTPD's single-value view", ReplaceWith("parameters"))
    override fun getParms(): Map<String, String> = parameters.orEmpty().mapValues { (_, values) -> values.first() }

    override fun getParameters(): Map<String, List<String>> = parameters.orEmpty()

    override fun getQueryParameterString(): String = queryText

    override fun getUri(): String? = uri

    override fun parseBody(files: MutableMap<String, String>?) = throw UnsupportedOperationException("A relayed request's body is read by its route")

    override fun getRemoteIpAddress(): String = address

    override fun getRemoteHostName(): String = address

    private companion object {
        const val UNKNOWN = "unknown"

        /** An address as the relay names it (`CF-Connecting-IP`): IPv4 or IPv6 digits, nothing else. */
        val ADDRESS = Regex("[0-9A-Fa-f:.]{2,45}")

        /** A server never started, only to make NanoHTTPD's cookie handler (an inner class of it) for [getCookies]. */
        val COOKIES = object : NanoHTTPD(0) {}

        fun decode(text: String): String? = try {
            URLDecoder.decode(text, "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }

        /** The query as NanoHTTPD's `decodeParms` reads it; null when an escape doesn't decode. */
        fun decodeParameters(query: String): Map<String, List<String>>? {
            val out = LinkedHashMap<String, MutableList<String>>()
            val parts = StringTokenizer(query, "&")
            while (parts.hasMoreTokens()) {
                val part = parts.nextToken()
                val eq = part.indexOf('=')
                val key = decode(if (eq >= 0) part.substring(0, eq) else part)?.trim() ?: return null
                val value = if (eq >= 0) decode(part.substring(eq + 1)) ?: return null else ""
                out.getOrPut(key) { mutableListOf() } += value
            }
            return out
        }
    }
}

/**
 * One of the server's answers as the relay carries it back (v1.10 — M26): its [status], the
 * [headers] the server sets, each with the value it sends on a listener ([HEADERS]; `Date` and
 * `Connection` are the relay's own), and its whole [body], which every route sends at a fixed length
 * (a page, a script, an image, JSON: well under [MAX_BODY]). [res] is the protocol's head for it;
 * the body follows in chunks.
 */
class RelayedResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun res(id: Long): RelayMessage.Res = RelayMessage.Res(id, status, headers, body.size.toLong())

    companion object {
        /** An answer's body, at most: the largest the server sends is the panel's script or a portrait, far under it. */
        const val MAX_BODY = 16 * 1024 * 1024

        /**
         * Every header the server sets (WebServer's security headers, `Cache-Control`, `Set-Cookie`,
         * `Retry-After`, `Allow`, and since v1.18 (M46) the diagnostics zip's `Content-Disposition`), in the
         * order sent; `Content-Type` and `Content-Length` come from the answer itself. The relay passes on
         * only its own allow-list (`cloud/src/relay/room.ts`, `RESPONSE_HEADERS`), which leaves
         * `Content-Disposition` out: through it the page names the file itself.
         */
        val HEADERS: List<String> = listOf(
            "X-Content-Type-Options",
            "X-Frame-Options",
            "Referrer-Policy",
            "Content-Security-Policy",
            "Cross-Origin-Resource-Policy",
            "Cache-Control",
            "Set-Cookie",
            "Retry-After",
            "Allow",
            "Content-Disposition",
        )

        /** [response] read whole (and closed): its status, its known headers, its body. IOException past [MAX_BODY]. */
        fun write(response: NanoHTTPD.Response): RelayedResponse {
            val body = response.data?.use { readCapped(it) } ?: ByteArray(0)
            val headers = LinkedHashMap<String, String>()
            response.mimeType?.let { headers["Content-Type"] = it }
            for (name in HEADERS) response.getHeader(name)?.let { headers[name] = it }
            headers["Content-Length"] = body.size.toString()
            return RelayedResponse(response.status.requestStatus, headers, body)
        }

        /**
         * An answer the relay client gives itself, before or without the server (503 busy past the
         * requests in flight; 500 when the server failed): the panel's JSON error with the headers
         * the server's own refusals carry, for the relay's [host] over [scheme].
         */
        fun refusal(status: Int, code: String, message: String, host: String, scheme: String): RelayedResponse {
            val body = WebApi.error(code, message).toString().toByteArray(Charsets.UTF_8)
            val headers = LinkedHashMap<String, String>()
            headers["Content-Type"] = JSON
            for ((name, value) in securityHeaders(host, scheme)) headers[name] = value
            headers["Cache-Control"] = "no-store"
            headers["Content-Length"] = body.size.toString()
            return RelayedResponse(status, headers, body)
        }

        private const val JSON = "application/json; charset=utf-8"

        private fun readCapped(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return out.toByteArray()
                if (out.size() + n > MAX_BODY) throw IOException("An answer larger than the relay carries")
                out.write(buffer, 0, n)
            }
        }
    }
}
