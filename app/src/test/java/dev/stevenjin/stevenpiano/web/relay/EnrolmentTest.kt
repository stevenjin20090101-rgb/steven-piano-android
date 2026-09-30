// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import dev.stevenjin.stevenpiano.net.HttpPost
import dev.stevenjin.stevenpiano.net.PostAnswer
import dev.stevenjin.stevenpiano.net.PostTransport
import dev.stevenjin.stevenpiano.net.RefusedRequestException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Enrolling with a code: the address and the code as typed, the one POST, its allow-list and cap, and the relay's answers. */
class EnrolmentTest {
    private class Sent(val url: String, val headers: Map<String, String>, val body: String, val cap: Int)

    private val sent = mutableListOf<Sent>()

    private fun answering(code: Int, body: String?, failure: IOException? = null) = PostTransport { url, headers, bytes, _, _, cap ->
        sent += Sent(url, headers, bytes.toString(Charsets.UTF_8), cap)
        failure?.let { throw it }
        PostAnswer(code, body?.toByteArray())
    }

    private fun enrolment(transport: PostTransport, override: String? = null) =
        Enrolment(override) { allowed -> HttpPost(allowed, transport, userAgent = "StevenPiano/test") }

    @Test
    fun `the relay's address and the code are read as typed, and kept in their one form`() {
        for ((typed, host) in listOf(
            "steven-piano-relay.steven.workers.dev" to "steven-piano-relay.steven.workers.dev",
            "  HTTPS://Relay.Example.dev/ " to "relay.example.dev",
            "relay.test:8443" to "relay.test:8443",
            "10.0.2.2:8787" to "10.0.2.2:8787",
        )) assertEquals(typed, host, CloudAddress.host(typed))
        for (typed in listOf("", "http://relay.example.dev", "relay.example.dev/p/abc", "user@relay.example.dev", "relay example.dev", "-relay.dev", "relay.dev:0", "relay.dev:70000", "relay.dev:", "ftp://relay.dev")) {
            assertNull(typed, CloudAddress.host(typed))
        }
        for (typed in listOf("abcd efgh", "ABCD-EFGH", "abcdefgh", " ab-cd-ef-gh ")) assertEquals(typed, "ABCD-EFGH", CloudAddress.code(typed))
        for (typed in listOf("", "ABCD-EFG", "ABCD-EFGHJ", "ABCD-EFG1", "ABCD-EFGO", "ABCD_EFGH", "x".repeat(40))) assertNull(typed, CloudAddress.code(typed))
        assertEquals("wss://relay.example.dev/tablet", CloudAddress.socketUrl(CloudAddress.origin("relay.example.dev", null)))
        assertEquals("ws://10.0.2.2:8787/tablet", CloudAddress.socketUrl(CloudAddress.origin("relay.example.dev", "http://10.0.2.2:8787")))
        assertEquals("https", CloudAddress.scheme("https://relay.example.dev"))
        assertEquals("http", CloudAddress.scheme("http://10.0.2.2:8787"))
    }

    @Test
    fun `one POST of the code alone, to the typed host's enrol address, and the id and secret come back`() {
        val secret = "s".repeat(43)
        val result = enrolment(answering(200, """{"pianoId":"abcdefgh2345","secret":"$secret","host":"relay.example.dev","panelUrl":"https://relay.example.dev/p/abcdefgh2345/"}"""))
            .enrol("Relay.Example.dev", "abcd efgh")
        assertTrue(result.toString(), result is EnrolResult.Enrolled)
        result as EnrolResult.Enrolled
        assertEquals("relay.example.dev", result.host)
        assertEquals("abcdefgh2345", result.pianoId)
        assertEquals(secret, result.secret)
        assertFalse("the secret never prints", result.toString().contains(secret))
        val post = sent.single()
        assertEquals("https://relay.example.dev/api/enrol", post.url)
        assertEquals(setOf("code"), JSONObject(post.body).keys().asSequence().toSet())
        assertEquals("ABCD-EFGH", JSONObject(post.body).getString("code"))
        assertEquals("application/json; charset=utf-8", post.headers["Content-Type"])
        assertEquals("StevenPiano/test", post.headers["User-Agent"])
        assertEquals("16 KB of answer at most", 16 * 1024, post.cap)
    }

