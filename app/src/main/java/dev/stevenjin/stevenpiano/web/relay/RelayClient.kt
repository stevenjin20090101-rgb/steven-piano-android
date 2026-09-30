// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import dev.stevenjin.stevenpiano.web.WebCookies
import dev.stevenjin.stevenpiano.web.WebServer
import dev.stevenjin.stevenpiano.web.WebSocketHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Where the tablet's connection to Steven Piano Cloud stands (the Remote page's CLOUD section, the
 * hub's row, the notification): [Off] (remote access over the internet is off), [NotEnrolled] (no
 * enrolment, or its key is gone), [Connecting], [Connected] to [Connected.host] since
 * [Connected.since], [Waiting] [Waiting.retryInMs] from [Waiting.since] before the next try and why,
 * [Revoked] in the console (4401, or the relay refusing the secret) or [Disabled] there (4403): the
 * last two stop trying until the tablet is enrolled again.
 */
sealed interface CloudStatus {
    data object Off : CloudStatus

    data object NotEnrolled : CloudStatus

    data object Connecting : CloudStatus

    data class Connected(val host: String, val pianoId: String, val since: Long) : CloudStatus

    data class Waiting(val reason: String, val retryInMs: Long, val since: Long) : CloudStatus

    data object Revoked : CloudStatus

    data object Disabled : CloudStatus
}

/**
 * How to reach the relay: its [url] (`wss://<host>/tablet`; `ws://10.0.2.2:8787/tablet` only in the
 * debug build's local test, `CloudOverride`), this piano's [pianoId] and bearer [secret], and the
 * [scheme] of the panel's pages there (`https`, or `http` for that local test). [toString] never
 * prints the secret.
 */
class RelayConfig(val url: String, val pianoId: String, val secret: String, val scheme: String = WebServer.HTTPS) {
    override fun toString(): String = "RelayConfig($url, $pianoId, secret kept)"
}

/** What the tablet reports in its `status` ([RelayStatus] builds it), and when something in it [changes]. */
interface StatusSource {
    suspend fun report(): JSONObject

    val changes: Flow<Unit>
}

/**
 * The tablet's one outbound connection to Steven Piano Cloud (BUILD_SPEC.md › v1.10 — M26): an
 * OkHttp WebSocket to the relay ([RelayConfig.url], `Authorization: Bearer <pianoId>.<secret>`,
 * subprotocol [RelayProtocol.SUBPROTOCOL]; hostname verification as OkHttp does by default, a ping
 * every 30 s), speaking [RelayProtocol]:
 *
 * - **Requests** the relay carries from browsers go to the web panel's own [server] through
 *   [WebServer.serveRelayed] on a pool of [THREADS] threads, their bodies through a [BodyPipe] under
 *   the hello's credit window; the answer goes back as `res`, its chunks and its end. At most
 *   [MAX_IN_FLIGHT] at once (the relay's own cap): past it, 503 busy at once.
 * - **Browser sockets** are admitted as the listeners' are ([WebServer.admitSocket]: a session, the
 *   panel's own origin, the relay's host) and attached to the [hub] (at most four); what the hub
 *   sends goes out as `ws.text`.
 * - **Console commands** go to [commands]; **a new secret** to [secrets], acknowledged once kept.
 * - **Status**: [status]'s report on hello, every [STATUS_EVERY_MS] and at most [statusSettleMs]
 *   after a change.
 *
 * It connects while started ([start]); a dropped connection is tried again after 1 s × 2ⁿ (at most
 * 300 s, ±20 %), the count starting again at each hello; [nudge] (a network came back) tries at once;
 * 4401 and 4403 (or the relay refusing the secret) stop it, 4409 (another tablet took over) waits a
 * minute. [config] is read before every try (a rotated secret takes effect at the next one); null:
 * not enrolled. Nothing here logs a secret, a cookie or a request's content.
 */
