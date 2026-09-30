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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The panel's live updates over `/ws` (BUILD_SPEC.md › v1.5.1 — M18 › The socket). The server
 * has already checked the session, the origin and the listener at the handshake; this hub then
 * sends each open socket the whole state ([stateMessage], `{"type":"state",…}`) when it opens and
 * whenever [changes] says something changed, coalesced to at most one every [minGapMs] (ten a
 * second), and while a piece plays [progressMessage] once a second (`{"type":"progress",
 * positionMs, at}`: the page runs its clock on from there at the tempo). The page sends nothing
 * that is read; its frames are bounded ([FrameGuard]) and dropped. Every [pingMs] each socket is
 * pinged (its pong keeps the socket's read alive under the server's read timeout) and closed if its
 * session has ended ([sessionValid]: logged out, the PIN changed, the panel turned off). At most
 * [maxSockets] are open at once: each holds one of the server's four threads while it is open.
 *
 * Browsers that reach the panel through the relay (v1.10 — M26) are [Member]s ([attach]): their
 * sockets end at the relay, which bridges them over the tablet's one connection, so the hub hands
 * each message to the relay ([Member]'s `send`) rather than to a socket of its own. They hear what
 * the listeners' sockets hear, the state as they attach included; at most [maxMembers] of them,
 * apart from the listeners' [maxSockets]; they are not pinged (the relay keeps the browsers awake
 * and the tablet's connection has its own pings), but checked at every ping all the same and
 * closed once their session has ended; the relay's side ends one with [Member.detach].
 */
class WebSocketHub(
    private val stateMessage: suspend () -> String,
    private val progressMessage: () -> String?,
    private val sessionValid: (String?) -> Boolean,
    private val maxSockets: Int = MAX_SOCKETS,
    private val minGapMs: Long = MIN_GAP_MS,
    private val pingMs: Long = PING_MS,
    private val progressMs: Long = PROGRESS_MS,
    private val maxMembers: Int = MAX_MEMBERS,
) : WebSockets {
    private val open = CopyOnWriteArrayList<HubSocket>()
    private val members = CopyOnWriteArrayList<Member>()
    private var jobs: List<Job> = emptyList()

    @Volatile
    private var scope: CoroutineScope? = null

    /** Sockets open now. */
    val count: Int get() = open.size

    /** Relayed browsers attached now. */
    val memberCount: Int get() = members.size

    override fun hasRoom(): Boolean = open.size < maxSockets

    /** Whether another relayed browser may attach. */
    fun hasMemberRoom(): Boolean = members.size < maxMembers

    override fun open(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket {
        val token = WebCookies.parse(handshake.headers["cookie"])[WebCookies.SESSION]
        return HubSocket(GuardedHandshake(handshake), token)
    }

    /**
     * A browser's socket bridged by the relay, whose session ([token]) and origin were checked
     * ([WebServer.admitSocket]): the hub's messages go to [send], and [close] ends it (its code and
     * reason, for the relay to pass on). Null when [maxMembers] are attached already. The whole state
     * follows at once, as a listener's socket gets it on opening.
     */
    fun attach(token: String?, send: (String) -> Unit, close: (Int, String) -> Unit): Member? {
        val member = synchronized(members) {
            if (members.size >= maxMembers) return null
            Member(token, send, close).also { members += it }
        }
        scope?.launch {
            val first = try {
                stateMessage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (first != null && member in members) member.sendQuietly(first)
        }
        return member
    }

    /** Starts the hub's three loops in [scope]: the coalesced state messages, the progress, the pings. */
    fun start(scope: CoroutineScope, changes: Flow<Unit>) {
        stop()
        this.scope = scope
        jobs = listOf(
            scope.launch {
                changes.conflate().collect {
                    if (open.isNotEmpty() || members.isNotEmpty()) broadcast(stateMessage())
                    delay(minGapMs)   // what changes meanwhile is folded into the next message
                }
            },
            scope.launch {
                while (isActive) {
                    delay(progressMs)
                    if (open.isEmpty() && members.isEmpty()) continue
                    progressMessage()?.let { broadcast(it) }
                }
            },
            scope.launch {
                while (isActive) {
                    delay(pingMs)
                    for (socket in open) socket.keepAlive()
                    for (member in members) if (!sessionValid(member.token)) member.end(CLOSE_SESSION_ENDED, "The session has ended.")
                }
            },
        )
    }

    /** Stops the loops and closes every socket and member (the server is stopping, or the panel turned off). */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        scope = null
        for (socket in open) socket.closeQuietly(NanoWSD.WebSocketFrame.CloseCode.GoingAway, "The panel is off.")
        open.clear()
        for (member in members) member.end(NanoWSD.WebSocketFrame.CloseCode.GoingAway.value, "The panel is off.")
        members.clear()
    }

    private fun broadcast(text: String) {
        for (socket in open) socket.sendQuietly(text)
        for (member in members) member.sendQuietly(text)
    }

    /**
     * A browser attached through the relay ([attach]). [detach] when the browser's side has gone (the
     * relay said so, or the tablet's connection to it dropped): nothing more is sent, and there is
     * nothing to close.
     */
    inner class Member internal constructor(internal val token: String?, private val send: (String) -> Unit, private val close: (Int, String) -> Unit) {
        fun detach() {
            members -= this
        }

        internal fun sendQuietly(text: String) {
            try {
                send(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                detach()
            }
        }

        /** Ended from this side: the relay is told why. */
        internal fun end(code: Int, reason: String) {
            if (!members.remove(this)) return
            try {
                close(code, reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The relay's connection is gone already.
            }
        }
    }

    private inner class HubSocket(handshake: NanoHTTPD.IHTTPSession, private val token: String?) : NanoWSD.WebSocket(handshake) {
        override fun onOpen() {
            open += this
            val first = try {
                runBlocking { stateMessage() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            first?.let(::sendQuietly)
        }

        override fun onClose(code: NanoWSD.WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            open -= this
        }

        /** The page sends nothing the app reads (it may say "ping" to keep its side awake): every message is dropped. */
        override fun onMessage(message: NanoWSD.WebSocketFrame) = Unit

        override fun onPong(pong: NanoWSD.WebSocketFrame) = Unit

        override fun onException(exception: IOException) = Unit

        fun sendQuietly(text: String) {
            if (!isOpen) return
            try {
                send(text)
            } catch (e: IOException) {
                closeQuietly(NanoWSD.WebSocketFrame.CloseCode.AbnormalClosure, "Send failed")
            }
        }

        /** A ping, or the end when its session is over. */
        fun keepAlive() {
            if (!sessionValid(token)) {
                closeQuietly(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "The session has ended.")
                return
            }
            try {
                ping(PING_PAYLOAD)
            } catch (e: IOException) {
                closeQuietly(NanoWSD.WebSocketFrame.CloseCode.AbnormalClosure, "Ping failed")
            }
        }

        fun closeQuietly(code: NanoWSD.WebSocketFrame.CloseCode, reason: String) {
            open -= this
            try {
                close(code, reason, false)
            } catch (e: IOException) {
                // Gone already.
            }
        }
    }

    /** The handshake as NanoWSD reads it, but for its input: every frame the page sends passes [FrameGuard] first. */
    private class GuardedHandshake(private val handshake: NanoHTTPD.IHTTPSession) : NanoHTTPD.IHTTPSession by handshake {
        private val guarded = FrameGuard(handshake.inputStream)

        override fun getInputStream(): InputStream = guarded
    }

    companion object {
        const val MAX_SOCKETS = 2

        /** Relayed browsers at once (the relay's own cap is the same). */
        const val MAX_MEMBERS = 4

        /** How a relayed browser's socket ends when its session has (a private code: the page just opens the gate). */
        const val CLOSE_SESSION_ENDED = 4000
        const val MIN_GAP_MS = 100L
        const val PING_MS = 4_000L
        const val PROGRESS_MS = 1_000L
        private val PING_PAYLOAD = "sp".toByteArray()
    }
}

/**
 * The frames a page sends, watched as NanoWSD reads them: NanoWSD would allocate whatever length a
 * frame announces (up to 2 GB) and join fragments without end. Here a frame whose payload is over
 * [maxPayload] bytes, a run of more than [maxFragments] fragments, or an unmasked frame (browsers
 * always mask; RFC 6455 § 5.1) ends the socket before its payload is read. The state follows
 * NanoWSD's own reads byte for byte: the header a byte at a time, then exactly the payload.
 */
class FrameGuard(input: InputStream, private val maxPayload: Int = MAX_PAYLOAD, private val maxFragments: Int = MAX_FRAGMENTS) : FilterInputStream(input) {
    private enum class Part { HEAD, LENGTH, EXTENDED, MASK, PAYLOAD }

    private var part = Part.HEAD
    private var fin = false
    private var control = false
    private var extendedLeft = 0
    private var length = 0L
    private var maskLeft = 0
    private var payloadLeft = 0L
    private var fragments = 0

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) see(b)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        for (i in off until off + maxOf(n, 0)) see(b[i].toInt() and 0xFF)
        return n
    }

    override fun skip(n: Long): Long = throw IOException("A socket's frames are read, never skipped")

    private fun see(b: Int) {
        when (part) {
            Part.HEAD -> {
                fin = b and 0x80 != 0
                control = b and 0x08 != 0
                if (!control) fragments = if (fin) 0 else fragments + 1
                if (fragments > maxFragments) throw tooBig("Too many fragments")
                part = Part.LENGTH
            }
            Part.LENGTH -> {
                if (b and 0x80 == 0) throw NanoWSD.WebSocketException(NanoWSD.WebSocketFrame.CloseCode.ProtocolError, "Unmasked frame")
                when (val short = b and 0x7F) {
                    127 -> throw tooBig("Frame too large")   // a 64-bit length: 64 KB or more
                    126 -> {
                        length = 0
                        extendedLeft = 2
                        part = Part.EXTENDED
                    }
                    else -> {
                        length = short.toLong()
                        lengthKnown()
                    }
                }
            }
            Part.EXTENDED -> {
                length = (length shl 8) or b.toLong()
                if (--extendedLeft == 0) lengthKnown()
            }
            Part.MASK -> if (--maskLeft == 0) payload()
            Part.PAYLOAD -> if (--payloadLeft == 0L) part = Part.HEAD
        }
    }

    private fun lengthKnown() {
        if (length > maxPayload) throw tooBig("Frame too large")
        maskLeft = MASK_BYTES
        part = Part.MASK
    }

    private fun payload() {
        payloadLeft = length
        part = if (length == 0L) Part.HEAD else Part.PAYLOAD
    }

    private fun tooBig(reason: String) = NanoWSD.WebSocketException(NanoWSD.WebSocketFrame.CloseCode.MessageTooBig, reason)

    companion object {
        const val MAX_PAYLOAD = 4 * 1024
        const val MAX_FRAGMENTS = 16
        private const val MASK_BYTES = 4
    }
}
