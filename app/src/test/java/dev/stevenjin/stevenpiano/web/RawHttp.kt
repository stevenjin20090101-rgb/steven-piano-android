// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * HTTP by hand, for the server's tests: every header exactly as written (`Host`, `Origin` and
 * `Content-Length` included, which the JDK's clients will not let a caller set), and every
 * response read to the end (the server closes each connection), its headers kept as sent.
 */
class RawHttp(private val port: Int, private val host: String = "127.0.0.1:$port") {
    /** A response: its status, its headers (names lower-cased, every value kept), its body. */
    class Answer(val status: Int, val headers: List<Pair<String, String>>, val body: ByteArray) {
        fun header(name: String): String? = headers.lastOrNull { it.first == name.lowercase() }?.second

        fun all(name: String): List<String> = headers.filter { it.first == name.lowercase() }.map { it.second }

        val text: String get() = body.toString(Charsets.UTF_8)

        fun json(): JSONObject = JSONObject(text)

        /** The value a `Set-Cookie` gives [name], or null. */
        fun cookie(name: String): String? = all("set-cookie").firstOrNull { it.startsWith("$name=") }?.substringAfter('=')?.substringBefore(';')

        override fun toString(): String = "$status ${headers.joinToString()} ${text.take(200)}"
    }

    /**
     * Sends [method] [path] with [headers] (a `Host` unless [headers] name one; `Content-Length`
     * for a [body] unless [lengthHeader] is false) and reads the whole answer.
     */
    fun send(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        lengthHeader: Boolean = true,
        readTimeoutMs: Int = 15_000,
    ): Answer {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = readTimeoutMs
            // Head and body in one write, as a browser's small request arrives: nothing is left unread by a refusal.
            socket.getOutputStream().write(head(method, path, body, headers, lengthHeader) + (body ?: ByteArray(0)))
            socket.getOutputStream().flush()
            return read(socket.getInputStream())
        }
    }

    fun head(method: String, path: String, body: ByteArray?, headers: Map<String, String>, lengthHeader: Boolean = true): ByteArray {
        val lines = StringBuilder("$method $path HTTP/1.1\r\n")
        if (headers.keys.none { it.equals("Host", ignoreCase = true) }) lines.append("Host: $host\r\n")
        for ((name, value) in headers) lines.append("$name: $value\r\n")
        if (body != null && lengthHeader && headers.keys.none { it.equals("Content-Length", ignoreCase = true) }) lines.append("Content-Length: ${body.size}\r\n")
        lines.append("\r\n")
        return lines.toString().toByteArray(Charsets.ISO_8859_1)
    }

    fun get(path: String, headers: Map<String, String> = emptyMap()): Answer = send("GET", path, headers = headers)

    /** A JSON request from the panel: the header, the type, the session when given. */
    fun api(method: String, path: String, json: String? = "{}", session: String? = null, panel: Boolean = true, extra: Map<String, String> = emptyMap()): Answer {
        val headers = LinkedHashMap<String, String>()
        if (json != null) headers["Content-Type"] = "application/json"
        if (panel) headers["X-Steven-Piano"] = "1"
        if (session != null) headers["Cookie"] = "sp_session=$session"
        headers.putAll(extra)
        return send(method, path, json?.toByteArray(Charsets.UTF_8), headers)
    }

    companion object {
        /** A whole response from [input]: status line, headers, then the body to the end of the stream. */
        fun read(input: InputStream): Answer {
            val all = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = try {
                    input.read(buffer)
                } catch (e: java.net.SocketException) {
                    -1   // a reset after the answer: what arrived is the answer
                }
                if (n < 0) break
                all.write(buffer, 0, n)
            }
            val bytes = all.toByteArray()
            val end = indexOf(bytes, "\r\n\r\n".toByteArray())
            require(end >= 0) { "No complete response: ${String(bytes, Charsets.ISO_8859_1).take(200)}" }
            val head = String(bytes, 0, end, Charsets.ISO_8859_1).split("\r\n")
            val status = head.first().split(' ')[1].toInt()
            val headers = head.drop(1).map { line -> line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim() }
            return Answer(status, headers, bytes.copyOfRange(end + 4, bytes.size))
        }

        fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
