// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections

/**
 * The web panel's server, for real, on 127.0.0.1 and a free port, against [FakeWebBackend]: every
 * rule of the audit's checklist that a request can test (BUILD_SPEC.md › v1.5.1 — M18).
 */
class WebServerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000L
    private lateinit var backend: FakeWebBackend
    private val sessions = Sessions(clock = { now })
    private val guard = LoginGuard(clock = { now })
    private val requests = GuestRequests(clock = { now })
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val files = mapOf(
        "index.html" to "<!doctype html><title>Panel</title>",
        "app.js" to "/* the panel */",
        "style.css" to ":root { }",
        "request.html" to "<!doctype html><title>Ask the piano</title>",
        "request.js" to "/* the request page */",
        "poster.html" to "<!doctype html>",
    )
    private val assets = AssetSource { name ->
        asked += name
        files[name]?.toByteArray()
    }
    private val sockets = object : WebSockets {
        var room = false

        override fun hasRoom(): Boolean = room

        override fun open(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket = error("These tests open no socket")
    }
    private val servers = mutableListOf<WebServer>()

    @Before
    fun setUp() {
        backend = FakeWebBackend(tmp.newFolder("uploads"))
    }

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
    }

    private fun start(guestOnly: Boolean = false, names: List<String> = emptyList()): Pair<WebServer, RawHttp> {
        val server = WebServer(WebServer.Config("127.0.0.1", 0, guestOnly, { names }, tmp.newFolder()), backend, sessions, guard, requests, assets, sockets)
        server.startListening()
        servers += server
        return server to RawHttp(server.listeningPort)
    }

    private fun login(http: RawHttp): String {
        backend.pin = PIN
        val answer = http.api("POST", "/api/login", """{"pin":"482913"}""")
        assertEquals(answer.toString(), 204, answer.status)
        return answer.cookie("sp_session")!!
    }

    private fun sample(route: WebServer.Route): Pair<String, String> {
        val path = route.sample
        val body = if (path.startsWith("/api/upload")) "0123456789" else "{}"
        return path to body
    }

    @Test
    fun `every route that changes anything refuses without a session, without the header, or with a foreign Host, and reaches nothing`() {
        val (server, http) = start()
        val token = login(http)
        backend.calls.clear()
        val writes = server.routes.filter { it.access == WebServer.Access.WRITE }
        assertEquals("the table's nineteen routes that change something", 19, writes.size)
        for (route in writes) {
            val (path, body) = sample(route)
            val method = route.method.name
            val noSession = http.api(method, path, body, session = null)
            assertEquals("$method $path without a session", 401, noSession.status)
            val noHeader = http.api(method, path, body, session = token, panel = false)
            assertEquals("$method $path without the header", 403, noHeader.status)
            val wrongHeader = http.api(method, path, body, session = token, panel = false, extra = mapOf("X-Steven-Piano" to "yes"))
            assertEquals("$method $path with another value", 403, wrongHeader.status)
            val foreign = http.api(method, path, body, session = token, extra = mapOf("Host" to "evil.example:${server.listeningPort}"))
            assertEquals("$method $path with a foreign Host", 403, foreign.status)
            val crossSite = http.api(method, path, body, session = token, extra = mapOf("Origin" to "http://evil.example"))
            assertEquals("$method $path from another site", 403, crossSite.status)
        }
        assertEquals("nothing reached the app", emptyList<String>(), backend.calls.toList())
        assertTrue(backend.imported.isEmpty())
    }

    @Test
    fun `reading the panel needs a session, and a stale or forged one is refused`() {
        val (server, http) = start()
        for (route in server.routes.filter { it.access == WebServer.Access.READ }) {
            assertEquals(route.sample, 401, http.get(route.sample).status)
            assertEquals(route.sample, 401, http.get(route.sample, mapOf("Cookie" to "sp_session=${"A".repeat(43)}")).status)
        }
        val token = login(http)
        assertEquals(200, http.get("/api/state", mapOf("Cookie" to "sp_session=$token")).status)
        now += Sessions.IDLE_MS
        assertEquals("a day without a request", 401, http.get("/api/state", mapOf("Cookie" to "sp_session=$token")).status)
    }

    @Test
    fun `logging in takes the right PIN, slows wrong ones down, and sets a strict session cookie`() {
        val (_, http) = start()
        val noPin = http.api("POST", "/api/login", """{"pin":"482913"}""")
        assertEquals("no PIN set on the tablet", 403, noPin.status)
        backend.pin = PIN
        assertEquals("login needs the header", 403, http.api("POST", "/api/login", """{"pin":"482913"}""", panel = false).status)
        repeat(4) {
            val wrong = http.api("POST", "/api/login", """{"pin":"000000"}""")
            assertEquals(401, wrong.status)
            assertEquals(0, wrong.json().getInt("retryAfter"))
        }
        val fifth = http.api("POST", "/api/login", """{"pin":"12"}""")
        assertEquals("a malformed PIN is a wrong one", 401, fifth.status)
        assertEquals(30, fifth.json().getInt("retryAfter"))
        val locked = http.api("POST", "/api/login", """{"pin":"482913"}""")
        assertEquals("even the right PIN waits out the lock", 429, locked.status)
        assertEquals("30", locked.header("retry-after"))
        now += 30_000
        val right = http.api("POST", "/api/login", """{"pin":"482913"}""")
        assertEquals(204, right.status)
        val cookie = right.all("set-cookie").single()
        assertTrue(cookie, cookie.startsWith("sp_session="))
        for (flag in listOf("HttpOnly", "SameSite=Strict", "Path=/")) assertTrue("$flag in $cookie", flag in cookie)
        assertFalse("no Domain: this host only", "Domain" in cookie)
        val token = right.cookie("sp_session")!!
        assertTrue(Sessions.TOKEN.matches(token))
        val out = http.api("POST", "/api/logout", "{}", session = token)
        assertEquals(204, out.status)
        assertTrue(out.all("set-cookie").single().contains("Max-Age=0"))
        assertEquals("logged out", 401, http.get("/api/state", mapOf("Cookie" to "sp_session=$token")).status)
    }

    @Test
    fun `every response carries the security headers and closes, and none carries CORS`() {
        val (server, http) = start()
        val token = login(http)
        val port = server.listeningPort
        val answers = listOf(
            http.get("/"),
            http.get("/app.js"),
            http.get("/api/state"),
            http.get("/api/state", mapOf("Cookie" to "sp_session=$token")),
            http.get("/nothing/here"),
            http.api("POST", "/api/play", """{"pieceId":1}""", session = token),
            http.api("POST", "/api/play", "{}", session = token, extra = mapOf("Host" to "evil.example:$port")),
            http.send("OPTIONS", "/api/state", headers = mapOf("Origin" to "http://evil.example", "Access-Control-Request-Method" to "POST", "Access-Control-Request-Headers" to "x-steven-piano")),
            http.get("/api/art/composer/debussy", mapOf("Cookie" to "sp_session=$token")),
        )
        for (answer in answers) {
            assertEquals(answer.toString(), "nosniff", answer.header("x-content-type-options"))
            assertEquals("DENY", answer.header("x-frame-options"))
            assertEquals("no-referrer", answer.header("referrer-policy"))
            assertEquals("same-origin", answer.header("cross-origin-resource-policy"))
            val csp = answer.header("content-security-policy")!!
            assertTrue(csp, csp.startsWith("default-src 'self'; img-src 'self' data:; connect-src 'self' ws://127.0.0.1:$port; frame-ancestors 'none'"))
            assertEquals("close", answer.header("connection"))
            assertTrue(answer.toString(), answer.headers.none { it.first.startsWith("access-control-") })
        }
        assertEquals("a preflight is refused", 405, answers[7].status)
        assertEquals("no-store", answers[3].header("cache-control"))
        assertEquals("application/json; charset=utf-8", answers[3].header("content-type"))
        assertEquals("private, max-age=3600", answers[8].header("cache-control"))
        assertEquals("image/jpeg", answers[8].header("content-type"))
        assertEquals("text/html; charset=utf-8", answers[0].header("content-type"))
        assertEquals("text/javascript; charset=utf-8", answers[1].header("content-type"))
    }

    @Test
    fun `a request must name this listener, its address or a name the person gave`() {
        val (server, http) = start(names = listOf("piano-tablet"))
        val port = server.listeningPort
        assertEquals(200, http.get("/", mapOf("Host" to "127.0.0.1:$port")).status)
        assertEquals(200, http.get("/", mapOf("Host" to "PIANO-TABLET:$port")).status)
        for (host in listOf("evil.example:$port", "127.0.0.1", "127.0.0.1:1", "localhost:$port", "piano-tablet.evil.example:$port", "")) {
            assertEquals(host, 403, http.get("/", mapOf("Host" to host)).status)
        }
        assertEquals(setOf("127.0.0.1:$port", "piano-tablet:$port"), server.allowedHosts())
    }

    @Test
    fun `JSON bodies are capped, strict and shallow, and ids are whole numbers`() {
        val (_, http) = start()
        val token = login(http)
        backend.calls.clear()
        fun play(body: String, extra: Map<String, String> = emptyMap()) = http.api("POST", "/api/play", body, session = token, extra = extra).status
        val tooLong = http.send("POST", "/api/play", null, mapOf("Content-Type" to "application/json", "X-Steven-Piano" to "1", "Cookie" to "sp_session=$token", "Content-Length" to "${WebApi.MAX_BODY + 1}"), readTimeoutMs = 3_000)
        assertEquals("refused on its length alone", 413, tooLong.status)
        assertEquals(415, play("""{"pieceId":1}""", mapOf("Content-Type" to "text/plain")))
        assertEquals(415, play("""{"pieceId":1}""", mapOf("Content-Type" to "application/json; charset=latin1")))
        assertEquals(400, play("""{"pieceId":1,"queue":[[[[1]]]]}"""))
        assertEquals(400, play("""[1]"""))
        assertEquals(400, play("""{"pieceId":1"""))
        assertEquals(400, play("""{"pieceId":1,"other":2}"""))
        for (id in listOf("1.5", "\"1\"", "9223372036854775808", "0", "-3", "true", "null")) assertEquals(id, 400, play("""{"pieceId":$id}"""))
        val badUtf8 = byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), 0x28, '"'.code.toByte(), ':'.code.toByte(), '1'.code.toByte(), '}'.code.toByte())
        assertEquals(400, http.send("POST", "/api/play", badUtf8, mapOf("Content-Type" to "application/json", "X-Steven-Piano" to "1", "Cookie" to "sp_session=$token")).status)
        val noLength = http.send("POST", "/api/play", """{"pieceId":1}""".toByteArray(), mapOf("Content-Type" to "application/json", "X-Steven-Piano" to "1", "Cookie" to "sp_session=$token"), lengthHeader = false)
        assertEquals(411, noLength.status)
        val chunked = http.send("POST", "/api/play", "0\r\n\r\n".toByteArray(), mapOf("Content-Type" to "application/json", "Transfer-Encoding" to "chunked", "X-Steven-Piano" to "1", "Cookie" to "sp_session=$token"), lengthHeader = false)
        assertEquals(411, chunked.status)
        assertEquals("nothing got through", emptyList<String>(), backend.calls.toList())
        assertEquals(204, play("""{"pieceId":3,"queue":[3,1]}"""))
        assertEquals(404, play("""{"pieceId":99}"""))
        assertEquals(listOf("play 3 queue=[3, 1]"), backend.calls.toList())
    }

    @Test
    fun `uploads need a length, a MIDI or zip name and a size under the cap, all before a byte is read`() {
        val (_, http) = start()
        val token = login(http)
        val auth = mapOf("X-Steven-Piano" to "1", "Cookie" to "sp_session=$token")
        fun headOnly(path: String, length: Long?): Int {
            val headers = auth + (if (length != null) mapOf("Content-Length" to length.toString()) else emptyMap())
            // Only the head is sent: a server that tried to read the body would wait out the socket's timeout instead.
            return http.send("PUT", path, null, headers, readTimeoutMs = 3_000).status
        }
        assertEquals(415, headOnly("/api/upload?name=notes.txt", 5_000_000))
        assertEquals(415, headOnly("/api/upload?name=song.mid.exe", 100))
        assertEquals(411, headOnly("/api/upload?name=song.mid", null))
        assertEquals(413, headOnly("/api/upload?name=song.mid", MIDI_BYTES + 1))
        assertEquals(413, headOnly("/api/upload?name=library.zip", ZIP_BYTES + 1))
        assertEquals(400, headOnly("/api/upload", 100))
        assertTrue("nothing was imported", backend.imported.isEmpty())

        val midi = ByteArray(2_000) { it.toByte() }
        val sent = http.send("PUT", "/api/upload?name=..%2F..%2FChopin%20-%20Nocturne.MID", midi, auth)
        assertEquals(sent.toString(), 202, sent.status)
        assertEquals("the folders go, the name stays", "Chopin - Nocturne.MID", sent.json().getString("name"))
        val zip = ByteArray(10_000) { (it % 7).toByte() }
        assertEquals(202, http.send("PUT", "/api/upload?name=pieces.zip", zip, auth).status)
        assertEquals(listOf("midi Chopin - Nocturne.MID 2000", "zip pieces.zip 10000"), backend.imported.toList())
        assertEquals("the zip's copy went once read", emptyList<String>(), backend.uploadDir.list()!!.toList())
    }

    @Test
    fun `one upload at a time`() {
        val (server, http) = start()
        val token = login(http)
        val auth = mapOf("X-Steven-Piano" to "1", "Cookie" to "sp_session=$token")
        Socket().use { first ->
            first.connect(InetSocketAddress("127.0.0.1", server.listeningPort))
            first.soTimeout = 10_000
            val body = ByteArray(1_000) { 1 }
            first.getOutputStream().write(http.head("PUT", "/api/upload?name=slow.mid", body, auth))
            first.getOutputStream().write(body, 0, 500)
            first.getOutputStream().flush()
            Thread.sleep(500)   // the first upload holds the lock while its body trickles in
            val second = http.send("PUT", "/api/upload?name=quick.mid", ByteArray(100) { 2 }, auth)
            assertEquals(409, second.status)
            first.getOutputStream().write(body, 500, 500)
            first.getOutputStream().flush()
            assertEquals(202, RawHttp.read(first.getInputStream()).status)
        }
        assertEquals(listOf("midi slow.mid 1000"), backend.imported.toList())
        assertEquals(202, http.send("PUT", "/api/upload?name=quick.mid", ByteArray(100) { 2 }, auth).status)
    }

    @Test
    fun `files come from the allow-list by name, and a path never resolves`() {
        val (_, http) = start()
        assertEquals(200, http.get("/").status)
        assertEquals("<!doctype html><title>Panel</title>", http.get("/").text)
        assertEquals(200, http.get("/app.js").status)
        assertEquals(200, http.get("/style.css").status)
        for (path in listOf("/index.html", "/../app.js", "/%2e%2e/%2e%2e/etc/passwd", "/app.js/..", "//app.js", "/web/app.js", "/assets/web/index.html", "/poster.html", "/request.html", "/.", "/%00", "/api", "/api/")) {
            assertEquals(path, 404, http.get(path).status)
        }
        assertEquals("a file's route is GET only", 405, http.send("POST", "/app.js", "x".toByteArray()).status)
        assertTrue("only names on the list were ever read: $asked", WebAssets.NAMES.containsAll(asked.toSet()))
    }

    @Test
    fun `the public routes answer without a session, and the request page gives the guest an id`() {
        val (_, http) = start()
        val page = http.get("/request")
        assertEquals(200, page.status)
        val guest = page.cookie("sp_guest")!!
        assertTrue(WebCookies.GUEST_ID.matches(guest))
        assertTrue(page.all("set-cookie").single().contains("HttpOnly; SameSite=Strict; Path=/"))
        assertNull("a guest who has one keeps it", http.get("/request", mapOf("Cookie" to "sp_guest=$guest")).cookie("sp_guest"))
        assertEquals(200, http.get("/request.js").status)
        assertEquals(200, http.get("/style.css").status)
        val catalogue = http.get("/api/public/catalogue")
        assertEquals(200, catalogue.status)
        val lists = catalogue.json().getJSONArray("lists")
        assertEquals("Popular", lists.getJSONObject(0).getString("name"))
        val first = lists.getJSONObject(0).getJSONArray("pieces").getJSONObject(0)
        assertEquals(setOf("id", "title", "composer"), first.keys().asSequence().toSet())
        backend.guests = GuestSettings(open = false, approveFirst = false)
        val closed = http.get("/api/public/catalogue").json()
        assertFalse(closed.getBoolean("open"))
        assertEquals(0, closed.getJSONArray("lists").length())
    }

    @Test
    fun `a guest may ask once every five minutes, by cookie and by address, for pieces on the list only`() {
        val (_, http) = start()
        fun ask(id: Long, cookie: String? = null) = http.api("POST", "/api/public/request", """{"pieceId":$id}""", panel = false, extra = cookie?.let { mapOf("Cookie" to "sp_guest=$it") } ?: emptyMap())
        val first = ask(1)
        assertEquals(first.toString(), 202, first.status)
        assertEquals("queued", first.json().getString("status"))
        val guest = first.cookie("sp_guest")!!
        assertEquals(listOf("requested 1"), backend.calls.toList())
        val again = ask(2, guest)
        assertEquals(429, again.status)
        assertEquals("300", again.header("retry-after"))
        assertEquals(300, again.json().getInt("retryAfter"))
        assertEquals("a new cookie from the same phone waits too", 429, ask(2, "B".repeat(22)).status)
        now += 4 * 60_000
        assertEquals(60, ask(2, guest).json().getInt("retryAfter"))
        now += 60_000
        assertEquals("not on the list", 400, ask(3, guest).status)
        assertEquals(202, ask(2, guest).status)
        assertEquals(listOf("requested 1", "requested 2"), backend.calls.toList())
        assertEquals("free text, or anything but the id, is refused", 400, http.api("POST", "/api/public/request", """{"pieceId":1,"note":"hello"}""", panel = false).status)
        assertEquals("it must be JSON, so another site's form cannot send it", 415, http.send("POST", "/api/public/request", "pieceId=1".toByteArray(), mapOf("Content-Type" to "application/x-www-form-urlencoded")).status)
        assertEquals("another site's page cannot send it either", 403, http.api("POST", "/api/public/request", """{"pieceId":1}""", panel = false, extra = mapOf("Origin" to "http://evil.example")).status)
        backend.guests = GuestSettings(open = false, approveFirst = false)
        now += 10 * 60_000
        assertEquals("guests closed", 403, ask(1, guest).status)
    }

    @Test
    fun `with approval first a request waits on the panel until approved or dismissed`() {
        val (_, http) = start()
        val token = login(http)
        backend.calls.clear()
        backend.guests = GuestSettings(open = true, approveFirst = true)
        val asked = http.api("POST", "/api/public/request", """{"pieceId":2}""", panel = false)
        assertEquals("pending", asked.json().getString("status"))
        assertTrue("nothing plays yet", backend.calls.isEmpty())
        val list = http.get("/api/requests", mapOf("Cookie" to "sp_session=$token")).json()
        val pending = list.getJSONArray("pending").getJSONObject(0)
        assertEquals("Nocturne in E-flat", pending.getString("title"))
        assertEquals("Chopin", pending.getString("composer"))
        val id = pending.getLong("id")
        assertEquals(1, http.get("/api/state", mapOf("Cookie" to "sp_session=$token")).json().getJSONObject("requests").getInt("pending"))
        assertEquals(204, http.api("POST", "/api/requests/$id/approve", session = token).status)
        assertEquals(listOf("requested 2"), backend.calls.toList())
        assertEquals("approved once", 404, http.api("POST", "/api/requests/$id/approve", session = token).status)
        now += 5 * 60_000
        http.api("POST", "/api/public/request", """{"pieceId":1}""", panel = false)
        val second = requests.pending.value.single().id
        assertEquals(204, http.api("POST", "/api/requests/$second/dismiss", session = token).status)
        assertEquals("dismissed: nothing plays", listOf("requested 2"), backend.calls.toList())
    }

    @Test
    fun `a Wi-Fi listener without Panel on Wi-Fi too serves guests only`() {
        val (server, http) = start(guestOnly = true)
        backend.pin = PIN
        for (path in listOf("/", "/app.js", "/api/state", "/api/library", "/api/piano")) assertEquals(path, 404, http.get(path).status)
        assertEquals("no logging in here", 404, http.api("POST", "/api/login", """{"pin":"482913"}""").status)
        assertEquals(404, http.api("POST", "/api/play", """{"pieceId":1}""").status)
        assertEquals(200, http.get("/request").status)
        assertEquals(200, http.get("/style.css").status)
        assertEquals(200, http.get("/api/public/catalogue").status)
        assertEquals(202, http.api("POST", "/api/public/request", """{"pieceId":1}""", panel = false).status)
        assertEquals(404, handshake(http, server.listeningPort, cookie = null).status)
    }

    @Test
    fun `the socket opens only for a session, from the panel's own page, on the panel's listener`() {
        val (server, http) = start()
        val token = login(http)
        val port = server.listeningPort
        assertEquals(401, handshake(http, port, cookie = null).status)
        assertEquals("no origin", 403, handshake(http, port, cookie = token, origin = null).status)
        assertEquals("another site's page", 403, handshake(http, port, cookie = token, origin = "http://evil.example").status)
        assertEquals("no room", 503, handshake(http, port, cookie = token).status)
        assertEquals("another path", 404, handshake(http, port, cookie = token, path = "/api/state").status)
        assertEquals("a foreign Host", 403, handshake(http, port, cookie = token, host = "evil.example:$port").status)
    }

    @Test
    fun `the piano's routes write only the table's names, within their ranges`() {
        val (_, http) = start()
        val token = login(http)
        backend.calls.clear()
        fun set(name: String, value: String) = http.api("PUT", "/api/piano/$name", """{"value":$value}""", session = token).status
        assertEquals(204, set("volume", "70"))
        assertEquals(204, set("volume", "100.0000001"))
        assertEquals(400, set("volume", "101"))
        assertEquals(400, set("volume", "\"70\""))
        assertEquals(403, set("keyforce_white", "1.2"))
        assertEquals(404, set("nope", "1"))
        assertEquals(404, set("preset", "1"))
        assertEquals(400, set("restrike", "20"))
        assertEquals(204, set("restrike", "0"))
        assertEquals(204, set("restrike", "40"))
        assertEquals(204, set("leds", "true"))
        assertEquals(204, set("ledmode", "3"))
        assertEquals(400, set("ledmode", "4"))
        assertEquals(204, set("velcurve", "1.23"))
        assertEquals(400, set("velcurve", "3.5"))
        assertEquals(
            listOf("piano set volume 70", "piano set volume 100", "piano set restrike 0", "piano set restrike 40", "piano set leds 1", "piano set ledmode 3", "piano set velcurve 1.25"),
            backend.calls.toList(),
        )
        backend.calls.clear()
        assertEquals(204, http.api("POST", "/api/piano/preset", """{"name":"cinematic"}""", session = token).status)
        assertEquals(400, http.api("POST", "/api/piano/preset", """{"name":"loud"}""", session = token).status)
        for (action in listOf("off", "save", "status")) assertEquals(204, http.api("POST", "/api/piano/action", """{"name":"$action"}""", session = token).status)
        for (action in listOf("ledtest", "testmax", "dump")) assertEquals(action, 400, http.api("POST", "/api/piano/action", """{"name":"$action"}""", session = token).status)
        assertEquals(listOf("piano preset cinematic", "piano action off", "piano action save", "piano action status"), backend.calls.toList())
        val piano = http.get("/api/piano", mapOf("Cookie" to "sp_session=$token")).json()
        assertEquals(listOf("feel", "lighting", "pedal"), (0 until piano.getJSONArray("pages").length()).map { piano.getJSONArray("pages").getJSONObject(it).getString("key") })
        assertFalse("the key-force pair is never offered", piano.toString().contains("keyforce"))
    }

    @Test
    fun `the panel changes only the preferences it may, within their ranges`() {
        val (_, http) = start()
        val token = login(http)
        backend.calls.clear()
        fun put(json: String) = http.api("PUT", "/api/settings", json, session = token).status
        assertEquals(204, put("""{"webGuests":true,"preRollMs":1500}"""))
        assertEquals(204, put("""{"webHostName":"Piano-Tablet"}"""))
        for (bad in listOf("""{"webEnabled":true}""", """{"webOnWifi":true}""", """{"webPinHash":"x"}""", "{}", """{"preRollMs":9000}""", """{"webHostName":"100.101.2.3"}""", """{"webHostName":"piano tablet"}""", """{"webGuests":"yes"}""")) {
            assertEquals(bad, 400, put(bad))
        }
        assertEquals(2, backend.calls.size)
        assertTrue(backend.calls[0], "webGuests=true" in backend.calls[0] && "preRollMs=1500" in backend.calls[0])
        assertTrue(backend.calls[1], "webHostName=piano-tablet" in backend.calls[1])
    }

    @Test
    fun `the library, playlists, composers and art answer what the backend holds`() {
        val (_, http) = start()
        val token = login(http)
        val auth = mapOf("Cookie" to "sp_session=$token")
        val page = http.get("/api/library?q=clair", auth).json()
        assertEquals(1, page.getInt("total"))
        assertEquals("portrait", page.getJSONArray("pieces").getJSONObject(0).getString("art"))
        assertEquals(400, http.get("/api/library?limit=500", auth).status)
        assertEquals(400, http.get("/api/library?category=weird", auth).status)
        assertEquals(400, http.get("/api/library?offset=-1", auth).status)
        assertEquals(2, http.get("/api/library?limit=2", auth).json().getJSONArray("pieces").length())
        assertEquals("Evening", http.get("/api/playlists", auth).json().getJSONArray("playlists").getJSONObject(0).getString("name"))
        assertEquals(2, http.get("/api/playlists/10", auth).json().getJSONArray("pieces").length())
        assertEquals(404, http.get("/api/playlists/11", auth).status)
        assertEquals(3, http.get("/api/composers", auth).json().getJSONArray("composers").length())
        assertEquals(404, http.get("/api/composers/nobody", auth).status)
        assertEquals(200, http.get("/api/art/composer/debussy?size=tile", auth).status)
        assertEquals(400, http.get("/api/art/composer/debussy?size=huge", auth).status)
        assertEquals(404, http.get("/api/art/composer/chopin", auth).status)
        assertEquals("image/png", http.get("/api/art/piece/1", auth).header("content-type"))
        assertEquals(404, http.get("/api/art/piece/99", auth).status)
    }

    @Test
    fun `transport, queue and channel commands reach the app as asked`() {
        val (_, http) = start()
        val token = login(http)
        backend.state = WebState(player = WebPlayer(queue = dev.stevenjin.stevenpiano.player.QueueSnapshot(listOf(1, 2), listOf(7, 8), 0)))
        backend.calls.clear()
        fun post(path: String, json: String) = http.api("POST", path, json, session = token).status
        assertEquals(204, post("/api/transport", """{"action":"next"}"""))
        assertEquals(400, post("/api/transport", """{"action":"eject"}"""))
        assertEquals(204, post("/api/seek", """{"ms":90000}"""))
        assertEquals(400, post("/api/seek", """{"ms":-1}"""))
        assertEquals(204, post("/api/tempo", """{"pct":80}"""))
        assertEquals(400, post("/api/tempo", """{"pct":300}"""))
        assertEquals(204, post("/api/shuffle", """{"on":true}"""))
        assertEquals(204, post("/api/repeat", """{"mode":"one"}"""))
        assertEquals(400, post("/api/repeat", """{"mode":"twice"}"""))
        assertEquals(204, post("/api/queue", """{"action":"move","uid":8,"toIndex":0}"""))
        assertEquals("a stale entry", 404, post("/api/queue", """{"action":"remove","uid":99}"""))
        assertEquals(204, post("/api/play-all", """{"playlistId":10,"shuffle":true}"""))
        assertEquals(400, post("/api/play-all", """{"playlistId":10,"ids":[1]}"""))
        assertEquals(204, post("/api/channels/calm/play", "{}"))
        assertEquals(409, post("/api/channels/tiny/play", "{}"))
        assertEquals(404, post("/api/channels/none/play", "{}"))
        assertEquals(204, http.api("PUT", "/api/channels/calm/volume", """{"pct":40}""", session = token).status)
        assertEquals(400, http.api("PUT", "/api/channels/calm/volume", """{"pct":140}""", session = token).status)
        assertEquals(
            listOf(
                "transport next", "seek 90000", "tempo 80", "shuffle true", "repeat one", "queue Move(uid=8, toIndex=0)", "queue Remove(uid=99)",
                "play-playlist 10 shuffle=true", "channel play calm", "channel volume calm 40",
            ),
            backend.calls.toList(),
        )
    }

    /** A WebSocket handshake by hand. */
    private fun handshake(http: RawHttp, port: Int, cookie: String?, origin: String? = "http://127.0.0.1:$port", path: String = "/ws", host: String? = null): RawHttp.Answer {
        val headers = LinkedHashMap<String, String>()
        if (host != null) headers["Host"] = host
        headers["Upgrade"] = "websocket"
        headers["Connection"] = "Upgrade"
        headers["Sec-WebSocket-Key"] = "dGhlIHNhbXBsZSBub25jZQ=="
        headers["Sec-WebSocket-Version"] = "13"
        if (origin != null) headers["Origin"] = origin
        if (cookie != null) headers["Cookie"] = "sp_session=$cookie"
        return http.send("GET", path, headers = headers, readTimeoutMs = 3_000)
    }

    private companion object {
        /** Made once: 100,000 rounds take a moment. */
        val PIN: PinHash by lazy { PinHash.create("482913") }
    }
}
