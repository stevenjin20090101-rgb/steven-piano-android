// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.net

import dev.stevenjin.stevenpiano.update.UpdateSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Scripted answers by URL (anything unscripted answers 404); every request opened is recorded with its headers. */
class FakeTransport : HttpTransport {
    class Answer(val code: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0), val declared: Long = body.size.toLong())

    val answers = mutableMapOf<String, Answer>()
    val opened = mutableListOf<Pair<String, HttpRequest>>()
    var closed = 0

    fun redirect(from: String, to: String, code: Int = 302) = apply { answers[from] = Answer(code, mapOf("Location" to to)) }

    fun ok(url: String, body: ByteArray, declared: Long = body.size.toLong()) = apply { answers[url] = Answer(200, body = body, declared = declared) }

    override fun open(url: String, request: HttpRequest): HttpExchange {
        opened += url to request
        val answer = answers[url] ?: Answer(404)
        return object : HttpExchange {
            override val code: Int = answer.code
            override fun header(name: String): String? = answer.headers[name]
            override val contentLength: Long = answer.declared
            override fun body(): InputStream = ByteArrayInputStream(answer.body)
            override fun close() {
                closed++
            }
        }
    }
}

/** The shared HTTP path (v1.4): every hop, redirects included, passes the caller's allow-list before anything is sent. */
class HttpFetchTest {
    private val transport = FakeTransport()
    private val updates = HttpFetch(UpdateSource.production::allowsHop, "*/*", readTimeoutMs = 30_000, transport = transport)
    private val release = "https://github.com/stevenjin20090101-rgb/steven-piano-android/releases/download/v1.4/steven-piano-1.4.apk"
    private val asset = "https://objects.githubusercontent.com/github-production-release-asset-2e65be/123?X-Amz-Signature=abc&response-content-disposition=attachment"

    @Test
    fun `a release download follows GitHub's redirect to its asset host, with the User-Agent on every hop`() {
        transport.redirect(release, asset).ok(asset, byteArrayOf(1, 2, 3))
        val body = updates.exchange(release) { answer -> answer.body().readBytes() }
        assertArrayEquals(byteArrayOf(1, 2, 3), body)
        assertEquals(listOf(release, asset), transport.opened.map { it.first })
        transport.opened.forEach { (_, request) ->
            assertEquals(HttpFetch.USER_AGENT, request.headers["User-Agent"])
            assertEquals("*/*", request.headers["Accept"])
            assertEquals(30_000, request.readTimeoutMs)
            assertEquals(10_000, request.connectTimeoutMs)
        }
        assertEquals(2, transport.closed)
        assertTrue(HttpFetch.USER_AGENT.startsWith("StevenPiano/"))
    }

    @Test
    fun `a redirect off the allow-list is refused before anything is sent to it`() {
        for (target in listOf(
            "https://evil.example/steven-piano-1.4.apk",
            "http://objects.githubusercontent.com/x",   // not HTTPS
            "https://objects.githubusercontent.com:8443/x",
            "https://github.com/someone-else/app/releases/download/v1/app.apk",
            "https://evil.example\\@objects.githubusercontent.com/x",
        )) {
            transport.opened.clear()
            transport.redirect(release, target)
            try {
                updates.exchange(release) { fail("followed $target") }
                fail("no refusal for $target")
            } catch (e: RefusedRequestException) {
                assertEquals(listOf(release), transport.opened.map { it.first })
            }
        }
    }

    @Test
    fun `a start address off the allow-list opens nothing`() {
        try {
            updates.exchange("https://example.com/latest.json") { fail() }
            fail()
        } catch (e: RefusedRequestException) {
            assertEquals("Refused a request to example.com", e.message)
        }
        assertTrue(transport.opened.isEmpty())
    }

    @Test
    fun `relative redirects resolve against the hop they came from`() {
        val wiki = HttpFetch(WikipediaUrls::allowed, "application/json", readTimeoutMs = 15_000, transport = transport)
        val start = "https://en.wikipedia.org/api/rest_v1/page/summary/Chopin"
        val moved = "https://en.wikipedia.org/api/rest_v1/page/summary/Fr%C3%A9d%C3%A9ric_Chopin"
        transport.redirect(start, "Fr%C3%A9d%C3%A9ric_Chopin", code = 301).ok(moved, "{}".toByteArray())
        assertEquals("{}", wiki.exchange(start) { String(it.body().readBytes()) })
        assertEquals(listOf(start, moved), transport.opened.map { it.first })
    }

    @Test
    fun `five redirects are followed, a sixth is not`() {
        val hops = (0..6).map { "https://objects.githubusercontent.com/hop$it" }
        for (i in 0 until 6) transport.redirect(hops[i], hops[i + 1])
        transport.ok(hops[5], byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), updates.exchange(hops[0]) { it.body().readBytes() })
        transport.ok(hops[6], byteArrayOf(9))
        transport.redirect(hops[5], hops[6])
        try {
            updates.exchange(hops[0]) { fail() }
            fail()
        } catch (e: IOException) {
            assertEquals("More than 5 redirects", e.message)
        }
    }

    @Test
    fun `a redirect without a Location is a failure`() {
        transport.answers[release] = FakeTransport.Answer(302)
        try {
            updates.exchange(release) { fail() }
            fail()
        } catch (e: IOException) {
            assertEquals("HTTP 302 without a Location", e.message)
        }
    }

    @Test
    fun `bodies are read up to their cap and no further`() {
        assertArrayEquals(ByteArray(10), HttpFetch.readCapped(ByteArrayInputStream(ByteArray(10)), 10))
        assertNull(HttpFetch.readCapped(ByteArrayInputStream(ByteArray(11)), 10))
    }
}
