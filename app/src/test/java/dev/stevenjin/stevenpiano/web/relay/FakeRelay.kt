// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The relay's side of `steven-piano-relay-1` for the tests: NanoWSD on 127.0.0.1 over `ws://`. It
 * records every handshake's `Authorization` and subprotocol, refuses handshakes with [refuseWith]
 * while it is set, and hands each tablet connection to the test ([connections]) as a [Tablet] that
 * sends the room's messages and frames and keeps what the tablet sends.
 */
class FakeRelay : NanoWSD("127.0.0.1", 0) {
    val connections = LinkedBlockingQueue<Tablet>()
    val authorizations: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val protocols: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** While set, every handshake is refused with this status. */
    @Volatile
    var refuseWith: Int? = null

    /** While set, the handshake's answer names this subprotocol instead of echoing the tablet's (audit delta 3). */
    @Volatile
    var protocolAnswer: String? = null

    /** Messages and frames each connection sends the moment its handshake is answered, before it reads anything. */
    @Volatile
    var greeting: List<Any> = emptyList()

    /** Listens with no read timeout: a tablet's connection may be quiet between its pings. */
    fun begin() = start(0, false)

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/tablet") return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not here.")
        authorizations += session.headers["authorization"]
        protocols += session.headers["sec-websocket-protocol"]
        refuseWith?.let { status ->
            val known = Response.Status.values().firstOrNull { it.requestStatus == status }
            val code = known ?: object : Response.IStatus {
                override fun getDescription(): String = "$status Refused"

                override fun getRequestStatus(): Int = status
            }
            return newFixedLengthResponse(code, "application/json", """{"error":"refused"}""")
        }
        return super.serve(session).also { answer -> protocolAnswer?.let { answer.addHeader("sec-websocket-protocol", it) } }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = Tablet(handshake).also { connections += it }

    /** Takes the next tablet connection once its handshake is answered, waiting at most [seconds]. */
    fun next(seconds: Long = 10): Tablet {
        val tablet = connections.poll(seconds, TimeUnit.SECONDS) ?: throw AssertionError("No tablet connected")
        if (!tablet.opened.await(seconds, TimeUnit.SECONDS)) throw AssertionError("The handshake was never answered")
        return tablet
    }

    /** One tablet connection as the room sees it. */
    inner class Tablet(handshake: NanoHTTPD.IHTTPSession) : WebSocket(handshake) {
        private val received = mutableListOf<RelayMessage>()
        private val frames = mutableListOf<Frame>()
        private val lock: java.lang.Object = java.lang.Object()
        val closed = CountDownLatch(1)

        /** Counted down once the handshake's answer is out: frames may be sent from then on. */
        val opened = CountDownLatch(1)

        @Volatile
        var closeCode: Int? = null

        override fun onOpen() {
            try {
                for (item in greeting) {
                    when (item) {
                        is RelayMessage -> say(item)
                        is Frame -> say(item)
                    }
                }
            } catch (e: IOException) {
                // The tablet went first.
            }
            opened.countDown()
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            closeCode = code?.value
            closed.countDown()
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            synchronized(lock) {
                when (message.opCode) {
                    NanoWSD.WebSocketFrame.OpCode.Text -> received += RelayProtocol.decode(message.textPayload) ?: UNREADABLE
                    NanoWSD.WebSocketFrame.OpCode.Binary -> frames += Frame.decode(message.binaryPayload) ?: return
                    else -> return
                }
                lock.notifyAll()
            }
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame) = Unit

        override fun onException(exception: IOException) = Unit

        fun say(message: RelayMessage) = send(message.encode())

        fun say(frame: Frame) = send(frame.encode())

        /** A close frame with any code, the relay's own 44xx among them (NanoWSD's enum knows only the standard ones). */
        fun closeWith(code: Int, reason: String) {
            val payload = byteArrayOf((code shr 8).toByte(), code.toByte()) + reason.toByteArray(Charsets.UTF_8)
            sendFrame(NanoWSD.WebSocketFrame(NanoWSD.WebSocketFrame.OpCode.Close, true, payload))
        }

        /** Takes the first message the tablet sent that is a [T] and passes [filter], waiting at most [ms]; the others stay. */
        inline fun <reified T : RelayMessage> await(ms: Long = 5_000, noinline filter: (T) -> Boolean = { true }): T =
            take(ms) { it is T && filter(it) } as T

        fun take(ms: Long, match: (RelayMessage) -> Boolean): RelayMessage {
            val end = System.currentTimeMillis() + ms
            synchronized(lock) {
                while (true) {
                    val at = received.indexOfFirst(match)
                    if (at >= 0) return received.removeAt(at)
                    val left = end - System.currentTimeMillis()
                    if (left <= 0) throw AssertionError("Not sent by the tablet in $ms ms; it sent $received")
                    lock.wait(left)
                }
            }
        }

        /** Whether the tablet has sent (and nobody took) a message that passes [match]. */
        fun sent(match: (RelayMessage) -> Boolean): Boolean = synchronized(lock) { received.any(match) }

        /** Request [id]'s answer body: its chunks up to its end, waiting at most [ms]. */
        fun body(id: Long, ms: Long = 5_000): ByteArray {
            val end = System.currentTimeMillis() + ms
            val out = ByteArrayOutputStream()
            synchronized(lock) {
                while (true) {
                    val at = frames.indexOfFirst { it.id == id }
                    if (at >= 0) {
                        val frame = frames.removeAt(at)
                        when (frame.kind) {
                            Frame.RES_CHUNK -> out.write(frame.payload)
                            Frame.RES_END -> return out.toByteArray()
                        }
                        continue
                    }
                    val left = end - System.currentTimeMillis()
                    if (left <= 0) throw AssertionError("Answer $id never ended")
                    lock.wait(left)
                }
            }
        }
    }

    companion object {
        /** What a text the tablet sent that isn't a message is kept as. */
        val UNREADABLE: RelayMessage = RelayMessage.WsText(0xFFFF_FFFFL, "unreadable")
    }
}
