// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import dev.stevenjin.stevenpiano.web.AssetSource
import dev.stevenjin.stevenpiano.web.FakeWebBackend
import dev.stevenjin.stevenpiano.web.GuestRequests
import dev.stevenjin.stevenpiano.web.GuestSettings
import dev.stevenjin.stevenpiano.web.LoginGuard
import dev.stevenjin.stevenpiano.web.PinHash
import dev.stevenjin.stevenpiano.web.Poster
import dev.stevenjin.stevenpiano.web.RawHttp
import dev.stevenjin.stevenpiano.web.Sessions
import dev.stevenjin.stevenpiano.web.WebAssets
import dev.stevenjin.stevenpiano.web.WebServer
import dev.stevenjin.stevenpiano.web.WebSockets
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream

/**
 * The relay's way in (BUILD_SPEC.md › v1.10 — M26): every route answered through
 * [WebServer.serveRelayed] carries the same status and the same headers as over a listener, read
 * over [RawHttp], but for what depends on the edge (the socket's `wss://relay.test` in the policy,
 * the cookies' path and `Secure`); and the relay's own rules: its host alone, its origin, its
 * prefix, the guests' pages only while Guests can request is on.
 */
class WebServerRelayTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private lateinit var backend: FakeWebBackend
    private val sessions = Sessions(clock = { now })
    private val guard = LoginGuard(clock = { now })
    private val requests = GuestRequests(clock = { now })
    private val files = mapOf(
        "index.html" to "<!doctype html><title>Panel</title>",
        "app.js" to "/* the panel */",
        "style.css" to ":root { }",
        "request.html" to "<!doctype html><title>Ask the piano</title>",
        "request.js" to "/* the request page */",
        "poster.html" to "<!doctype html><p>{{URL}}</p>{{QR}}",
    )
    private val assets = AssetSource { name -> files[name]?.toByteArray() }
    private val sockets = object : WebSockets {
        override fun hasRoom(): Boolean = false

        override fun open(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket = error("No socket here")
    }
    private lateinit var lan: WebServer
    private lateinit var relay: WebServer
    private lateinit var http: RawHttp
    private val port: Int get() = lan.listeningPort

    @Before
    fun setUp() {
        backend = FakeWebBackend(tmp.newFolder("uploads"))
        val poster: suspend (String) -> ByteArray? = { url -> Poster.page(assets.read(WebAssets.POSTER.name), url) }
        lan = WebServer(WebServer.Config("127.0.0.1", 0, tempDir = tmp.newFolder()), backend, sessions, guard, requests, assets, sockets, poster)
        lan.startListening()
        http = RawHttp(lan.listeningPort)
        // As the web service keeps it: the same sessions, guard, requests and backend, never listening.
        relay = WebServer(WebServer.Config(RELAY_CONFIG_HOST, 0, tempDir = tmp.newFolder()), backend, sessions, guard, requests, assets, sockets, poster)
    }

    @After
    fun tearDown() {
        lan.stop()
    }

    @Test
    fun `every route answers the relay with the status and headers it gives a listener`() {
        val routes = lan.routes
        assertEquals("the whole table", relay.routes.map { "${it.method} ${it.sample}" }, routes.map { "${it.method} ${it.sample}" })
        for (route in routes) {
            val method = route.method.name
            val path = route.sample
            val body = if (path.startsWith("/api/upload")) "0123456789" else "{}"
            val what = "$method $path"
            val (lanAnswer, relayed) = if (method == "GET") {
                http.get(path, mapOf("Cookie" to "sp_session=${sessions.open()}")) to
                    relayed(method, path, headers = mapOf("cookie" to "sp_session=${sessions.open()}"))
            } else {
                http.api(method, path, body, session = sessions.open(), extra = mapOf("Origin" to "http://127.0.0.1:$port")) to
                    relayed(method, path, body.toByteArray(), panelHeaders(sessions.open()))
            }
            assertSameAnswer(what, lanAnswer, relayed)
        }
    }

    @Test
    fun `the pages, the refusals and the waits carry the same headers too`() {
        val pairs = listOf(
            "GET /" to (http.get("/") to relayed("GET", "/")),
            "GET /app.js" to (http.get("/app.js") to relayed("GET", "/app.js")),
            "GET /style.css" to (http.get("/style.css") to relayed("GET", "/style.css")),
            "GET /request" to (http.get("/request", mapOf("Cookie" to "sp_guest=${"G".repeat(22)}")) to relayed("GET", "/request", headers = mapOf("cookie" to "sp_guest=${"G".repeat(22)}"))),
            "GET /request.js" to (http.get("/request.js") to relayed("GET", "/request.js")),
            "GET /poster" to (http.get("/poster") to relayed("GET", "/poster")),
            "GET /nothing" to (http.get("/nothing/here") to relayed("GET", "/nothing/here")),
            "POST /app.js" to (http.send("POST", "/app.js", "x".toByteArray()) to relayed("POST", "/app.js", "x".toByteArray())),
            "GET /api/state no session" to (http.get("/api/state") to relayed("GET", "/api/state")),
            "PATCH schedule" to (http.api("PATCH", "/api/schedules/1", "{}", session = sessions.open()) to relayed("PATCH", "/api/schedules/1", "{}".toByteArray(), panelHeaders(sessions.open(), origin = null))),
            "POST play without the header" to (
                http.api("POST", "/api/play", """{"pieceId":1}""", session = sessions.open(), panel = false) to
                    relayed("POST", "/api/play", """{"pieceId":1}""".toByteArray(), panelHeaders(sessions.open(), origin = null) - "x-steven-piano")
                ),
            "GET library bad limit" to (http.get("/api/library?limit=500", mapOf("Cookie" to "sp_session=${sessions.open()}")) to relayed("GET", "/api/library?limit=500", headers = mapOf("cookie" to "sp_session=${sessions.open()}"))),
        )
        for ((what, pair) in pairs) assertSameAnswer(what, pair.first, pair.second)

        // The login lock's wait: Retry-After over both.
        backend.pin = PIN
        repeat(5) { http.api("POST", "/api/login", """{"pin":"000000"}""") }
        val lanWait = http.api("POST", "/api/login", """{"pin":"482913"}""")
        repeat(5) { relayed("POST", "/api/login", """{"pin":"000000"}""".toByteArray(), panelHeaders(null)) }
        val relayWait = relayed("POST", "/api/login", """{"pin":"482913"}""".toByteArray(), panelHeaders(null))
        assertEquals(429, lanWait.status)
        assertSameAnswer("the login lock", lanWait, relayWait)
        assertEquals("30", relayWait.headers["Retry-After"])
    }

    @Test
    fun `only the relay's own host, and its own https origin, are answered`() {
        val token = sessions.open()
        for (host in listOf("relay.test:443", "RELAY.TEST.evil.example", "127.0.0.1:$port", "evil.example", "")) {
            val answer = relayed("GET", "/api/state", headers = mapOf("host" to host, "cookie" to "sp_session=$token"))
            assertEquals(host, 403, answer.status)
            assertEquals("the policy names the relay's socket", SOCKET_RELAY, socketOf(answer.headers["Content-Security-Policy"]!!))
        }
        assertEquals("the host in any case", 200, relayed("GET", "/api/state", headers = mapOf("host" to "Relay.Test", "cookie" to "sp_session=$token")).status)
        for (origin in listOf("http://relay.test", "https://evil.example", "http://127.0.0.1:$port", "https://relay.test:443")) {
            val play = relayed("POST", "/api/play", """{"pieceId":1}""".toByteArray(), panelHeaders(token, origin = origin))
            assertEquals(origin, 403, play.status)
            assertEquals("origin", JSONObject(String(play.body)).getString("error"))
        }
        assertEquals(204, relayed("POST", "/api/play", """{"pieceId":1}""".toByteArray(), panelHeaders(token)).status)
        assertEquals("the listener refuses the relay's origin", 403, http.api("POST", "/api/play", """{"pieceId":1}""", session = token, extra = mapOf("Origin" to "https://relay.test")).status)
        assertEquals("and the relay's host", 403, http.get("/api/state", mapOf("Host" to "relay.test", "Cookie" to "sp_session=$token")).status)
        assertEquals(listOf("play 1 queue=null"), backend.calls.toList())
    }

    @Test
    fun `the relay's cookies are Secure and live under the piano's prefix`() {
        backend.pin = PIN
        val login = relayed("POST", "/api/login", """{"pin":"482913"}""".toByteArray(), panelHeaders(null))
        assertEquals(204, login.status)
        val cookie = login.headers["Set-Cookie"]!!
        assertTrue(cookie, Regex("sp_session=[A-Za-z0-9_-]{43}; HttpOnly; SameSite=Strict; Path=/p/abcdefgh2345/; Secure").matches(cookie))
        val token = cookie.substringAfter('=').substringBefore(';')
        assertEquals(200, relayed("GET", "/api/state", headers = mapOf("cookie" to "sp_session=$token")).status)
        val out = relayed("POST", "/api/logout", "{}".toByteArray(), panelHeaders(token))
        assertEquals("sp_session=; HttpOnly; SameSite=Strict; Path=/p/abcdefgh2345/; Max-Age=0; Secure", out.headers["Set-Cookie"])
        assertEquals(401, relayed("GET", "/api/state", headers = mapOf("cookie" to "sp_session=$token")).status)
        val guest = relayed("GET", "/request").headers["Set-Cookie"]!!
        assertTrue(guest, Regex("sp_guest=[A-Za-z0-9_-]{22}; HttpOnly; SameSite=Strict; Path=/p/abcdefgh2345/; Max-Age=31536000; Secure").matches(guest))
        val lanCookie = http.api("POST", "/api/login", """{"pin":"482913"}""").all("set-cookie").single()
        assertTrue("a listener's stay as they were: $lanCookie", lanCookie.endsWith("; HttpOnly; SameSite=Strict; Path=/"))
        // The debug build's local relay is plain HTTP: its origin is http, its cookies not Secure, its socket ws.
        val local = relay.serveRelayed(session("POST", "/api/login", """{"pin":"482913"}""".toByteArray(), panelHeaders(null, origin = "http://localhost:8787") + ("host" to "localhost:8787")), "localhost:8787", PREFIX, WebServer.HTTP)
        val plain = RelayedResponse.write(local)
        assertEquals(204, plain.status)
        assertFalse(plain.headers["Set-Cookie"]!!.contains("Secure"))
        assertEquals("ws://localhost:8787", socketOf(plain.headers["Content-Security-Policy"]!!))
    }

    @Test
    fun `over the relay the guests' pages and API exist only while guests may request`() {
        backend.guests = GuestSettings(open = false, approveFirst = true)
        for ((method, path) in listOf("GET" to "/request", "GET" to "/request.js", "GET" to "/poster", "GET" to "/api/public/catalogue", "POST" to "/api/public/request")) {
            val answer = relayed(method, path, if (method == "POST") """{"pieceId":1}""".toByteArray() else null, mapOf("content-type" to "application/json", "origin" to ORIGIN))
            assertEquals("$method $path closed", 404, answer.status)
            assertEquals("nosniff", answer.headers["X-Content-Type-Options"])
            assertNull("no guest id handed out", answer.headers["Set-Cookie"])
        }
        assertEquals("the panel's style is not a guests' page", 200, relayed("GET", "/style.css").status)
        assertEquals("a listener still says it is closed", 200, http.get("/api/public/catalogue").status)
        assertEquals(emptyList<String>(), backend.calls.toList())
        backend.guests = GuestSettings(open = true, approveFirst = false)
        assertEquals(200, relayed("GET", "/request").status)
        assertEquals(200, relayed("GET", "/api/public/catalogue").status)
        val asked = relayed("POST", "/api/public/request", """{"pieceId":1}""".toByteArray(), mapOf("content-type" to "application/json", "origin" to ORIGIN))
        assertEquals(202, asked.status)
        assertEquals(listOf("requested 1"), backend.calls.toList())
        assertEquals("a guest's request from another site", 403, relayed("POST", "/api/public/request", """{"pieceId":2}""".toByteArray(), mapOf("content-type" to "application/json", "origin" to "https://evil.example")).status)
    }

    @Test
    fun `what NanoHTTPD would refuse before a route is refused over the relay too`() {
        val token = sessions.open()
        val cases = listOf(
            session("BREW", "/", null, mapOf("cookie" to "sp_session=$token")) to 501,
            session("GET", "/api/composers/%zz", null, mapOf("cookie" to "sp_session=$token")) to 400,
            session("GET", "/api/library", null, mapOf("cookie" to "sp_session=$token"), query = "q=%E") to 400,
            session("GET", "api/state", null, mapOf("cookie" to "sp_session=$token")) to 400,
            session("GET", "/api/composers/débussy", null, mapOf("cookie" to "sp_session=$token")) to 400,
        )
        for ((session, status) in cases) {
            val answer = RelayedResponse.write(relay.serveRelayed(session, RELAY_HOST, PREFIX))
            assertEquals(session.uri.toString(), status, answer.status)
            assertEquals(SOCKET_RELAY, socketOf(answer.headers["Content-Security-Policy"]!!))
            assertEquals("no-store", answer.headers["Cache-Control"])
        }
        val decoded = relayed("GET", "/api/composers/debussy", headers = mapOf("cookie" to "sp_session=$token"))
        assertEquals("an escape decodes as on a listener", 200, decoded.status)
        val search = relayed("GET", "/api/library?q=clair%20de&limit=2", headers = mapOf("cookie" to "sp_session=$token"))
        assertEquals(1, JSONObject(String(search.body)).getInt("total"))
        assertEquals(1, relayed("GET", "/api/library?q=clair+de", headers = mapOf("cookie" to "sp_session=$token")).let { JSONObject(String(it.body)).getInt("total") })
        val relayedSession = session("GET", "/api/state", null, mapOf("Cookie" to "sp_session=$token", "Upgrade" to "websocket", "Connection" to "Upgrade", "X-Relay-Address" to "1.2.3.4"))
        assertEquals("only the forwarded headers are kept", setOf("host", "cookie"), relayedSession.headers.keys)
        assertEquals(token, relayedSession.cookies.read("sp_session"))
        assertEquals("203.0.113.9", relayedSession.remoteIpAddress)
        assertEquals("unknown", RelayedSession("GET", "/", "", emptyMap(), "evil\r\nhost", ByteArrayInputStream(ByteArray(0))).remoteIpAddress)
    }

    @Test
    fun `an upload over the relay reaches the app as over a listener`() {
        val token = sessions.open()
        val midi = ByteArray(2_000) { it.toByte() }
        val answer = relayed("PUT", "/api/upload?name=..%2FChopin%20-%20Nocturne.MID", midi, panelHeaders(token))
        assertEquals(202, answer.status)
        assertEquals("Chopin - Nocturne.MID", JSONObject(String(answer.body)).getString("name"))
        assertEquals(listOf("midi Chopin - Nocturne.MID 2000"), backend.imported.toList())
        val tooLarge = relayed("PUT", "/api/upload?name=big.zip", null, panelHeaders(token) + ("content-length" to "${64L * 1024 * 1024 + 1}"))
        assertEquals("refused on its length alone", 413, tooLarge.status)
    }

    @Test
    fun `a socket is admitted for a session from the panel's own origin only`() {
        val token = sessions.open()
        val cookies = mapOf("sp_session" to token)
        assertEquals(WebServer.SOCKET_ADMITTED, relay.admitSocket(cookies, ORIGIN, ORIGIN))
        assertEquals(401, relay.admitSocket(emptyMap(), ORIGIN, ORIGIN))
        assertEquals(401, relay.admitSocket(mapOf("sp_session" to "A".repeat(43)), ORIGIN, ORIGIN))
        assertEquals(403, relay.admitSocket(cookies, null, ORIGIN))
        assertEquals(403, relay.admitSocket(cookies, "http://relay.test", ORIGIN))
        sessions.close(token)
        assertEquals(401, relay.admitSocket(cookies, ORIGIN, ORIGIN))
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private fun panelHeaders(token: String?, origin: String? = ORIGIN): Map<String, String> = buildMap {
        put("content-type", "application/json")
        put("x-steven-piano", "1")
        if (token != null) put("cookie", "sp_session=$token")
        if (origin != null) put("origin", origin)
    }

    private fun session(method: String, path: String, body: ByteArray?, headers: Map<String, String> = emptyMap(), query: String? = null): RelayedSession {
        val raw = path.substringBefore('?')
        val q = query ?: if ('?' in path) path.substringAfter('?') else ""
        val all = LinkedHashMap<String, String>()
        all["host"] = RELAY_HOST
        for ((name, value) in headers) all[name.lowercase()] = value
        if (body != null && "content-length" !in all) all["content-length"] = body.size.toString()
        return RelayedSession(method, raw, q, all, "203.0.113.9", ByteArrayInputStream(body ?: ByteArray(0)))
    }

    private fun relayed(method: String, path: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap()): RelayedResponse =
        RelayedResponse.write(relay.serveRelayed(session(method, path, body, headers), RELAY_HOST, PREFIX))

    /**
     * The same status and headers, the edge's own values aside: the policy's socket, and a cookie's
     * value, path and `Secure` (checked on their own). Every header the listener sent, `Date` and
     * `Connection` aside, is one the relay carries. A GET's body is the same too.
     */
    private fun assertSameAnswer(what: String, lanAnswer: RawHttp.Answer, relayed: RelayedResponse) {
        assertEquals("$what: status", lanAnswer.status, relayed.status)
        val sent = lanAnswer.headers.filter { it.first !in setOf("date", "connection") }
        val carried = RelayedResponse.HEADERS.map { it.lowercase() } + listOf("content-type", "content-length")
        for ((name, _) in sent) assertTrue("$what: the listener's $name is carried", name in carried)
        val lanHeaders = sent.associate { (name, value) -> name to normal(name, value) }
        val relayHeaders = relayed.headers.entries.associate { (name, value) -> name.lowercase() to normal(name.lowercase(), value) }
        assertEquals("$what: headers", lanHeaders, relayHeaders)
        lanAnswer.header("content-security-policy")?.let { assertEquals("ws://127.0.0.1:$port", socketOf(it)) }
        relayed.headers["Content-Security-Policy"]?.let { assertEquals(SOCKET_RELAY, socketOf(it)) }
        relayed.headers["Set-Cookie"]?.let { assertTrue("$what: $it", it.contains("; Path=$PREFIX/") && it.endsWith("; Secure")) }
        if (what.startsWith("GET") && lanAnswer.status == 200 && !what.startsWith("GET /request")) {
            assertTrue("$what: the same body", lanAnswer.body.contentEquals(relayed.body))
        }
    }

    private fun normal(name: String, value: String): String = when (name) {
        "content-security-policy" -> value.replace(Regex("wss?://[^ ;]+"), "<socket>")
        "set-cookie" -> value.substringBefore('=') + "=<value>;" + value.substringAfter(';')
            .replace(Regex("; Path=[^;]*"), "").replace("; Secure", "")
        else -> value
    }

    private fun socketOf(csp: String): String = Regex("connect-src 'self' (\\S+?);").find(csp)!!.groupValues[1]

    private companion object {
        const val RELAY_HOST = "relay.test"
        const val RELAY_CONFIG_HOST = "relay"
        const val PREFIX = "/p/abcdefgh2345"
        const val ORIGIN = "https://relay.test"
        const val SOCKET_RELAY = "wss://relay.test"

        /** Made once: 100,000 rounds take a moment. */
        val PIN: PinHash by lazy { PinHash.create("482913") }
    }
}