class RelayClient(
    private val config: suspend () -> RelayConfig?,
    private val server: WebServer,
    private val hub: WebSocketHub,
    private val commands: CommandHandler,
    private val status: StatusSource,
    private val secrets: suspend (String) -> Boolean,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = defaultClient(),
    private val executor: ExecutorService = defaultExecutor(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val online: () -> Boolean = { true },
    private val random: () -> Double = Math::random,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val statusSettleMs: Long = STATUS_SETTLE_MS,
    private val userAgent: String = "StevenPiano",
) {
    private val _state = MutableStateFlow<CloudStatus>(CloudStatus.Off)

    /** Where the connection stands. */
    val state: StateFlow<CloudStatus> = _state.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    @Volatile
    private var current: Connection? = null

    /** Connects, and keeps connecting, until [stop]; nothing when it runs already. */
    @Synchronized
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { run() }
    }

    /** Closes the connection and stops trying: [CloudStatus.Off]. */
    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        current?.let {
            current = null
            it.close(NORMAL_CLOSURE, "The tablet turned remote access off.")
        }
        _state.value = CloudStatus.Off
    }

    /** A network came back (or the web service looked again): a wait for the next try ends now. */
    fun nudge() {
        if (_state.value is CloudStatus.Waiting) wake.trySend(Unit)
    }

    /** The wait before try [attempt] (0 first): 1 s × 2ⁿ, at most [MAX_WAIT_MS], ±20 %. */
    fun backoff(attempt: Int): Long {
        val base = min(FIRST_WAIT_MS.toDouble() * (1L shl min(attempt, 20)), MAX_WAIT_MS.toDouble())
        return (base * (1 - JITTER + 2 * JITTER * random().coerceIn(0.0, 1.0))).roundToLong()
    }

    private suspend fun run() {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            val cfg = config()
            if (cfg == null) {
                _state.value = CloudStatus.NotEnrolled
                return
            }
            _state.value = CloudStatus.Connecting
            val end = connect(cfg)
            when {
                end.code == RelayProtocol.CLOSE_REVOKED || end.httpStatus == UNAUTHORIZED -> {
                    log("Cloud: the relay refused this tablet (${end.code ?: end.httpStatus}): revoked")
                    _state.value = CloudStatus.Revoked
                    return
                }
                end.code == RelayProtocol.CLOSE_DISABLED -> {
                    log("Cloud: turned off in the console (4403)")
                    _state.value = CloudStatus.Disabled
                    return
                }
            }
            if (end.greeted) attempt = 0
            val replaced = end.code == RelayProtocol.CLOSE_REPLACED
            val wait = if (replaced) REPLACED_WAIT_MS else backoff(attempt++)
            val reason = when {
                replaced -> "Another tablet connected with this enrolment"
                !online() -> "Waiting for a network"
                else -> "The relay can't be reached"
            }
            log("Cloud: connection ended (${end.code ?: end.httpStatus ?: "failed"}); next try in ${wait / 1000} s")
            _state.value = CloudStatus.Waiting(reason, wait, clock())
            waitOrNudge(wait)
        }
    }

    /** Waits [ms], or less when [nudge]d. */
    private suspend fun waitOrNudge(ms: Long) {
        while (wake.tryReceive().isSuccess) Unit   // a nudge from before the wait began is not this wait's
        coroutineScope {
            val sleeper = async { sleep(ms) }
            select<Unit> {
                sleeper.onAwait { }
                wake.onReceive { }
            }
            sleeper.cancel()
        }
    }

    /** One connection, from the handshake to its end. */
    private suspend fun connect(cfg: RelayConfig): End = suspendCancellableCoroutine { cont ->
        val connection = Connection(cfg) { end -> if (cont.isActive) cont.resume(end) }
        val request = try {
            Request.Builder()
                .url(cfg.url)
                .header("Authorization", "Bearer ${cfg.pianoId}.${cfg.secret}")
                .header("Sec-WebSocket-Protocol", RelayProtocol.SUBPROTOCOL)
                .header("User-Agent", userAgent)
                .build()
        } catch (e: IllegalArgumentException) {
            cont.resume(End(code = null, httpStatus = null, greeted = false))
            return@suspendCancellableCoroutine
        }
        current = connection
        connection.socket = client.newWebSocket(request, connection)
        cont.invokeOnCancellation {
            connection.close(NORMAL_CLOSURE, "The tablet turned remote access off.")
        }
    }

    /** How a connection ended: the relay's close [code], or the handshake's [httpStatus]; [greeted] when a hello had come. */
    private data class End(val code: Int?, val httpStatus: Int?, val greeted: Boolean)

    /** A request being answered: its body's pipe (null without one); [aborted] once the relay gave up on it. */
    private class Pending(val pipe: BodyPipe?) {
        @Volatile
        var aborted = false

        fun abort() {
            aborted = true
            pipe?.abort()
        }
    }

    /** One connection's life: its hello, its requests, its bridged sockets, its status reports. */
    private inner class Connection(private val cfg: RelayConfig, private val onEnd: (End) -> Unit) : WebSocketListener() {
        @Volatile
        lateinit var socket: WebSocket

        @Volatile
        private var hello: RelayMessage.Hello? = null
        private val requests = ConcurrentHashMap<Long, Pending>()
        private val members = ConcurrentHashMap<Long, WebSocketHub.Member>()
        private val jobs = mutableListOf<Job>()
        private val ended = AtomicBoolean(false)

        fun close(code: Int, reason: String) {
            if (::socket.isInitialized) socket.close(code, reason)
            finish(End(code, null, hello != null))
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!::socket.isInitialized) socket = webSocket
            if (response.header("Sec-WebSocket-Protocol") != RelayProtocol.SUBPROTOCOL) {
                log("Cloud: the relay didn't answer in ${RelayProtocol.SUBPROTOCOL}")
                webSocket.close(PROTOCOL_ERROR, "Speak ${RelayProtocol.SUBPROTOCOL}.")
                return
            }
            launch {
                delay(HELLO_WAIT_MS)
                if (hello == null) webSocket.close(PROTOCOL_ERROR, "No hello.")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val message = RelayProtocol.decode(text)) {
                is RelayMessage.Hello -> onHello(webSocket, message)
                is RelayMessage.Req -> onReq(message)
                is RelayMessage.ReqAbort -> requests.remove(message.id)?.abort()
                is RelayMessage.WsOpen -> onWsOpen(message)
                is RelayMessage.WsClose -> members.remove(message.id)?.detach()
                is RelayMessage.Cmd -> onCmd(message)
                is RelayMessage.Secret -> onSecret(message)
                null -> log("Cloud: a message from the relay that isn't one was dropped")
                else -> Unit   // the tablet's own kinds: never sent to it
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val frame = Frame.decode(bytes.toByteArray()) ?: return
            val pending = requests[frame.id] ?: return   // over already, or never begun: the relay learns from our answer
            when (frame.kind) {
                Frame.REQ_CHUNK -> if (pending.pipe?.offer(frame.payload) != true) {
                    log("Cloud: a request's body went past its window; the request ends")
                    pending.pipe?.abort()
                }
                Frame.REQ_END -> pending.pipe?.end()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            finish(End(code, null, hello != null))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(End(code, null, hello != null))

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val status = response?.code
            response?.close()
            finish(End(null, status, hello != null))
        }

        private fun finish(end: End) {
            if (!ended.compareAndSet(false, true)) return
            synchronized(jobs) { jobs.forEach { it.cancel() } }
            requests.values.forEach { it.abort() }
            requests.clear()
            members.values.forEach { it.detach() }
            members.clear()
            if (current === this) current = null
            onEnd(end)
        }

        private fun launch(block: suspend CoroutineScope.() -> Unit) {
            val job = scope.launch(block = block)
            synchronized(jobs) {
                if (ended.get()) job.cancel() else jobs += job
            }
        }

        // ---- The room's messages ----------------------------------------------------------------

        private fun onHello(webSocket: WebSocket, message: RelayMessage.Hello) {
            if (message.pianoId != cfg.pianoId) {
                log("Cloud: the relay greeted another piano; closing")
                webSocket.close(POLICY_VIOLATION, "Not this piano.")
                return
            }
            val first = hello == null
            hello = message
            _state.value = CloudStatus.Connected(message.host, message.pianoId, clock())
            if (!first) return
            log("Cloud: connected to ${message.host}")
            launch {
                while (isActive) {
                    report()
                    delay(STATUS_EVERY_MS)
                }
            }
            launch {
                status.changes.conflate().collect {
                    delay(statusSettleMs)   // what changes meanwhile goes in the same report
                    report()
                }
            }
        }

        private suspend fun report() {
            val body = try {
                status.report()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return
            }
            send(RelayMessage.Status(body))
        }

        private fun onReq(req: RelayMessage.Req) {
            val hello = hello ?: return
            if (requests.size >= MAX_IN_FLIGHT) {
                answer(req.id, RelayedResponse.refusal(503, "busy", "The piano is busy. Try again.", hello.host, cfg.scheme), Pending(null), hello)
                return
            }
            val pending = Pending(if (req.body) BodyPipe(hello.caps.window, onCredit = { n -> send(RelayMessage.ReqCredit(req.id, n.toLong())) }) else null)
            if (requests.putIfAbsent(req.id, pending) != null) return   // an id already in flight: the room's mistake, ignored
            try {
                executor.execute { serve(req, pending, hello) }
            } catch (e: RejectedExecutionException) {
                requests.remove(req.id, pending)
                pending.abort()
                answer(req.id, RelayedResponse.refusal(503, "busy", "The piano is busy. Try again.", hello.host, cfg.scheme), Pending(null), hello)
            }
        }

        /** On a pool thread: the web panel's answer, then back to the relay. */
        private fun serve(req: RelayMessage.Req, pending: Pending, hello: RelayMessage.Hello) {
            try {
                val body = pending.pipe ?: ByteArrayInputStream(ByteArray(0))
                val session = RelayedSession(req.method, req.path, req.query, req.headers, req.address, body)
                val answer = try {
                    RelayedResponse.write(server.serveRelayed(session, hello.host, hello.prefix, cfg.scheme))
                } catch (e: Exception) {
                    RelayedResponse.refusal(500, "server", "Something went wrong.", hello.host, cfg.scheme)
                }
                if (!pending.aborted) answer(req.id, answer, pending, hello)
            } finally {
                requests.remove(req.id, pending)
                pending.pipe?.close()
            }
        }

        /** `res`, then the body in chunks (waiting while the socket's queue is long), then the end. */
        private fun answer(id: Long, answer: RelayedResponse, pending: Pending, hello: RelayMessage.Hello) {
            if (!send(answer.res(id))) return
            val body = answer.body
            var at = 0
            while (at < body.size) {
                if (pending.aborted || !roomToSend()) return
                val n = min(hello.caps.chunk, body.size - at)
                if (!sendFrame(Frame(id, Frame.RES_CHUNK, body.copyOfRange(at, at + n)))) return
                at += n
            }
            sendFrame(Frame(id, Frame.RES_END))
        }

        private fun onWsOpen(open: RelayMessage.WsOpen) {
            val hello = hello ?: return
            val cookies = WebCookies.parse(open.headers["cookie"])
            val verdict = if (open.headers["host"]?.lowercase() != hello.host) {
                403
            } else {
                server.admitSocket(cookies, open.headers["origin"], "${cfg.scheme}://${hello.host}")
            }
            if (verdict != WebServer.SOCKET_ADMITTED) {
                send(RelayMessage.WsRefuse(open.id, verdict))
                return
            }
            if (!hub.hasMemberRoom() || members.containsKey(open.id)) {
                send(RelayMessage.WsRefuse(open.id, SERVICE_UNAVAILABLE))
                return
            }
            send(RelayMessage.WsAccept(open.id))
            val member = hub.attach(
                cookies[WebCookies.SESSION],
                send = { text -> sendText(open.id, text) },
                close = { code, reason ->
                    members.remove(open.id)
                    send(RelayMessage.WsClose(open.id, code, reason))
                },
            )
            if (member == null) send(RelayMessage.WsClose(open.id, TRY_AGAIN_LATER, "Too many panels are open.")) else members[open.id] = member
        }

        /** A hub message for a bridged browser; one too large for the protocol's frame is dropped (the next state follows). */
        private fun sendText(id: Long, text: String) {
            val message = RelayMessage.WsText(id, text).encode()
            if (RelayProtocol.utf8Length(message) > RelayProtocol.MAX_TEXT) {
                log("Cloud: a panel message too large for the relay was dropped")
                return
            }
            socket.send(message)
        }

        private fun onCmd(cmd: RelayMessage.Cmd) {
            launch {
                val result = try {
                    commands.run(cmd.name, cmd.args)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    CommandResult(false, "The tablet couldn't do that.")
                }
                send(RelayMessage.CmdResult(cmd.id, result.ok, result.message))
                if (cmd.name == RelayCommands.STATUS) report()
            }
        }

        private fun onSecret(message: RelayMessage.Secret) {
            launch {
                val kept = try {
                    secrets(message.secret)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                if (kept) {
                    log("Cloud: a new secret is kept")
                    send(RelayMessage.SecretAck)
                } else {
                    log("Cloud: a new secret couldn't be kept; the old one stays")
                }
            }
        }

        // ---- Sending ----------------------------------------------------------------------------

        private fun send(message: RelayMessage): Boolean {
            if (!::socket.isInitialized) return false
            val text = message.encode()
            if (RelayProtocol.utf8Length(text) > RelayProtocol.MAX_TEXT) return false
            return socket.send(text)
        }

        private fun sendFrame(frame: Frame): Boolean = ::socket.isInitialized && socket.send(frame.encode().toByteString())

        /** Waits while the socket's outgoing queue is long (OkHttp closes a socket whose queue passes 16 MB); false if it never shortens. */
        private fun roomToSend(): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SEND_WAIT_MS)
            while (socket.queueSize() > MAX_QUEUED_BYTES) {
                if (ended.get() || System.nanoTime() > deadline) return false
                Thread.sleep(QUEUE_POLL_MS)
            }
            return true
        }
    }

    companion object {
        const val FIRST_WAIT_MS = 1_000L
        const val MAX_WAIT_MS = 300_000L
        const val JITTER = 0.2

        /** After 4409 (another tablet took over), a minute before trying again. */
        const val REPLACED_WAIT_MS = 60_000L

        /** A status report at least this often. */
        const val STATUS_EVERY_MS = 30_000L

        /** A change is reported at most this long after it. */
        const val STATUS_SETTLE_MS = 2_000L

        /** How long a connection may stay silent after its handshake before its hello. */
        const val HELLO_WAIT_MS = 15_000L

        /** Requests answered at once (the relay's own cap). */
        const val MAX_IN_FLIGHT = 8

        /** Threads answering them. */
        const val THREADS = 4

        /** OkHttp's pings on the connection. */
        const val PING_SECONDS = 30L

        private const val NORMAL_CLOSURE = 1000
        private const val PROTOCOL_ERROR = 1002
        private const val POLICY_VIOLATION = 1008
        private const val TRY_AGAIN_LATER = 4503
        private const val UNAUTHORIZED = 401
        private const val SERVICE_UNAVAILABLE = 503
        private const val MAX_QUEUED_BYTES = 1L * 1024 * 1024
        private const val SEND_WAIT_MS = 30_000L
        private const val QUEUE_POLL_MS = 5L

        /** OkHttp as the relay needs it: pings, no redirects followed, the platform's TLS and OkHttp's hostname check. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        /** [THREADS] threads for the relayed requests, [MAX_IN_FLIGHT] − [THREADS] waiting; idle threads end. */
        fun defaultExecutor(): ExecutorService {
            val count = AtomicInteger()
            return ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_IN_FLIGHT)) { task ->
                Thread(task, "steven-piano-relay-${count.incrementAndGet()}")
            }.apply { allowCoreThreadTimeOut(true) }
        }
    }
}