    @Test
    fun `the relay's refusals, a failed connection and an answer it can't use come back in plain words, and a typo sends nothing`() {
        fun enrol(code: Int, body: String?, failure: IOException? = null) = enrolment(answering(code, body, failure)).enrol("relay.example.dev", "ABCD-EFGH")
        assertEquals(EnrolResult.Refused("That code isn't right, or it has expired. Make a new one in the console."), enrol(404, """{"error":"code"}"""))
        assertEquals(EnrolResult.Refused("Too many tries. Try again in a minute."), enrol(429, """{"error":"wait"}"""))
        assertEquals(EnrolResult.Refused(Enrolment.NOT_A_CODE), enrol(400, "{}"))
        assertEquals("a redirect is not followed", EnrolResult.Refused("The relay answered 302. Try again in a moment."), enrol(302, ""))
        assertEquals(EnrolResult.Refused(Enrolment.UNREACHABLE), enrol(0, null, IOException("Connection refused")))
        for (body in listOf(null, "not json", """{"pianoId":"ABC","secret":"${"s".repeat(43)}"}""", """{"pianoId":"abcdefgh2345","secret":"short"}""", """{"pianoId":"abcdefgh2345","secret":{"a":{"b":1}}}""")) {
            assertEquals(body.toString(), EnrolResult.Refused(Enrolment.UNUSABLE), enrol(200, body))
        }
        val before = sent.size
        assertEquals(EnrolResult.Refused(Enrolment.NOT_AN_ADDRESS), enrolment(answering(200, "{}")).enrol("http://relay.example.dev", "ABCD-EFGH"))
        assertEquals(EnrolResult.Refused(Enrolment.NOT_A_CODE), enrolment(answering(200, "{}")).enrol("relay.example.dev", "ABCD-EFG1"))
        assertEquals("nothing was sent for a typo", before, sent.size)
    }

    @Test
    fun `only the typed host's enrol address is allowed, over TLS, and the debug stand-in's alone when it is set`() {
        val enrolment = enrolment(answering(200, "{}"))
        assertTrue(enrolment.allows("https://relay.example.dev/api/enrol", "relay.example.dev"))
        assertTrue(enrolment.allows("https://relay.test:8443/api/enrol", "relay.test:8443"))
        for (url in listOf(
            "http://relay.example.dev/api/enrol",
            "https://evil.example/api/enrol",
            "https://relay.example.dev.evil.example/api/enrol",
            "https://relay.example.dev:8443/api/enrol",
            "https://relay.example.dev/api/enrol?x=1",
            "https://user@relay.example.dev/api/enrol",
            "https://relay.example.dev/p/abc",
            "https://relay.example.dev\\@evil.example/api/enrol",
        )) assertFalse(url, enrolment.allows(url, "relay.example.dev"))
        val local = enrolment(answering(200, "{}"), override = "http://10.0.2.2:8787")
        assertTrue(local.allows("http://10.0.2.2:8787/api/enrol", "relay.example.dev"))
        assertFalse(local.allows("https://relay.example.dev/api/enrol", "relay.example.dev"))
        local.enrol("relay.example.dev", "ABCD-EFGH")
        assertEquals("the stand-in takes the enrolment", "http://10.0.2.2:8787/api/enrol", sent.last().url)
        assertTrue(runCatching { HttpPost({ false }, answering(200, "{}")).postJson("https://relay.example.dev/api/enrol", "{}") }.exceptionOrNull() is RefusedRequestException)
    }
}
