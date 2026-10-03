// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import dev.stevenjin.stevenpiano.studio.ComposeOrder
import dev.stevenjin.stevenpiano.studio.compose.ComposeRequest
import dev.stevenjin.stevenpiano.studio.compose.Mood
import dev.stevenjin.stevenpiano.studio.compose.MusicKey
import dev.stevenjin.stevenpiano.schedule.ScheduleRules
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
    private val store = FakeSessionStore()
    private val sessions = Sessions(store, clock = { now })
    private val guard = LoginGuard(clock = { now })
    private val requests = GuestRequests(clock = { now })
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val files = mapOf(
        "index.html" to "<!doctype html><title>Panel</title>",
        "app.js" to "/* the panel */",
        "style.css" to ":root { }",
        "request.html" to "<!doctype html><title>Ask the piano</title>",
        "request.js" to "/* the request page */",
        "poster.html" to "<!doctype html><p>{{URL}}</p>{{QR}}",
        "bravura.otf" to "OTTO the font as the app carries it",
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

    private fun start(guestOnly: Boolean = false, names: List<String> = emptyList(), deadlineMs: Long = REQUEST_DEADLINE_MS): Pair<WebServer, RawHttp> {
        val poster: suspend (String) -> ByteArray? = { url -> Poster.page(assets.read(WebAssets.POSTER.name), url) }
        val server = WebServer(WebServer.Config("127.0.0.1", 0, guestOnly, { names }, tmp.newFolder(), deadlineMs), backend, sessions, guard, requests, assets, sockets, poster)
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
        assertEquals("the table's twenty-five routes that change something (the schedules' three from 1.6.2, Studio's three from 1.7)", 25, writes.size)
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
        assertEquals("a year without a request", 401, http.get("/api/state", mapOf("Cookie" to "sp_session=$token")).status)
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
        assertTrue("kept a year, as the tablet keeps it (M42): $cookie", cookie.endsWith("; Max-Age=31536000"))
        assertFalse("no Domain: this host only", "Domain" in cookie)
        val token = right.cookie("sp_session")!!
        assertTrue(Sessions.TOKEN.matches(token))
        val remembered = store.saved.keys.single()
        assertTrue("remembered by its digest alone: $remembered", FileSessionStore.DIGEST.matches(remembered) && token !in remembered)
        val out = http.api("POST", "/api/logout", "{}", session = token)
        assertEquals(204, out.status)
        assertTrue(out.all("set-cookie").single().contains("Max-Age=0"))
        assertEquals("logged out", 401, http.get("/api/state", mapOf("Cookie" to "sp_session=$token")).status)
        assertEquals("and forgotten", emptyMap<String, Long>(), store.saved)
    }

    @Test
    fun `wrong PINs sent at once are weighed one at a time, so none slips past the lock (audit W2)`() {
        val (_, http) = start()
        backend.pin = PIN
        val go = java.util.concurrent.CountDownLatch(1)
        val statuses: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        val senders = (1..10).map {
            Thread {
                go.await()
                statuses += http.api("POST", "/api/login", """{"pin":"000000"}""").status
            }.apply { start() }
        }
        go.countDown()
        senders.forEach { it.join() }
        // Without the weighing lock every try that reached a thread before the fifth was counted passed
        // the wait unchecked: eight or more were weighed. Now exactly the five before the lock are.
        assertEquals("weighed: $statuses", 5, statuses.count { it == 401 })
        assertEquals("refused uncounted: $statuses", 5, statuses.count { it == 429 })
    }

    @Test
    fun `a request NanoHTTPD could not parse is refused with the panel's own headers, echoing nothing (audit W3)`() {
        val (server, _) = start()
        val port = server.listeningPort
        val host = "Host: 127.0.0.1:$port\r\n"
        // Each of these NanoHTTPD 2.3.1 used to answer by itself (no security headers, kept alive,
        // the method echoed), drop unanswered (a broken escape), cut short (a head over 8 KB) or pass
        // on as something else (another version, an absolute target, a second Host).
        val cases = listOf(
            "HELLO\r\n$host\r\n" to 400,
            "<script> / HTTP/1.1\r\n$host\r\n" to 501,
            "get / HTTP/1.1\r\n$host\r\n" to 501,
            "GET /%zz HTTP/1.1\r\n$host\r\n" to 400,
            "GET /api/library?q=%E HTTP/1.1\r\n$host\r\n" to 400,
            "GET / HTTP/2.0\r\n$host\r\n" to 505,
            "GET http://127.0.0.1:$port/ HTTP/1.1\r\n$host\r\n" to 400,
            "GET  / HTTP/1.1\r\n$host\r\n" to 400,
            "GET / HTTP/1.1\r\n${host}Host: evil.example:$port\r\n\r\n" to 400,
            "GET / HTTP/1.1\r\n${host}X-Pad: ${"a".repeat(9_000)}\r\n\r\n" to 431,
        )
        for ((request, status) in cases) {
            val what = request.take(48).replace("\r\n", "⏎")
            val answer = rawAnswer(port, request.toByteArray(Charsets.ISO_8859_1))
            assertTrue("an answer to $what", answer != null)
            answer!!
            assertEquals(what, status, answer.status)
            assertEquals(what, "nosniff", answer.header("x-content-type-options"))
            assertEquals(what, "DENY", answer.header("x-frame-options"))
            assertEquals(what, "no-referrer", answer.header("referrer-policy"))
            assertEquals(what, "same-origin", answer.header("cross-origin-resource-policy"))
            assertTrue(what, answer.header("content-security-policy")!!.startsWith("default-src 'self'; img-src 'self' data:; connect-src 'self' ws://127.0.0.1:$port;"))
            assertEquals(what, "close", answer.header("connection")?.lowercase())
            assertEquals(what, "no-store", answer.header("cache-control"))
            for (echo in listOf("script", "HELLO", "evil", "zz", "aaaa")) assertFalse("$what: nothing of the request echoed", echo in answer.text)
        }
        assertEquals("the listener answers the next request", 200, RawHttp(port).get("/").status)
        val bareLf = rawAnswer(port, "GET / HTTP/1.1\nHost: 127.0.0.1:$port\n\n".toByteArray())
        assertEquals("a head ended by bare line feeds, which NanoHTTPD tolerates, still passes", 200, bareLf?.status)
    }

    @Test
    fun `a head trickled a byte at a time is cut off at the request's deadline, and the listener answers meanwhile (audit W3)`() {
        val (server, http) = start(deadlineMs = 1_000)
        val port = server.listeningPort
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val peers = (1..POOL_THREADS).map {
            Socket().apply {
                connect(InetSocketAddress("127.0.0.1", port), 5_000)
                soTimeout = 10_000
                getOutputStream().write("GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nX-Slow: ".toByteArray())
            }
        }
        // A byte every 200 ms from each, far inside the 10 s per-read timeout: only the deadline ends this.
        val trickle = Thread {
            while (!stop.get()) {
                for (p in peers) runCatching { p.getOutputStream().write('a'.code) }
                runCatching { Thread.sleep(200) }
            }
        }.apply { start() }
        try {
            Thread.sleep(300)   // the four hold the four request threads
            val started = System.nanoTime()
            val answer = http.send("GET", "/", readTimeoutMs = 5_000)
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(200, answer.status)
            assertTrue("answered once the deadline freed a thread, not never: $tookMs ms", tookMs < 3_000)
            for (p in peers) {
                val cut = RawHttp.read(p.getInputStream())
                assertEquals("a peer too slow with its head is told so", 408, cut.status)
                assertEquals("nosniff", cut.header("x-content-type-options"))
            }
        } finally {
            stop.set(true)
            trickle.join()
            peers.forEach { it.close() }
        }
    }

    @Test
    fun `a signed-in upload's body may take longer than a request's deadline (audit W3)`() {
        val (server, http) = start(deadlineMs = 1_000)
        val token = login(http)
        val auth = mapOf("X-Steven-Piano" to "1", "Cookie" to "sp_session=$token")
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", server.listeningPort), 5_000)
            socket.soTimeout = 10_000
            val body = ByteArray(1_000) { 3 }
            socket.getOutputStream().write(http.head("PUT", "/api/upload?name=slow.mid", body, auth))
            socket.getOutputStream().write(body, 0, 400)
            socket.getOutputStream().flush()
            Thread.sleep(1_500)   // past the deadline: the body is exempt once the upload is checked and signed in
            socket.getOutputStream().write(body, 400, 600)
            socket.getOutputStream().flush()
            assertEquals(202, RawHttp.read(socket.getInputStream()).status)
        }
        assertEquals(listOf("midi slow.mid 1000"), backend.imported.toList())
    }

    @Test
    fun `a peer that keeps trickling after its answer is let go within half a second (audit W3)`() {
        val (server, http) = start()
        val port = server.listeningPort
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        // As many peers as there are request threads: each takes its answer, then sends a byte every
        // 200 ms, inside the drain's per-read timeout, which (before the fix) kept each thread draining.
        val peers = (1..POOL_THREADS).map {
            val socket = Socket().apply {
                connect(InetSocketAddress("127.0.0.1", port), 5_000)
                soTimeout = 5_000
            }
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray())
            assertEquals(200, RawHttp.read(socket.getInputStream()).status)
            Thread {
                try {
                    while (!stop.get()) {
                        socket.getOutputStream().write('x'.code)
                        Thread.sleep(200)
                    }
                } catch (e: java.io.IOException) {
                    // Let go by the server: what the test wants.
                }
            }.apply { start() }
            socket
        }
        try {
            Thread.sleep(300)
            val started = System.nanoTime()
            val answer = http.send("GET", "/", readTimeoutMs = 4_000)
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertEquals(200, answer.status)
            assertTrue("answered as the drains ended, not when the peers stopped: $tookMs ms", tookMs < 2_000)
        } finally {
            stop.set(true)
            peers.forEach { it.close() }
        }
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
    fun `the views of the piece playing are read-only bytes, every input bounded, every answer mapped (v1_13 M32)`() {
        val (_, http) = start()
        val token = login(http)
        val cookie = mapOf("Cookie" to "sp_session=$token")
        backend.calls.clear()
        val notes = http.get("/api/now/notes?rev=12", cookie)
        assertEquals(200, notes.status)
        assertEquals("application/octet-stream", notes.header("content-type"))
        assertEquals("no-store", notes.header("cache-control"))
        assertEquals("nosniff", notes.header("x-content-type-options"))
        assertEquals("SPNT-notes", notes.text)
        assertEquals(200, http.get("/api/now/score?w=1280&h=720", cookie).status)
        assertEquals(200, http.get("/api/now/score/7/page/3", cookie).status)
        assertEquals(listOf("notes rev=12", "score rev=null 1280x720", "page 7/3"), backend.viewsAsked.toList())
        // Every input a bounded whole number; nothing out of range reaches the app.
        backend.viewsAsked.clear()
        for (path in listOf(
            "/api/now/notes?rev=abc", "/api/now/notes?rev=-1", "/api/now/notes?rev=99999999999", "/api/now/notes?rev=1.5",
            "/api/now/score", "/api/now/score?w=800", "/api/now/score?w=0&h=600", "/api/now/score?w=800&h=9000",
            "/api/now/score?w=800&h=600&rev=x", "/api/now/score?w=1e3&h=600", "/api/now/score/9999999999/page/0",
        )) {
            assertEquals(path, 400, http.get(path, cookie).status)
        }
        for (path in listOf("/api/now/score/1/page/123456", "/api/now/score/99999999999/page/0", "/api/now/score/x/page/0", "/api/now/score/1/page/-1", "/api/now/notes/1")) {
            assertEquals(path, 404, http.get(path, cookie).status)
        }
        assertEquals(emptyList<String>(), backend.viewsAsked.toList())
        // Nothing here changes anything.
        assertEquals(405, http.api("POST", "/api/now/notes", "{}", session = token).status)
        assertEquals(405, http.api("PUT", "/api/now/score?w=800&h=600", "{}", session = token).status)
        assertEquals(405, http.api("DELETE", "/api/font/bravura.otf", null, session = token).status)
        // The answers a layout gives, as HTTP.
        backend.scoreAnswer = NowAnswer.Working(700)
        val working = http.get("/api/now/score?w=800&h=600", cookie)
        assertEquals(202, working.status)
        assertEquals("working", working.json().getString("status"))
        assertEquals(700, working.json().getLong("retryAfterMs"))
        backend.scoreAnswer = NowAnswer.Busy(12_000)
        val busy = http.get("/api/now/score?w=800&h=600", cookie)
        assertEquals(429, busy.status)
        assertEquals("12", busy.header("retry-after"))
        for ((answer, status) in listOf(NowAnswer.Stale to 409, NowAnswer.NoPiece to 404, NowAnswer.TooLarge to 413)) {
            backend.scoreAnswer = answer
            assertEquals("$answer", status, http.get("/api/now/score?w=800&h=600", cookie).status)
        }
        // The font: the app's own file, as is, for a year under its versioned address; a session first.
        val font = http.get("/api/font/bravura.otf?v=${WebAssets.FONT_VERSION}", cookie)
        assertEquals(200, font.status)
        assertEquals("font/otf", font.header("content-type"))
        assertEquals("private, max-age=31536000, immutable", font.header("cache-control"))
        assertEquals("OTTO the font as the app carries it", font.text)
        assertEquals(401, http.get("/api/font/bravura.otf").status)
        assertEquals(401, http.get("/api/now/notes").status)
        assertEquals("not a static file either", 404, http.get("/bravura.otf").status)
        assertEquals("nothing the views asked was a change", emptyList<String>(), backend.calls.toList())
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
    fun `Studio takes a recording under its cap, refuses the rest before a byte is read, and cancels its jobs`() {
        val (_, http) = start()
        val token = login(http)
        val auth = mapOf("X-Steven-Piano" to "1", "Cookie" to "sp_session=$token")
        fun headOnly(path: String, length: Long?): RawHttp.Answer {
            val headers = auth + (if (length != null) mapOf("Content-Length" to length.toString()) else emptyMap())
            return http.send("PUT", path, null, headers, readTimeoutMs = 3_000)
        }
        assertEquals(415, headOnly("/api/studio/audio?name=notes.txt", 5_000).status)
        assertEquals(415, headOnly("/api/studio/audio?name=song.mid", 5_000).status)
        assertEquals(411, headOnly("/api/studio/audio?name=take.wav", null).status)
        assertEquals(413, headOnly("/api/studio/audio?name=take.wav", AUDIO_BYTES + 1).status)
        assertEquals(400, headOnly("/api/studio/audio?name=take.wav", 0).status)
        assertEquals(400, headOnly("/api/studio/audio", 100).status)
        backend.studioHeld = WebStudio(available = false, reason = "Studio isn't available on this device.")
        val refused = headOnly("/api/studio/audio?name=take.m4a", 1_000)
        assertEquals(409, refused.status)
        assertEquals("Studio isn't available on this device.", refused.json().getString("message"))
        assertTrue("nothing was sent to Studio", backend.recordings.isEmpty())

        backend.studioHeld = WebStudio(available = true)
        val recording = ByteArray(30_000) { (it % 251).toByte() }
        val sent = http.send("PUT", "/api/studio/audio?name=..%2FClair%20de%20lune.M4A", recording, auth)
        assertEquals(sent.toString(), 202, sent.status)
        assertEquals("Clair de lune.M4A", sent.json().getString("name"))
        assertEquals(40L, sent.json().getLong("job"))
        val (name, length, bytes) = backend.recordings.single()
        assertEquals("Clair de lune.M4A" to 30_000L, name to length)
        assertTrue("the bytes arrive whole", bytes.contentEquals(recording))
        assertEquals("the recording's copy went once handed over", emptyList<String>(), backend.uploadDir.list()!!.toList())
        for (ext in AUDIO_EXTENSIONS) assertEquals(ext, 202, http.send("PUT", "/api/studio/audio?name=x.$ext", ByteArray(10) { 1 }, auth).status)

        assertEquals(204, http.api("POST", "/api/studio/jobs/7/cancel", session = token).status)
        assertEquals("gone once cancelled", 404, http.api("POST", "/api/studio/jobs/7/cancel", session = token).status)
        assertEquals(404, http.api("POST", "/api/studio/jobs/0/cancel", session = token).status)
    }

    @Test
    fun `Studio composes from a piece of the library with checked choices, and its seed comes with its key and tempo`() {
        val (_, http) = start()
        val token = login(http)
        val auth = mapOf("Cookie" to "sp_session=$token")

        val seed = http.get("/api/studio/seed", auth)
        assertEquals(200, seed.status)
        assertEquals("the default seed: the piece played last", 12L, seed.json().getLong("pieceId"))
        assertEquals("Clair de lune", seed.json().getString("title"))
        assertEquals("D♭ major", seed.json().getJSONObject("key").getString("label"))
        assertEquals(66, seed.json().getInt("bpm"))
        assertEquals(13L, http.get("/api/studio/seed?piece=13", auth).json().getLong("pieceId"))
        assertEquals(404, http.get("/api/studio/seed?piece=99", auth).status)
        assertEquals(400, http.get("/api/studio/seed?piece=x", auth).status)
        assertEquals(400, http.get("/api/studio/seed?piece=-3", auth).status)
        backend.defaultSeed = null
        assertEquals("an empty library has no seed", 404, http.get("/api/studio/seed", auth).status)
        backend.defaultSeed = 12L

        val sent = http.api("POST", "/api/studio/compose", """{"pieceId":13,"mood":"wild","key":{"tonic":9,"minor":true},"bpm":132,"minutes":1}""", session = token)
        assertEquals(sent.toString(), 202, sent.status)
        assertEquals(40L, sent.json().getLong("job"))
        assertEquals(ComposeOrder(13L, ComposeRequest(Mood.Wild, MusicKey(9, true), 132, 1)), backend.composed.single())
        assertEquals(202, http.api("POST", "/api/studio/compose", """{"mood":"calm","minutes":2}""", session = token).status)
        assertEquals("no piece chosen: the tablet's default", null, backend.composed.last().pieceId)

        assertEquals(400, http.api("POST", "/api/studio/compose", """{"mood":"calm","minutes":2,"prompt":"like Chopin"}""", session = token).status)
        assertEquals(400, http.api("POST", "/api/studio/compose", """{"mood":"calm","minutes":9}""", session = token).status)
        assertEquals(400, http.api("POST", "/api/studio/compose", """{"mood":"sad","minutes":2}""", session = token).status)
        assertEquals("a piece no longer there", 404, http.api("POST", "/api/studio/compose", """{"pieceId":77,"mood":"calm","minutes":2}""", session = token).status)
        backend.studioHeld = WebStudio(available = false, reason = "Studio isn't available on this device.")
        val unavailable = http.api("POST", "/api/studio/compose", """{"mood":"calm","minutes":2}""", session = token)
        assertEquals(409, unavailable.status)
        assertEquals("Studio isn't available on this device.", unavailable.json().getString("message"))
        assertEquals("nothing more was queued", 2, backend.composed.size)
    }

    @Test
    fun `Studio takes no more from the panel while it has eight jobs to do (audit delta 2)`() {
        val (_, http) = start()
        val token = login(http)
        val auth = mapOf("X-Steven-Piano" to "1", "Cookie" to "sp_session=$token")
        fun jobs(toDo: Int, ended: Int) = List(toDo) { WebStudioJob(it + 1L, "compose", "a piece", if (it == 0) "running" else "queued", "", null, null) } +
            List(ended) { WebStudioJob(100L + it, "transcribe", "a take", "done", "", null, null) }
        backend.studioHeld = WebStudio(available = true, jobs = jobs(STUDIO_JOBS_MAX, 5))
        val refused = http.send("PUT", "/api/studio/audio?name=take.wav", null, auth + mapOf("Content-Length" to "150000000"), readTimeoutMs = 3_000)
        assertEquals("refused before a byte of the 150 MB is read", 409, refused.status)
        assertEquals("full", refused.json().getString("error"))
        assertEquals("Studio has 8 jobs to do already. Try again when one has finished.", refused.json().getString("message"))
        val composeRefused = http.api("POST", "/api/studio/compose", """{"mood":"calm","minutes":2}""", session = token)
        assertEquals(409, composeRefused.status)
        assertEquals("full", composeRefused.json().getString("error"))
        assertTrue("nothing reached Studio", backend.recordings.isEmpty() && backend.composed.isEmpty())
        assertEquals("nothing was kept", emptyList<String>(), backend.uploadDir.list()?.toList().orEmpty())

        // One job fewer to do (the finished ones don't count), and both are taken again.
        backend.studioHeld = WebStudio(available = true, jobs = jobs(STUDIO_JOBS_MAX - 1, 20))
        assertEquals(202, http.send("PUT", "/api/studio/audio?name=take.wav", ByteArray(1_000) { 1 }, auth).status)
        assertEquals(202, http.api("POST", "/api/studio/compose", """{"mood":"calm","minutes":2}""", session = token).status)
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
    fun `guests see each list's genre and the Modern list, and may ask for a Modern piece but never an unlisted one (v1_14 M37)`() {
        val (_, http) = start()
        val alone = http.get("/api/public/catalogue").json().getJSONArray("lists")
        assertEquals("no Modern piece: the built-in lists alone", 1, alone.length())
        assertEquals("classical", alone.getJSONObject(0).getString("genre"))
        backend.pieces += WebPiece(4, "Shape of You", "Ed Sheeran", "ed sheeran", 233_000, composerShort = "Ed Sheeran", genre = "modern")
        val lists = http.get("/api/public/catalogue").json().getJSONArray("lists")
        assertEquals(listOf("popular" to "classical", "modern" to "modern"), (0 until lists.length()).map { i -> lists.getJSONObject(i).let { it.getString("key") to it.getString("genre") } })
        assertEquals("Modern", lists.getJSONObject(1).getString("name"))
        val piece = lists.getJSONObject(1).getJSONArray("pieces").getJSONObject(0)
        assertEquals("a guest's piece is still its id, title and artist alone", setOf("id", "title", "composer"), piece.keys().asSequence().toSet())
        assertEquals("Ed Sheeran", piece.getString("composer"))
        fun ask(id: Long) = http.api("POST", "/api/public/request", """{"pieceId":$id}""", panel = false)
        val unlisted = ask(3)
        assertEquals("in the library but on no list", 400, unlisted.status)
        assertEquals("not-offered", unlisted.json().getString("error"))
        val asked = ask(4)
        assertEquals(asked.toString(), 202, asked.status)
        assertEquals(listOf("requested 4"), backend.calls.toList())
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
    fun `the poster shows the request page's address and its QR, on every listener, without a session`() {
        for (guestOnly in listOf(false, true)) {
            val (_, http) = start(guestOnly = guestOnly)
            backend.state = WebState(web = WebAddresses(null, null))
            assertEquals("no address yet", 503, http.get("/poster").status)
            backend.state = WebState(web = WebAddresses("http://100.101.2.3:8737", "http://192.168.1.20:8737/request"))
            val poster = http.get("/poster")
            assertEquals(200, poster.status)
            assertEquals("text/html; charset=utf-8", poster.header("content-type"))
            assertTrue(poster.text.contains("<p>http://192.168.1.20:8737/request</p>"))
            assertTrue(poster.text.contains("<svg class=\"qr\""))
            assertEquals(405, http.send("POST", "/poster", "x".toByteArray()).status)
        }
    }

    @Test
    fun `a Wi-Fi listener without Panel on Wi-Fi too serves guests only`() {
        val (server, http) = start(guestOnly = true)
        backend.pin = PIN
        for (path in listOf("/", "/app.js", "/api/state", "/api/library", "/api/piano", "/views.js", "/api/now/notes", "/api/font/bravura.otf")) assertEquals(path, 404, http.get(path).status)
        assertEquals("no logging in here", 404, http.api("POST", "/api/login", """{"pin":"482913"}""").status)
        assertEquals(404, http.api("POST", "/api/play", """{"pieceId":1}""").status)
        assertEquals(200, http.get("/request").status)
        assertEquals(200, http.get("/style.css").status)
        assertEquals(200, http.get("/api/public/catalogue").status)
        assertEquals(202, http.api("POST", "/api/public/request", """{"pieceId":1}""", panel = false).status)
        assertEquals(404, handshake(http, server.listeningPort, cookie = null).status)
        // Every route of the panel's is no route here, Studio's among them (audit delta 2: guests reach nothing Studio).
        val panelRoutes = server.routes.filter { it.access != WebServer.Access.PUBLIC }
        assertTrue(panelRoutes.map { it.sample }.containsAll(listOf("/api/studio/audio?name=a.wav", "/api/studio/compose", "/api/studio/jobs/1/cancel", "/api/studio/seed")))
        for (route in panelRoutes) {
            val (path, body) = sample(route)
            val answer = if (route.method.name == "GET") http.get(path) else http.api(route.method.name, path, body)
            assertEquals("${route.method} $path on the guests' listener", 404, answer.status)
        }
        assertTrue("nothing reached the app but the guest's request", backend.recordings.isEmpty() && backend.composed.isEmpty())
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
        assertEquals(204, put("""{"tabletVolume":45}"""))
        // The View control's four (v1.13 — M32).
        assertEquals(204, put("""{"noteDisplay":"falling","fingering":false,"chordNames":true,"handColours":true}"""))
        for (bad in listOf(
            """{"tabletVolume":-1}""", """{"tabletSound":"off"}""", """{"webEnabled":true}""", """{"webOnWifi":true}""", """{"webPinHash":"x"}""", "{}",
            """{"preRollMs":9000}""", """{"webHostName":"100.101.2.3"}""", """{"webHostName":"piano tablet"}""", """{"webGuests":"yes"}""",
            """{"noteDisplay":"score"}""", """{"noteDisplay":"STAFF"}""", """{"fingering":"on"}""", """{"notesSplitSide":0.5}""", """{"wideLayout":"NOTES_ONLY"}""",
        )) {
            assertEquals(bad, 400, put(bad))
        }
        assertEquals(4, backend.calls.size)
        assertTrue(backend.calls[0], "webGuests=true" in backend.calls[0] && "preRollMs=1500" in backend.calls[0])
        assertTrue(backend.calls[1], "webHostName=piano-tablet" in backend.calls[1])
        assertTrue(backend.calls[2], "tabletVolume=45" in backend.calls[2])
        assertTrue(backend.calls[3], listOf("noteDisplay=FALLING", "fingering=false", "chordNames=true", "handColours=true").all { it in backend.calls[3] })
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
        // v1.10.1 — M28: the panel lists the playlists in the order the app chose (the backend's), not by name.
        backend.playlistsHeld.add(0, WebPlaylist(14, "MIDI", 265, 3_600_000, builtIn = false))
        backend.playlistsHeld.add(WebPlaylist(8, "Popular", 17, 900_000, builtIn = true))
        val listed = http.get("/api/playlists", auth).json().getJSONArray("playlists")
        assertEquals(listOf("MIDI", "Evening", "Popular"), (0 until listed.length()).map { listed.getJSONObject(it).getString("name") })
        backend.playlistsHeld.removeAll { it.id != 10L }
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
    fun `the Library's reads follow the genre asked for, classical or modern, and refuse any other (v1_14 M37)`() {
        val (_, http) = start()
        val token = login(http)
        val auth = mapOf("Cookie" to "sp_session=$token")
        backend.pieces += WebPiece(4, "Shape of You", "Ed Sheeran", "ed sheeran", 233_000, composerShort = "Ed Sheeran", genre = "modern")
        for (path in listOf("/api/library", "/api/playlists", "/api/composers", "/api/composers/debussy")) {
            for (genre in listOf("weird", "Modern", "")) assertEquals("$path?genre=$genre", 400, http.get("$path?genre=$genre", auth).status)
        }
        assertEquals("genre must be classical or modern.", http.get("/api/library?genre=pop", auth).json().getString("message"))
        val modern = http.get("/api/library?genre=modern", auth).json()
        assertEquals(1, modern.getInt("total"))
        assertEquals("modern", modern.getJSONArray("pieces").getJSONObject(0).getString("genre"))
        assertEquals(3, http.get("/api/library?genre=classical", auth).json().getInt("total"))
        assertEquals("with a search too", 0, http.get("/api/library?q=clair&genre=modern", auth).json().getInt("total"))
        assertEquals("absent: every piece", 4, http.get("/api/library", auth).json().getInt("total"))
        val artists = http.get("/api/composers?genre=modern", auth).json().getJSONArray("composers")
        assertEquals("ed sheeran", artists.getJSONObject(0).getString("key"))
        assertEquals(1, artists.length())
        assertEquals(404, http.get("/api/composers/debussy?genre=modern", auth).status)
        assertEquals(200, http.get("/api/composers/debussy?genre=classical", auth).status)
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

    @Test
    fun `schedules are listed, made, changed and deleted, each checked as the app's editor checks them`() {
        val (_, http) = start()
        val token = login(http)
        val auth = mapOf("Cookie" to "sp_session=$token")
        backend.calls.clear()
        val empty = http.get("/api/schedules", auth).json()
        assertEquals(0, empty.getJSONArray("schedules").length())
        assertTrue(empty.isNull("next"))
        assertTrue(empty.getBoolean("exactAlarms"))

        val calm = """{"days":31,"startMinute":750,"kind":"channel","target":"calm","endMinute":795,"volumePct":70}"""
        val made = http.api("POST", "/api/schedules", calm, session = token)
        assertEquals(made.toString(), 201, made.status)
        val schedule = made.json().getJSONObject("schedule")
        assertEquals(1L, schedule.getLong("id"))
        assertEquals("Weekdays 12:30", schedule.getString("when"))
        assertEquals("Calm channel · until 13:15 · 70%", schedule.getString("what"))
        assertTrue("enabled unless it says otherwise", schedule.getBoolean("enabled"))

        fun post(json: String) = http.api("POST", "/api/schedules", json, session = token)
        val refused = listOf(
            """{"days":0,"startMinute":750,"kind":"channel","target":"calm"}""",
            """{"days":128,"startMinute":750,"kind":"channel","target":"calm"}""",
            """{"days":31,"startMinute":1440,"kind":"channel","target":"calm"}""",
            """{"days":31,"startMinute":750,"kind":"channel","target":"calm","endMinute":750}""",
            """{"days":31,"startMinute":"750","kind":"channel","target":"calm"}""",
            """{"days":31,"startMinute":750,"kind":"radio","target":"calm"}""",
            """{"days":31,"startMinute":750,"kind":"channel","target":"Calm Channel"}""",
            """{"days":31,"startMinute":750,"kind":"channel","target":"jazz"}""",
            """{"days":31,"startMinute":750,"kind":"playlist","target":"99"}""",
            """{"days":31,"startMinute":750,"kind":"piece","target":"0"}""",
            """{"days":31,"startMinute":750,"kind":"piece","target":3}""",
            """{"days":31,"startMinute":750,"kind":"channel","target":"calm","volumePct":101}""",
            """{"days":31,"startMinute":750,"kind":"channel","target":"calm","enabled":"yes"}""",
            """{"days":31,"startMinute":750,"kind":"channel","target":"calm","name":"Calm"}""",
            """{"days":31,"startMinute":750,"kind":"channel"}""",
        )
        for (body in refused) assertEquals(body, 400, post(body).status)
        assertEquals("the day rule in the app's words", "Choose at least one day.", post(refused[0]).json().getString("message"))
        assertEquals("That channel, playlist or piece isn't in the library.", post(refused[7]).json().getString("message"))
        assertEquals("only the first save reached the app", 1, backend.calls.size)

        assertEquals(201, post("""{"days":64,"startMinute":1410,"kind":"playlist","target":"10","endMinute":30,"volumePct":null,"enabled":false}""").status)
        assertEquals(201, post("""{"days":127,"startMinute":420,"kind":"piece","target":"3"}""").status)
        val listed = http.get("/api/schedules", auth).json()
        val rows = listed.getJSONArray("schedules")
        assertEquals("by start time", listOf(3L, 1L, 2L), (0 until rows.length()).map { rows.getJSONObject(it).getLong("id") })
        val late = rows.getJSONObject(2)
        assertEquals("Sundays 23:30", late.getString("when"))
        assertEquals("Evening · until 00:30", late.getString("what"))
        assertTrue(late.isNull("volumePct"))
        assertFalse(late.getBoolean("enabled"))
        assertEquals("no volume given: none", "Für Elise · until the end", rows.getJSONObject(0).getString("what"))
        assertEquals("Next: Wednesday 12:30, Calm", listed.getString("next"))

        val changed = http.api("PUT", "/api/schedules/1", """{"days":31,"startMinute":760,"kind":"channel","target":"calm","endMinute":null,"volumePct":40,"enabled":false}""", session = token)
        assertEquals(204, changed.status)
        assertEquals(760, backend.schedulesHeld.getValue(1).startMinute)
        assertEquals(null, backend.schedulesHeld.getValue(1).endMinute)
        assertEquals(404, http.api("PUT", "/api/schedules/99", calm, session = token).status)
        assertEquals(400, http.api("PUT", "/api/schedules/1", """{"days":31}""", session = token).status)
        assertEquals("no id 0: a new schedule is a POST", 404, http.api("PUT", "/api/schedules/0", calm, session = token).status)
        val patch = http.api("PATCH", "/api/schedules/1", calm, session = token)
        assertEquals(405, patch.status)
        assertEquals("PUT, DELETE", patch.header("allow"))

        assertEquals(204, http.api("DELETE", "/api/schedules/1", session = token).status)
        assertEquals("deleted once", 404, http.api("DELETE", "/api/schedules/1", session = token).status)
        assertEquals(2, backend.schedulesHeld.size)

        repeat(ScheduleRules.MAX_SCHEDULES - 2) { assertEquals(201, post(calm).status) }
        val full = post(calm)
        assertEquals(409, full.status)
        assertEquals("too-many", full.json().getString("error"))

        backend.exactAlarms = false
        backend.lastOutcome = "Missed: Wednesday 12:30 (piano not connected)"
        val told = http.get("/api/schedules", auth).json()
        assertFalse(told.getBoolean("exactAlarms"))
        assertEquals("Missed: Wednesday 12:30 (piano not connected)", told.getString("last"))
    }

    @Test
    fun `a listening socket that keeps failing pauses a little longer each time, then closes itself`() {
        // A socket Android destroyed with its address fails every accept at once; NanoHTTPD would retry without end.
        val pauses = mutableListOf<Long>()
        var failing = true
        val socket = object : SteadyServerSocket(pause = { pauses += it }) {
            override fun acceptOnce(): Socket = if (failing) throw java.net.SocketException("Software caused connection abort") else Socket()
        }
        socket.use {
            assertTrue(runCatching { it.accept() }.exceptionOrNull() is java.io.IOException)
            assertEquals(1, it.failuresInARow)
            failing = false
            it.accept().close()
            assertEquals("a success starts the count again", 0, it.failuresInARow)
            failing = true
            repeat(SteadyServerSocket.MAX_ACCEPT_FAILURES) { _ -> assertTrue(runCatching { it.accept() }.isFailure) }
            assertTrue("closed after twenty in a row, which ends NanoHTTPD's loop", it.isClosed)
            val expected = listOf(20L) + (1 until SteadyServerSocket.MAX_ACCEPT_FAILURES).map { n -> minOf(n * 20L, 500L) }
            assertEquals(expected, pauses)
            assertTrue("never more than half a second at a time", pauses.all { ms -> ms <= SteadyServerSocket.MAX_ACCEPT_PAUSE_MS })
        }
    }

    /** [bytes] sent exactly as they are, and the answer read; null when the server closed without one. */
    private fun rawAnswer(port: Int, bytes: ByteArray): RawHttp.Answer? {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            socket.soTimeout = 15_000
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
            return try {
                RawHttp.read(socket.getInputStream())
            } catch (e: IllegalArgumentException) {
                null   // no complete response: the connection just ended
            }
        }
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
