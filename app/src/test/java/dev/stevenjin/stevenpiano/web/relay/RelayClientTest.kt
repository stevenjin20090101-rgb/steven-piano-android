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
import dev.stevenjin.stevenpiano.web.LoginGuard
import dev.stevenjin.stevenpiano.web.Sessions
import dev.stevenjin.stevenpiano.web.WebApi
import dev.stevenjin.stevenpiano.web.WebServer
import dev.stevenjin.stevenpiano.web.WebSocketHub
import dev.stevenjin.stevenpiano.web.WebState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

/**
 * The tablet's side of Steven Piano Cloud against a relay of the tests' own ([FakeRelay], NanoWSD over
 * `ws://` on 127.0.0.1), with the real OkHttp client and the web panel's real server behind it:
 * the handshake, the hello and the status, a request's round trip, an upload under credits, the
 * requests-in-flight cap and aborts, the bridged sockets, a console command, a secret's rotation,
 * the close codes, and the waits between tries.
 */
class RelayClientTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val relay = FakeRelay()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = Sessions()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val statusChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val commandsRun: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val kept: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val waits: MutableList<Long> = Collections.synchronizedList(mutableListOf())
    private val clients = mutableListOf<RelayClient>()

    @Volatile
    private var secret = "S".repeat(43)

    @Volatile
    private var now = 1_000L

    private lateinit var backend: FakeWebBackend
    private lateinit var hub: WebSocketHub
    private lateinit var server: WebServer

    @Before
    fun setUp() {
        relay.begin()
        backend = FakeWebBackend(tmp.newFolder("uploads"))
        hub = WebSocketHub(
            stateMessage = { WebApi.state(WebState(), 0, type = "state").toString() },
            progressMessage = { null },
            sessionValid = sessions::isValid,
            pingMs = 200,
        )
        hub.start(scope, changes)
        server = WebServer(WebServer.Config("relay", 0, tempDir = tmp.newFolder()), backend, sessions, LoginGuard(), GuestRequests(), AssetSource { null }, hub)
    }

    @After
    fun tearDown() {
        clients.forEach { it.stop() }
        hub.stop()
        relay.stop()
        scope.cancel()
    }

    private fun newClient(
        sleep: suspend (Long) -> Unit = { delay(it) },
        random: () -> Double = { 0.5 },
        url: () -> String = { "ws://127.0.0.1:${relay.listeningPort}/tablet" },
    ): RelayClient = RelayClient(
        config = { RelayConfig(url(), PIANO, secret) },
        server = server,
        hub = hub,
        commands = { name, args ->
            commandsRun += "$name $args"
            CommandResult(true, "Done: $name")
        },
        status = object : StatusSource {
            override suspend fun report(): JSONObject = JSONObject().put("app", JSONObject().put("version", "1.10").put("code", 18)).put("at", now)

            override val changes = statusChanges
        },
        secrets = { given ->
            kept += given
            secret = given
            true
        },
        scope = scope,
        clock = { now },
        random = random,
        sleep = sleep,
        statusSettleMs = 50,
    ).also { clients += it }

    /** A client connected and greeted: the relay's side of its connection. */
    private fun connected(client: RelayClient = newClient(), caps: Caps = Caps()): FakeRelay.Tablet {
        client.start()
        val tablet = relay.next()
        tablet.say(RelayMessage.Hello(PIANO, HOST, PREFIX, caps, 5))
        waitFor { client.state.value is CloudStatus.Connected }
        return tablet
    }

    @Test
    fun `the handshake carries the bearer and the subprotocol, the hello connects it and a status follows, and another after a change`() {
        val client = newClient()
        client.start()
        val tablet = relay.next()
        assertEquals("Bearer $PIANO.${"S".repeat(43)}", relay.authorizations.single())
        assertEquals(RelayProtocol.SUBPROTOCOL, relay.protocols.single())
        assertEquals(CloudStatus.Connecting, client.state.value)
        tablet.say(RelayMessage.Hello(PIANO, HOST, PREFIX, Caps(), 5))
        waitFor { client.state.value is CloudStatus.Connected }
        assertEquals(CloudStatus.Connected(HOST, PIANO, now), client.state.value)
        val status = tablet.await<RelayMessage.Status>()
        assertEquals("1.10", status.body.getJSONObject("app").getString("version"))
        assertEquals(18, status.body.getJSONObject("app").getInt("code"))
        now = 2_000
        statusChanges.tryEmit(Unit)
        assertEquals(2_000L, tablet.await<RelayMessage.Status>().body.getLong("at"))
    }

    @Test
    fun `a request goes to the web panel and its answer comes back with its headers and body`() {
        val tablet = connected()
        val token = sessions.open()
        tablet.say(RelayMessage.Req(7, "GET", "/api/state", "", mapOf("host" to HOST, "cookie" to "sp_session=$token"), "203.0.113.9", PREFIX, false))
        val res = tablet.await<RelayMessage.Res> { it.id == 7L }
        assertEquals(200, res.status)
        assertEquals("application/json; charset=utf-8", res.headers["Content-Type"])
        assertEquals("no-store", res.headers["Cache-Control"])
        assertTrue(res.headers["Content-Security-Policy"]!!.contains("connect-src 'self' wss://$HOST;"))
        assertEquals("nosniff", res.headers["X-Content-Type-Options"])
        val body = tablet.body(7)
        assertEquals(res.length, body.size.toLong())
        assertTrue(JSONObject(String(body)).has("player"))

        tablet.say(RelayMessage.Req(8, "GET", "/api/state", "", mapOf("host" to HOST), "203.0.113.9", PREFIX, false))
        assertEquals("no session", 401, tablet.await<RelayMessage.Res> { it.id == 8L }.status)
        tablet.body(8)
        tablet.say(RelayMessage.Req(9, "POST", "/api/play", "", mapOf("host" to HOST, "cookie" to "sp_session=$token", "x-steven-piano" to "1", "content-type" to "application/json", "content-length" to "13", "origin" to "https://$HOST"), "203.0.113.9", PREFIX, true))
        tablet.say(Frame(9, Frame.REQ_CHUNK, """{"pieceId":1}""".toByteArray()))
        tablet.say(Frame(9, Frame.REQ_END))
        assertEquals(204, tablet.await<RelayMessage.Res> { it.id == 9L }.status)
        assertEquals("an answer without a body still ends", 0, tablet.body(9).size)
        assertEquals(listOf("play 1 queue=null"), backend.calls.toList())
    }

    @Test
    fun `an upload streams under the credit window, a chunk at a time, and reaches the app whole`() {
        val window = 128 * 1024
        val tablet = connected(caps = Caps(chunk = 64 * 1024, window = window))
        val token = sessions.open()
        val zip = ByteArray(1_000_000) { (it % 251).toByte() }
        val headers = mapOf(
            "host" to HOST, "cookie" to "sp_session=$token", "x-steven-piano" to "1", "origin" to "https://$HOST",
            "content-length" to zip.size.toString(), "content-type" to "application/zip",
        )
        tablet.say(RelayMessage.Req(21, "PUT", "/api/upload", "name=pieces.zip", headers, "203.0.113.9", PREFIX, true))
        var credit = window.toLong()
        var granted = 0L
        var sent = 0
        while (sent < zip.size) {
            while (credit <= 0) {
                val more = tablet.await<RelayMessage.ReqCredit> { it.id == 21L }.bytes
                credit += more
                granted += more
            }
            val n = minOf(64 * 1024, zip.size - sent, credit.toInt())
            tablet.say(Frame(21, Frame.REQ_CHUNK, zip.copyOfRange(sent, sent + n)))
            credit -= n
            sent += n
        }
        tablet.say(Frame(21, Frame.REQ_END))
        val res = tablet.await<RelayMessage.Res> { it.id == 21L }
        assertEquals(202, res.status)
        assertEquals("pieces.zip", JSONObject(String(tablet.body(21))).getString("name"))
        assertEquals(listOf("zip pieces.zip 1000000"), backend.imported.toList())
        assertTrue("credit came back as the body was read: $granted", granted >= zip.size - window)
    }

    @Test
    fun `eight requests at most are answered at once, past them 503 busy, and an aborted one gets no answer`() {
        val tablet = connected()
        val token = sessions.open()
        val headers = mapOf("host" to HOST, "cookie" to "sp_session=$token", "x-steven-piano" to "1", "content-type" to "application/json", "content-length" to "13", "origin" to "https://$HOST")
        // Eight requests whose bodies never come hold the tablet's eight places.
        for (id in 100L until 108L) tablet.say(RelayMessage.Req(id, "POST", "/api/play", "", headers, "203.0.113.9", PREFIX, true))
        Thread.sleep(300)
        tablet.say(RelayMessage.Req(200, "GET", "/api/state", "", mapOf("host" to HOST, "cookie" to "sp_session=$token"), "203.0.113.9", PREFIX, false))
        val busy = tablet.await<RelayMessage.Res> { it.id == 200L }
        assertEquals(503, busy.status)
        assertEquals("busy", JSONObject(String(tablet.body(200))).getString("error"))
        assertTrue(busy.headers["Content-Security-Policy"]!!.contains("wss://$HOST"))
        // The relay gives up on them: each ends, and none is answered.
        for (id in 100L until 108L) tablet.say(RelayMessage.ReqAbort(id))
        Thread.sleep(300)
        tablet.say(RelayMessage.Req(201, "GET", "/api/state", "", mapOf("host" to HOST, "cookie" to "sp_session=$token"), "203.0.113.9", PREFIX, false))
        assertEquals("places free again", 200, tablet.await<RelayMessage.Res> { it.id == 201L }.status)
        assertFalse(tablet.sent { it is RelayMessage.Res && it.id in 100L until 108L })
        assertEquals("nothing reached the app", emptyList<String>(), backend.calls.toList())
    }

    @Test
    fun `a browser's socket is bridged for a session from the panel's origin, hears the hub, and ends either way`() {
        val tablet = connected()
        val token = sessions.open()
        val headers = mapOf("cookie" to "sp_session=$token", "origin" to "https://$HOST", "host" to HOST)
        tablet.say(RelayMessage.WsOpen(3, headers, "203.0.113.9"))
        tablet.await<RelayMessage.WsAccept> { it.id == 3L }
        assertEquals("state", JSONObject(tablet.await<RelayMessage.WsText> { it.id == 3L }.data).getString("type"))
        assertEquals(1, hub.memberCount)
        changes.tryEmit(Unit)
        assertEquals("state", JSONObject(tablet.await<RelayMessage.WsText> { it.id == 3L }.data).getString("type"))
        tablet.say(RelayMessage.WsClose(3, 1001, "The browser went."))
        waitFor { hub.memberCount == 0 }

        tablet.say(RelayMessage.WsOpen(4, headers - "cookie", "203.0.113.9"))
        assertEquals(401, tablet.await<RelayMessage.WsRefuse> { it.id == 4L }.status)
        tablet.say(RelayMessage.WsOpen(5, headers + ("origin" to "https://evil.example"), "203.0.113.9"))
        assertEquals(403, tablet.await<RelayMessage.WsRefuse> { it.id == 5L }.status)
        tablet.say(RelayMessage.WsOpen(6, headers + ("host" to "evil.example"), "203.0.113.9"))
        assertEquals(403, tablet.await<RelayMessage.WsRefuse> { it.id == 6L }.status)

        tablet.say(RelayMessage.WsOpen(7, headers, "203.0.113.9"))
        tablet.await<RelayMessage.WsAccept> { it.id == 7L }
        sessions.close(token)
        assertEquals("its session ended", WebSocketHub.CLOSE_SESSION_ENDED, tablet.await<RelayMessage.WsClose> { it.id == 7L }.code)
        assertEquals(0, hub.memberCount)
    }

    @Test
    fun `a console command is run and its result answered`() {
        val tablet = connected()
        tablet.say(RelayMessage.Cmd(11, "transport", mapOf("action" to "next")))
        assertEquals(RelayMessage.CmdResult(11, true, "Done: transport"), tablet.await<RelayMessage.CmdResult>())
        assertEquals(listOf("transport {action=next}"), commandsRun.toList())
    }

    @Test
    fun `a new secret is kept, acknowledged, and used for the next connection`() {
        val client = newClient(sleep = { delay(10) })
        val tablet = connected(client)
        val fresh = "N".repeat(43)
        tablet.say(RelayMessage.Secret(fresh))
        tablet.await<RelayMessage.SecretAck>()
        assertEquals(listOf(fresh), kept.toList())
        tablet.closeWith(1011, "The room restarted.")
        relay.next()
        assertEquals("Bearer $PIANO.$fresh", relay.authorizations.last())
    }

    @Test
    fun `4401 and a refused secret stop the tries as revoked, 4403 as turned off`() {
        val revoked = newClient(sleep = { delay(10) })
        connected(revoked).closeWith(RelayProtocol.CLOSE_REVOKED, "Revoked from the console.")
        waitFor { revoked.state.value == CloudStatus.Revoked }
        Thread.sleep(300)
        assertEquals("no try after", 1, relay.authorizations.size)
        revoked.stop()

        val disabled = newClient(sleep = { delay(10) })
        connected(disabled).closeWith(RelayProtocol.CLOSE_DISABLED, "Removed from the console.")
        waitFor { disabled.state.value == CloudStatus.Disabled }
        disabled.stop()

        relay.refuseWith = 401
        val refused = newClient(sleep = { delay(10) })
        refused.start()
        waitFor { refused.state.value == CloudStatus.Revoked }
        Thread.sleep(300)
        assertEquals(3, relay.authorizations.size)
    }

    @Test
    fun `the waits between tries double from a second to five minutes, with a fifth either way`() {
        relay.refuseWith = 503
        val client = newClient(sleep = { ms ->
            waits += ms
            if (waits.size >= 12) awaitCancellation()
        })
        client.start()
        waitFor { waits.size >= 12 }
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 32_000, 64_000, 128_000, 256_000, 300_000, 300_000, 300_000), waits.toList())
        waitFor { client.state.value is CloudStatus.Waiting }
        assertEquals(CloudStatus.Waiting("The relay can't be reached", 300_000, now), client.state.value)
        val low = newClient(random = { 0.0 })
        val high = newClient(random = { 1.0 })
        assertEquals(listOf(800L, 1_600, 240_000), listOf(low.backoff(0), low.backoff(1), low.backoff(12)))
        assertEquals(listOf(1_200L, 2_400, 360_000), listOf(high.backoff(0), high.backoff(1), high.backoff(12)))
    }

    @Test
    fun `a hello starts the count again, 4409 waits a minute, and a nudge ends a wait at once`() {
        val client = newClient(sleep = { ms ->
            waits += ms
            delay(if (ms >= RelayClient.REPLACED_WAIT_MS) 60_000 else 20)
        })
        connected(client).closeWith(1011, "Going away.")
        relay.next().closeWith(1011, "Not yet.")   // no hello: the count goes on
        connected(client).closeWith(RelayProtocol.CLOSE_REPLACED, "Replaced by a newer connection.")
        waitFor { client.state.value is CloudStatus.Waiting && waits.size == 3 }
        assertEquals(listOf(1_000L, 2_000, 60_000), waits.toList())
        assertEquals("Another tablet connected with this enrolment", (client.state.value as CloudStatus.Waiting).reason)
        client.nudge()
        relay.next(5)
        assertEquals(4, relay.authorizations.size)
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out")
            Thread.sleep(10)
        }
    }

    private companion object {
        const val PIANO = "abcdefgh2345"
        const val HOST = "relay.test"
        const val PREFIX = "/p/abcdefgh2345"
    }
}
