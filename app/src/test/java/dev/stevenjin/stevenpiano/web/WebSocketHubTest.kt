// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The panel's socket (the audit's point 2 and the socket's rules): state on open and on change, progress, pings, bounds. */
class WebSocketHubTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = Sessions()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val built = AtomicInteger()
    private val playing = AtomicReference<String?>(null)
    private lateinit var hub: WebSocketHub
    private lateinit var server: WebServer

    @Before
    fun setUp() {
        hub = WebSocketHub(
            stateMessage = { WebApi.state(WebState(), built.incrementAndGet(), type = "state").toString() },
            progressMessage = { playing.get() },
            sessionValid = sessions::isValid,
            pingMs = 200,
            progressMs = 250,
        )
        hub.start(scope, changes)
        val backend = FakeWebBackend(tmp.newFolder("uploads"))
        server = WebServer(WebServer.Config("127.0.0.1", 0, tempDir = tmp.newFolder()), backend, sessions, LoginGuard(), GuestRequests(), { null }, hub)
        server.startListening()
    }

    @After
    fun tearDown() {
        hub.stop()
        server.stop()
        scope.cancel()
    }

    @Test
    fun `a socket gets the state as it opens, then each change, coalesced to ten a second`() {
        val token = sessions.open()
        Client(server.listeningPort, token).use { client ->
            assertEquals(101, client.handshake())
            val first = JSONObject(client.text())
            assertEquals("state", first.getString("type"))
            assertTrue(first.has("player"))
            val before = built.get()
            repeat(200) { changes.tryEmit(Unit) }
            Thread.sleep(700)
            val messages = client.textsFor(300)
            val states = messages.count { JSONObject(it).getString("type") == "state" }
            assertTrue("some state went out: $states", states >= 1)
            assertTrue("coalesced: ${built.get() - before} built for 200 changes", built.get() - before <= 11)
        }
    }

    @Test
    fun `while a piece plays, progress once a second (here a quarter)`() {
        val token = sessions.open()
        Client(server.listeningPort, token).use { client ->
            assertEquals(101, client.handshake())
            client.text()
            playing.set(WebApi.progress(12_000, 1).toString())
            val progress = client.textsFor(900).map { JSONObject(it) }.filter { it.getString("type") == "progress" }
            assertTrue("several progress messages: ${progress.size}", progress.size >= 2)
            assertEquals(12_000, progress.first().getLong("positionMs"))
        }
    }

    @Test
    fun `two sockets at most`() {
        val a = Client(server.listeningPort, sessions.open())
        val b = Client(server.listeningPort, sessions.open())
        val c = Client(server.listeningPort, sessions.open())
        try {
            assertEquals(101, a.handshake())
            a.text()
            assertEquals(101, b.handshake())
            b.text()
            assertEquals(2, hub.count)
            assertEquals(503, c.handshake())
        } finally {
            listOf(a, b, c).forEach { it.close() }
        }
    }

    @Test
    fun `a socket whose session ends is closed at its next ping`() {
        val token = sessions.open()
        Client(server.listeningPort, token).use { client ->
            assertEquals(101, client.handshake())
            client.text()
            sessions.close(token)
            assertEquals("a close frame", 0x8, client.untilClose())
        }
        Thread.sleep(100)
        assertEquals(0, hub.count)
    }

    @Test
    fun `a frame too large for the guard ends the socket before its payload is read`() {
        val token = sessions.open()
        Client(server.listeningPort, token).use { client ->
            assertEquals(101, client.handshake())
            client.text()
            // Claims 2 GB: NanoWSD alone would try to allocate it.
            client.raw(byteArrayOf(0x81.toByte(), (0x80 or 127).toByte(), 0, 0, 0, 0, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 1, 2, 3, 4))
            client.untilClose()
        }
        Thread.sleep(200)
        assertEquals(0, hub.count)
    }

    @Test
    fun `a socket outlives a request's deadline, its reads keeping only the per-read timeout (audit W3)`() {
        val quick = WebServer(
            WebServer.Config("127.0.0.1", 0, tempDir = tmp.newFolder(), requestDeadlineMs = 500),
            FakeWebBackend(tmp.newFolder("quick-uploads")), sessions, LoginGuard(), GuestRequests(), { null }, hub,
        )
        quick.startListening()
        try {
            Client(quick.listeningPort, sessions.open()).use { client ->
                assertEquals(101, client.handshake())
                client.text()
                Thread.sleep(1_500)   // three deadlines, the page silent meanwhile (its pongs unsent)
                changes.tryEmit(Unit)
                assertEquals("still open, still told", "state", JSONObject(client.text()).getString("type"))
            }
        } finally {
            quick.stop()
        }
    }

    @Test
    fun `stopping the hub closes every socket`() {
        val client = Client(server.listeningPort, sessions.open())
        client.use {
            assertEquals(101, it.handshake())
            it.text()
            hub.stop()
            it.untilClose()
        }
        assertEquals(0, hub.count)
    }

    @Test
    fun `a relayed browser hears the state as it attaches, then each change and the progress (v1_10 M26)`() {
        val member = Recorder()
        assertTrue(hub.attach(sessions.open(), member::send, member::close) != null)
        member.waitFor { it.size >= 1 }
        assertEquals("state", JSONObject(member.texts.first()).getString("type"))
        changes.tryEmit(Unit)
        member.waitFor { it.size >= 2 }
        assertEquals("state", JSONObject(member.texts[1]).getString("type"))
        playing.set(WebApi.progress(9_000, 1).toString())
        member.waitFor { list -> list.any { JSONObject(it).getString("type") == "progress" } }
        playing.set(null)
        assertEquals("never pinged, never closed", emptyList<String>(), member.closes.toList())
    }

    @Test
    fun `four relayed browsers at most, apart from the listeners' two sockets`() {
        val members = (1..WebSocketHub.MAX_MEMBERS).map { Recorder().also { r -> assertTrue(hub.attach(sessions.open(), r::send, r::close) != null) } }
        assertEquals(4, hub.memberCount)
        assertTrue(!hub.hasMemberRoom())
        val fifth = Recorder()
        assertEquals(null, hub.attach(sessions.open(), fifth::send, fifth::close))
        assertTrue("the listeners' sockets keep their own room", hub.hasRoom())
        Client(server.listeningPort, sessions.open()).use { client ->
            assertEquals(101, client.handshake())
            assertEquals("state", JSONObject(client.text()).getString("type"))
            // A change reaches the listener's socket and every member alike.
            changes.tryEmit(Unit)
            assertEquals("state", JSONObject(client.text()).getString("type"))
        }
        for (member in members) member.waitFor { it.size >= 2 }
    }

    @Test
    fun `a relayed browser whose session ends is closed at the next ping, and a detached one hears nothing more`() {
        val token = sessions.open()
        val ending = Recorder()
        hub.attach(token, ending::send, ending::close)
        val leaving = Recorder()
        val member = hub.attach(sessions.open(), leaving::send, leaving::close)!!
        ending.waitFor { it.isNotEmpty() }
        leaving.waitFor { it.isNotEmpty() }
        member.detach()
        sessions.close(token)
        val deadline = System.currentTimeMillis() + 2_000
        while (ending.closes.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("4000 The session has ended."), ending.closes.toList())
        assertEquals(0, hub.memberCount)
        val heard = leaving.texts.size
        changes.tryEmit(Unit)
        Thread.sleep(400)
        assertEquals("detached: nothing more", heard, leaving.texts.size)
        assertEquals("and never told to close", emptyList<String>(), leaving.closes.toList())
    }

    @Test
    fun `stopping the hub closes the relayed browsers too`() {
        val member = Recorder()
        hub.attach(sessions.open(), member::send, member::close)
        hub.stop()
        assertEquals(listOf("1001 The panel is off."), member.closes.toList())
        assertEquals(0, hub.memberCount)
    }

    /** A relayed browser's side as the relay client keeps it: what the hub sends and why it closes. */
    private class Recorder {
        val texts: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val closes: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        fun send(text: String) {
            texts += text
        }

        fun close(code: Int, reason: String) {
            closes += "$code $reason"
        }

        fun waitFor(condition: (List<String>) -> Boolean) {
            val end = System.currentTimeMillis() + 3_000
            while (!condition(texts.toList())) {
                if (System.currentTimeMillis() > end) throw AssertionError("Timed out: $texts")
                Thread.sleep(20)
            }
        }
    }

    @Test
    fun `the guard passes a masked frame and refuses large, unmasked or endlessly fragmented ones`() {
        val ok = NanoWSD.WebSocketFrame.read(FrameGuard(ByteArrayInputStream(frame(0x81, "ping".toByteArray()))))
        assertEquals("ping", ok.textPayload)
        val two = frame(0x81, "a".toByteArray()) + frame(0x81, "b".toByteArray())
        val stream = FrameGuard(ByteArrayInputStream(two))
        assertEquals("a", NanoWSD.WebSocketFrame.read(stream).textPayload)
        assertEquals("b", NanoWSD.WebSocketFrame.read(stream).textPayload)

        fun refused(bytes: ByteArray, code: NanoWSD.WebSocketFrame.CloseCode) {
            val guard = FrameGuard(ByteArrayInputStream(bytes))
            val e = assertThrows(NanoWSD.WebSocketException::class.java) { while (true) NanoWSD.WebSocketFrame.read(guard) }
            assertEquals(code, e.code)
        }
        refused(frame(0x81, ByteArray(FrameGuard.MAX_PAYLOAD + 1)), NanoWSD.WebSocketFrame.CloseCode.MessageTooBig)
        refused(byteArrayOf(0x81.toByte(), (0x80 or 127).toByte(), 0, 0, 0, 1, 0, 0, 0, 0), NanoWSD.WebSocketFrame.CloseCode.MessageTooBig)
        refused(byteArrayOf(0x81.toByte(), 4, 'p'.code.toByte(), 'i'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte()), NanoWSD.WebSocketFrame.CloseCode.ProtocolError)
        val fragments = ByteArrayOutputStream()
        fragments.write(frame(0x01, "x".toByteArray()))   // text, not final
        repeat(FrameGuard.MAX_FRAGMENTS + 1) {
            fragments.write(frame(0x89, ByteArray(0)))       // a ping between fragments does not reset the count
            fragments.write(frame(0x00, "x".toByteArray()))  // continuation, not final
        }
        refused(fragments.toByteArray(), NanoWSD.WebSocketFrame.CloseCode.MessageTooBig)
        assertEquals(4_096, FrameGuard.MAX_PAYLOAD)
    }

    /** A masked frame as a browser sends it. */
    private fun frame(head: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(head)
        when {
            payload.size < 126 -> out.write(0x80 or payload.size)
            else -> {
                out.write(0x80 or 126)
                out.write(payload.size shr 8)
                out.write(payload.size and 0xFF)
            }
        }
        val mask = byteArrayOf(1, 2, 3, 4)
        out.write(mask)
        payload.forEachIndexed { i, b -> out.write(b.toInt() xor mask[i % 4].toInt()) }
        return out.toByteArray()
    }

    /** A socket opened by hand: the handshake with the session cookie and the panel's origin, then frames read and written raw. */
    private class Client(port: Int, private val token: String) : AutoCloseable {
        private val socket = Socket().apply {
            connect(InetSocketAddress("127.0.0.1", port))
            soTimeout = 5_000
        }
        private val input: InputStream = socket.getInputStream()
        private val host = "127.0.0.1:$port"

        fun handshake(): Int {
            val request = "GET /ws HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\nOrigin: http://$host\r\nCookie: sp_session=$token\r\n\r\n"
            socket.getOutputStream().write(request.toByteArray())
            val head = ByteArrayOutputStream()
            while (!head.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                head.write(b)
            }
            return head.toString(Charsets.ISO_8859_1.name()).split(' ')[1].toInt()
        }

        /** The next text message, answering pings on the way; past [deadline] a timeout. */
        fun text(deadline: Long = Long.MAX_VALUE): String {
            while (true) {
                if (deadline != Long.MAX_VALUE) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) throw SocketTimeoutException("deadline")
                    socket.soTimeout = left.toInt()
                }
                val (opcode, payload) = frame()
                when (opcode) {
                    0x1 -> return payload.toString(Charsets.UTF_8)
                    0x9 -> raw(maskedPong(payload))
                    0x8 -> throw EOFException("closed")
                }
            }
        }

        /** Every text message that arrives within [ms]. */
        fun textsFor(ms: Long): List<String> {
            val end = System.currentTimeMillis() + ms
            val texts = mutableListOf<String>()
            while (System.currentTimeMillis() < end) {
                try {
                    texts += text(deadline = end)
                } catch (e: SocketTimeoutException) {
                    break
                }
            }
            socket.soTimeout = 5_000
            return texts
        }

        /** Reads until the server closes: its close frame's opcode, or -1 when the connection just ends; at most 5 s. */
        fun untilClose(): Int {
            val deadline = System.currentTimeMillis() + 5_000
            while (true) {
                if (System.currentTimeMillis() > deadline) throw AssertionError("The server never closed the socket")
                val (opcode, _) = try {
                    frame()
                } catch (e: EOFException) {
                    return -1
                } catch (e: java.net.SocketException) {
                    return -1
                }
                if (opcode == 0x8) return opcode
            }
        }

        fun raw(bytes: ByteArray) {
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }

        private fun maskedPong(payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x8A)
            out.write(0x80 or payload.size)
            val mask = byteArrayOf(9, 8, 7, 6)
            out.write(mask)
            payload.forEachIndexed { i, b -> out.write(b.toInt() xor mask[i % 4].toInt()) }
            return out.toByteArray()
        }

        /** One server frame (never masked): its opcode and payload. */
        private fun frame(): Pair<Int, ByteArray> {
            val b0 = read()
            val b1 = read()
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) length = ((read() shl 8) or read()).toLong()
            if (length == 127L) {
                length = 0
                repeat(8) { length = (length shl 8) or read().toLong() }
            }
            val payload = ByteArray(length.toInt())
            var at = 0
            while (at < payload.size) {
                val n = input.read(payload, at, payload.size - at)
                if (n < 0) throw EOFException()
                at += n
            }
            return (b0 and 0x0F) to payload
        }

        private fun read(): Int = input.read().also { if (it < 0) throw EOFException() }

        override fun close() = socket.close()
    }
}
