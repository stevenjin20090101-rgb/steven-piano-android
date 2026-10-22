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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.SecureRandom
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/** The WebSocket side of the server ([WebSocketHub]): whether another socket may open, and opening one for a handshake already checked. */
interface WebSockets {
    fun hasRoom(): Boolean

    fun open(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket
}

/**
 * The web panel's HTTP server (BUILD_SPEC.md › v1.5.1 — M18): NanoHTTPD 2.3.1 with its WebSocket
 * half, one per address the web service listens on ([Config.host]; never the any-address).
 *
 * - **Routes** are exactly [routes]: every other path is a static file from [WebAssets]' allow-list
 *   or a 404, and a listener that is [Config.guestOnly] (the Wi-Fi one, always since 1.24) knows only
 *   the public ones, answering 404 to the rest.
 * - **Every request** must name this listener in its `Host` ([allowedHosts]: its address and port, or
 *   a name the person gave, `webHostName`), or it is refused 403: a page that rebinds its own name to
 *   the tablet's address never gets an answer.
 * - **The panel's routes** need the `sp_session` cookie of a session opened on this way in ([Sessions],
 *   bound to its [Sessions.Transport] since 1.24: a listener's or the relay's; 401 without); those that
 *   change anything also need `X-Steven-Piano: 1` (403 without), which no other site's page can send:
 *   the server never answers with CORS headers, so a browser refuses to send it cross-site. A request
 *   that says where it came from (`Origin`) must come from the panel itself. Login needs the header
 *   and no session; the public routes (the request page and its API, the poster) need neither. The
 *   microphone's two starts and Install the update also need the session to have confirmed it's you
 *   in the last five minutes (v1.24 — M56: [needsConfirm], `POST /api/confirm`).
 * - **Every response** carries [securityHeaders] and closes its connection (NanoHTTPD never skips a
 *   body a handler did not read, so keep-alive could take the rest of a refused upload for the next
 *   request; closing also frees the pool's threads at once). APIs are `Cache-Control: no-store`.
 * - **Bodies**: JSON only, at most 64 KB, strict UTF-8, four levels deep ([WebApi]); uploads are a raw
 *   `PUT /api/upload?name=` body with its `Content-Length`, refused 411 / 413 / 415 before a byte is
 *   read, one at a time; Studio's recordings (v1.7 — M23) are `PUT /api/studio/audio?name=`, the same
 *   way, 200 MB at most.
 * - **Threads**: a pool of [POOL_THREADS] with a short queue ([BoundedRunner]); NanoHTTPD's reverse
 *   lookup of every peer's name is skipped ([createClientHandler]); a socket read waits at most
 *   [SOCKET_READ_TIMEOUT_MS] and a whole request at most [REQUEST_DEADLINE_MS] ([DeadlineInput]),
 *   its head read and checked before NanoHTTPD sees it ([RequestHead]); a handler that needs the
 *   app waits at most [CALL_TIMEOUT_MS] for it; a listening socket that keeps failing closes
 *   itself rather than spin ([SteadyServerSocket]).
 * - **The socket** (`/ws`, [WebSockets]) opens only for a session, from the panel's own origin, on a
 *   listener that serves the panel.
 * - **Relayed requests** (v1.10 — M26, Steven Piano Cloud) come in through [serveRelayed] as
 *   synthetic sessions and meet the same route table and checks ([answer]), with the relay's edge in
 *   place of the listener's: `https`, the relay's host alone, `Secure` cookies under the piano's
 *   prefix, the socket's `wss:`; the guests' pages and API only while Guests can request is on.
 *   Such a server is never started as a listener.
 */
class WebServer(
    private val config: Config,
    internal val backend: WebBackend,
    internal val sessions: Sessions,
    internal val guard: LoginGuard,
    internal val requests: GuestRequests,
    internal val assets: AssetSource,
    private val sockets: WebSockets,
    private val posterPage: suspend (url: String) -> ByteArray? = { null },
    private val random: SecureRandom = SecureRandom(),
) : NanoWSD(config.host, config.port) {

    /**
     * One listener: the address it binds ([host]), its [port] (0 in tests: any free one),
     * [guestOnly] for the Wi-Fi address (guests only, always since 1.24), the other [names] requests may
     * call it by (the person's `webHostName`; "localhost" for the emulator's loopback listener),
     * [tempDir] (`cacheDir/web`) for the server's temporary files and the uploads, and
     * [requestDeadlineMs], how long a whole request (its head and any JSON body) may take to arrive.
     */
    data class Config(
        val host: String,
        val port: Int = WebAddress.PORT,
        val guestOnly: Boolean = false,
        val names: () -> List<String> = { emptyList() },
        val tempDir: File,
        val requestDeadlineMs: Long = REQUEST_DEADLINE_MS,
    )

    private val runner = BoundedRunner(POOL_THREADS, QUEUE_LENGTH)

    /** One upload at a time (the routes' `PUT /api/upload` and `PUT /api/studio/audio`). */
    internal val uploading = ReentrantLock()

    /** The connection a request thread is serving, so a route that may rightly take long (an upload, a socket) can lift its deadline. */
    private val connection = ThreadLocal<DeadlineInput>()

    init {
        setAsyncRunner(runner)
        setTempFileManagerFactory { CacheTempFiles(config.tempDir) }
        setServerSocketFactory { SteadyServerSocket() }
    }

    /** Listens, with every socket read waiting at most [SOCKET_READ_TIMEOUT_MS]; the listener's thread keeps the process up while it runs. */
    fun startListening() = start(SOCKET_READ_TIMEOUT_MS, false)

    override fun stop() {
        super.stop()
        runner.shutdown()
    }

    /** `address:port` and the names given, as a browser writes them in `Host`. */
    fun allowedHosts(): Set<String> {
        val port = listeningPort
        return (listOf(config.host) + config.names()).map { "${it.lowercase()}:$port" }.toSet()
    }

    // ---- Every request -----------------------------------------------------------------------

    override fun serve(session: IHTTPSession): Response = answer(session, HTTP, allowedHosts(), cookiePath = "/")

    /**
     * A request that came through the relay (BUILD_SPEC.md › v1.10 — M26): [session] is a
     * [dev.stevenjin.stevenpiano.web.relay.RelayedSession], [host] the relay's host as browsers name
     * it (`relay.example.workers.dev`, no port added), [prefix] the piano's path there (`/p/<id>`).
     * It meets the same routes and checks as a listener's request ([answer]) on the relay's edge:
     * [scheme] (`https`; `http` only for the debug build's local relay), `Host` exactly [host], an
     * `Origin` of `<scheme>://<host>`, cookies `Secure` under `<prefix>/`, the socket's `wss:` in the
     * policy. What NanoHTTPD would have refused before a route saw it is refused here the same way
     * ([RequestHead]): a method it doesn't know (501), an address that doesn't decode (400).
     */
    fun serveRelayed(session: IHTTPSession, host: String, prefix: String, scheme: String = HTTPS): Response {
        val relayHost = host.lowercase()
        val refused = when {
            session.method == null -> refuse(501, "method", "The panel doesn't answer that kind of request.")
            session.uri == null -> refuse(400, "request", "The address holds a broken escape.")
            else -> return answer(session, scheme, setOf(relayHost), cookiePath = "$prefix/", relayed = true)
        }
        return secure(refused, relayHost, scheme)
    }

    /**
     * Every request, a listener's ([serve]) or the relay's ([serveRelayed]): its `Host` must be one
     * of [allowed] (403 otherwise, before anything else); then its route, with the edge's [scheme]
     * for the `Origin` it must come from and the socket's scheme in the policy, and [cookiePath] for
     * the cookies it sets (`Secure` over HTTPS). [relayed]: the guests' pages and API answer 404
     * while Guests can request is off, and no socket is opened here.
     */
    internal fun answer(session: IHTTPSession, scheme: String, allowed: Set<String>, cookiePath: String, relayed: Boolean = false): Response {
        val edge = Edge(scheme, cookiePath, relayed)
        val host = session.headers[HOST]?.trim()?.lowercase()
        val known = host != null && host in allowed
        val response = try {
            if (!known) refuse(403, "host", "This address isn't the panel's.") else dispatch(session, host, edge)
        } catch (e: ApiError) {
            error(e)
        } catch (e: TimeoutCancellationException) {
            refuse(503, "busy", "The app is busy. Try again.")
        } catch (e: Exception) {
            refuse(500, "server", "Something went wrong.")
        } catch (e: OutOfMemoryError) {
            refuse(503, "memory", "The tablet is short of memory.")
        }
        val shown = when {
            known -> host
            relayed -> allowed.firstOrNull() ?: config.host
            else -> "${config.host}:$listeningPort"
        }
        return secure(response, shown, scheme)
    }

    private fun dispatch(session: IHTTPSession, host: String, edge: Edge): Response {
        if (isWebsocketRequested(session)) return if (edge.relayed) notFound() else socket(session, host)
        val path = session.uri ?: return notFound()
        val method = session.method
        val matching = routes.filter { (!config.guestOnly || it.access == Access.PUBLIC) && it.pattern.matches(path) }
        if (matching.isEmpty()) return asset(session, path, edge)
        if (edge.relayed && matching.any { it.access == Access.PUBLIC } && guestsClosed()) return notFound()
        val route = matching.firstOrNull { it.method == method }
            ?: return refuse(405, "method", "Not allowed here.").also { r -> r.addHeader("Allow", matching.joinToString(", ") { it.method.name }) }
        val cookies = WebCookies.parse(session.headers[COOKIE])
        val token = cookies[WebCookies.SESSION]
        when (route.access) {
            Access.READ -> if (!sessions.isValid(token, edge.transport)) return refuse(401, "session", "Enter the PIN first.")
            Access.WRITE -> {
                if (!sessions.isValid(token, edge.transport)) return refuse(401, "session", "Enter the PIN first.")
                checkHeaderAndOrigin(session, host, edge)?.let { return it }
            }
            Access.LOGIN -> checkHeaderAndOrigin(session, host, edge)?.let { return it }
            Access.PUBLIC -> if (method != Method.GET) checkOrigin(session, host, edge)?.let { return it }
        }
        val call = Call(session, route.pattern.matchEntire(path)!!.groupValues.drop(1), host, token, cookies, connection.get(), edge)
        return runBlocking {
            if (route.timed) withTimeout(CALL_TIMEOUT_MS) { route.handle(call) } else route.handle(call)
        }
    }

    /** The custom header (no other site's page can send it) and, when the request says where it came from, the panel's own origin. */
    private fun checkHeaderAndOrigin(session: IHTTPSession, host: String, edge: Edge): Response? {
        if (session.headers[PANEL_HEADER] != PANEL_HEADER_VALUE) return refuse(403, "header", "This request didn't come from the panel.")
        return checkOrigin(session, host, edge)
    }

    private fun checkOrigin(session: IHTTPSession, host: String, edge: Edge): Response? {
        val origin = session.headers[ORIGIN] ?: return null
        return if (origin == edge.origin(host)) null else refuse(403, "origin", "This request came from another site.")
    }

    /** Over the relay, the guests' pages and API exist only while Guests can request is on (v1.10 — M26). */
    private fun guestsClosed(): Boolean = !runBlocking { withTimeout(CALL_TIMEOUT_MS) { backend.guestSettings().open } }

    // ---- Static files --------------------------------------------------------------------------

    private fun asset(session: IHTTPSession, path: String, edge: Edge): Response {
        if (edge.relayed && path in GUEST_PAGES && guestsClosed()) return notFound()
        if (path == POSTER_PATH) return poster(session)
        val asset = WebAssets.PUBLIC[path] ?: (if (config.guestOnly) null else WebAssets.PANEL[path]) ?: return notFound()
        if (session.method != Method.GET) return refuse(405, "method", "Not allowed here.").also { it.addHeader("Allow", "GET") }
        val bytes = assets.read(asset.name) ?: return notFound()
        val response = bytesResponse(Response.Status.OK, asset.contentType, bytes)
        response.addHeader("Cache-Control", "no-cache")
        if (path == REQUEST_PAGE) {
            guestCookie(WebCookies.parse(session.headers[COOKIE]))?.let { response.addHeader("Set-Cookie", WebCookies.guest(it, edge.cookiePath, edge.secure)) }
        }
        return response
    }

    private fun poster(session: IHTTPSession): Response {
        if (session.method != Method.GET) return refuse(405, "method", "Not allowed here.").also { it.addHeader("Allow", "GET") }
        val url = runBlocking { backend.state().web.guest } ?: return refuse(503, "offline", "The request page has no address yet.")
        val page = runBlocking { withTimeout(CALL_TIMEOUT_MS) { posterPage(url) } } ?: return notFound()
        return bytesResponse(Response.Status.OK, WebAssets.HTML, page).also { it.addHeader("Cache-Control", "no-cache") }
    }

    /** A new guest id when the request carries none that looks like one; null when it has one. */
    internal fun guestCookie(cookies: Map<String, String>): String? {
        if (cookies[WebCookies.GUEST]?.let(WebCookies.GUEST_ID::matches) == true) return null
        return ByteArray(GUEST_ID_BYTES).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }

    // ---- The socket ----------------------------------------------------------------------------

    private fun socket(session: IHTTPSession, host: String): Response {
        if (session.method != Method.GET || session.uri != SOCKET_PATH || config.guestOnly) return notFound()
        // A listener's socket: the tablet's own address, so a session opened there (v1.24 — M56).
        when (admitSocket(WebCookies.parse(session.headers[COOKIE]), session.headers[ORIGIN], "$HTTP://$host", Sessions.Transport.TAILNET)) {
            401 -> return refuse(401, "session", "Enter the PIN first.")
            403 -> return refuse(403, "origin", "This request came from another site.")
        }
        if (!sockets.hasRoom()) return refuse(503, "sockets", "Too many panels are open.")
        connection.get()?.lift()   // a socket lives long by design: its reads keep the per-read timeout, which the pings feed
        return super.serve(session)   // the handshake; its version and key are checked there
    }

    /**
     * Whether a socket may open on [transport] for a page with [cookies] that says it came from [origin]: 401
     * without a session valid on [transport] (v1.24 — M56), 403 unless [origin] is exactly [expectedOrigin] (the
     * panel's own page), else [SOCKET_ADMITTED]. The listeners' `/ws` asks it, and the relay's bridged sockets
     * (v1.10 — M26).
     */
    fun admitSocket(cookies: Map<String, String>, origin: String?, expectedOrigin: String, transport: Sessions.Transport): Int = when {
        !sessions.isValid(cookies[WebCookies.SESSION], transport) -> 401
        origin != expectedOrigin -> 403
        else -> SOCKET_ADMITTED
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = sockets.open(handshake)

    // ---- Routes --------------------------------------------------------------------------------

    /** Who may call a route: anyone; a session reading; a session changing something (and the header); logging in (the header). */
    enum class Access { PUBLIC, READ, WRITE, LOGIN }

    /** One route: its [method], the whole path it matches, who may call it, a [sample] path (the tests call every route), and its handler. */
    inner class Route(
        val method: Method,
        val pattern: Regex,
        val access: Access,
        val sample: String,
        val timed: Boolean = true,
        val handle: suspend (Call) -> Response,
    )

    /**
     * A request that passed its route's checks: [groups] from its path, the [host] it named, its
     * session [token] and cookies, and its [connection] (null only outside a request thread).
     */
    inner class Call internal constructor(
        val session: IHTTPSession,
        val groups: List<String>,
        val host: String,
        val token: String?,
        val cookies: Map<String, String>,
        internal val connection: DeadlineInput? = null,
        internal val edge: Edge = Edge(HTTP, "/", relayed = false),
    ) {
        val address: String get() = session.remoteIpAddress ?: "unknown"

        fun body(maxDepth: Int = WebApi.MAX_DEPTH): JSONObject {
            val headers = session.headers
            return WebApi.readObject(session.inputStream, contentLength(headers), headers[CONTENT_TYPE], headers.containsKey(TRANSFER_ENCODING), maxDepth)
        }

        fun param(name: String): String? = session.parameters[name]?.firstOrNull()
    }

    /** The routes ([routeTable], WebRoutes.kt, v1.27 — M63): the dispatcher tries them in this order. */
    val routes: List<Route> = routeTable()

    // ---- Responses -----------------------------------------------------------------------------

    internal fun json(body: JSONObject, status: Response.Status = Response.Status.OK): Response =
        bytesResponse(status, JSON, body.toString().toByteArray(Charsets.UTF_8)).also { it.addHeader("Cache-Control", "no-store") }

    /** Bytes for the panel's own reader (v1.13 — M32): never sniffed (the security headers' `nosniff`), never stored. */
    internal fun binary(bytes: ByteArray): Response =
        bytesResponse(Response.Status.OK, BINARY, bytes).also { it.addHeader("Cache-Control", "no-store") }

    /** 202 while a layout runs: ask again after [retryAfterMs]. */
    internal fun working(retryAfterMs: Long): Response =
        json(JSONObject().put("status", "working").put("retryAfterMs", retryAfterMs), Response.Status.ACCEPTED)

    internal fun noContent(): Response =
        newFixedLengthResponse(Response.Status.NO_CONTENT, null, ByteArrayInputStream(ByteArray(0)), 0).also { it.addHeader("Cache-Control", "no-store") }

    internal fun notFound(): Response = refuse(404, "not-found", "Not here.")

    /** A picture: asked for at a version ([versioned], v1.17 — M45: its address changes with the art), kept for good; else for an hour. */
    internal fun image(image: WebImage?, versioned: Boolean): Response {
        if (image == null || image.contentType !in IMAGE_TYPES) return notFound()
        val cache = if (versioned) "private, max-age=31536000, immutable" else "private, max-age=3600"
        return bytesResponse(Response.Status.OK, image.contentType, image.bytes).also { it.addHeader("Cache-Control", cache) }
    }

    internal fun refuse(status: Int, code: String, message: String): Response =
        bytesResponse(statusOf(status), JSON, WebApi.error(code, message).toString().toByteArray(Charsets.UTF_8)).also { it.addHeader("Cache-Control", "no-store") }

    private fun error(e: ApiError): Response = refuse(e.status, e.code, e.message)

    /** 429 with how long to wait, in the body and in `Retry-After`. */
    internal fun waitResponse(ms: Long): Response {
        val secs = seconds(ms)
        return json(WebApi.error("wait", "Try again in $secs s.").put("retryAfter", secs), Response.Status.TOO_MANY_REQUESTS)
            .also { it.addHeader("Retry-After", secs.toString()) }
    }

    internal fun bytesResponse(status: Response.IStatus, type: String?, bytes: ByteArray): Response =
        newFixedLengthResponse(status, type, ByteArrayInputStream(bytes), bytes.size.toLong())

    /**
     * The headers on every response: no sniffing, no framing, no referrer, the content security
     * policy (this origin only, the socket at [host] over [scheme]'s socket scheme, no frames, no
     * base, no forms), resources for this origin only; and the connection closes after it, but for a
     * socket's handshake.
     */
    private fun secure(response: Response, host: String, scheme: String = HTTP): Response {
        for ((name, value) in securityHeaders(host, scheme)) response.addHeader(name, value)
        if (response.status.requestStatus != SWITCHING_PROTOCOLS) response.closeConnection(true)
        return response
    }

    /**
     * Where a request came in (v1.10 — M26): a listener (`http`, cookies at `/`) or the relay
     * (`https`, cookies under the piano's prefix, [relayed]).
     */
    internal class Edge(val scheme: String, val cookiePath: String, val relayed: Boolean) {
        /** Cookies are `Secure` wherever the page is HTTPS. */
        val secure: Boolean get() = scheme == HTTPS

        /**
         * The sessions this edge reads (v1.24 — M56): the relay's, or the tablet's own address's (the Tailscale listener, and
         * the debug emulator's loopback; the Wi-Fi listener, guests only, reads none).
         */
        val transport: Sessions.Transport get() = if (relayed) Sessions.Transport.RELAY else Sessions.Transport.TAILNET

        /** The panel's own origin at [host]: what a request from its page says in `Origin`. */
        fun origin(host: String): String = "$scheme://$host"
    }

    companion object {
        /** [admitSocket]'s answer when the socket may open. */
        const val SOCKET_ADMITTED = 101

        const val HTTP = "http"
        const val HTTPS = "https"

        /** The guests' pages: over the relay, only while Guests can request is on. */
        private val GUEST_PAGES = setOf(POSTER_PATH, REQUEST_PAGE, "/request.js")
    }

    // ---- NanoHTTPD's threads and files ---------------------------------------------------------

    /**
     * NanoHTTPD's own handler asks DNS for every peer's name (`InetAddress.getHostName`), a lookup
     * that can take seconds on a school network, for every request. This one hands the session an
     * address named by its own digits, so no lookup ever happens.
     */
    override fun createClientHandler(finalAccept: Socket, inputStream: InputStream): ClientHandler = LiteralClientHandler(inputStream, finalAccept)

    /**
     * One connection, one request (every answer closes it, but a socket's handshake). The request's
     * head is read here first, under a deadline for the whole request ([Config.requestDeadlineMs],
     * not only NanoHTTPD's per-read timeout, which a peer sending a byte every few seconds never
     * trips), and checked ([RequestHead]): whatever NanoHTTPD 2.3.1 would have answered by itself —
     * without the panel's headers, kept alive, echoing the method — is answered here, with them
     * (audit 1.5.1, W3). A head that passes goes to NanoHTTPD with every byte read so far, and the
     * rest of the connection, still under the deadline, follows it.
     */
    private inner class LiteralClientHandler(private val input: InputStream, private val socket: Socket) : ClientHandler(input, socket) {
        override fun run() {
            var output: OutputStream? = null
            try {
                output = socket.getOutputStream()
                val peer = socket.inetAddress
                val named = InetAddress.getByAddress(peer.hostAddress, peer.address)
                val timed = DeadlineInput(socket, input, SOCKET_READ_TIMEOUT_MS, config.requestDeadlineMs)
                when (val head = RequestHead.read(timed)) {
                    RequestHead.Empty -> Unit   // connected and said nothing (a browser's preconnect, a port scan): no answer owed
                    is RequestHead.Refused -> {
                        output.write(refusal(head))
                        output.flush()
                    }
                    is RequestHead.Ok -> {
                        connection.set(timed)
                        try {
                            HTTPSession(tempFileManagerFactory.create(), SequenceInputStream(ByteArrayInputStream(head.bytes), timed), output, named).execute()
                        } finally {
                            connection.remove()
                        }
                    }
                }
            } catch (e: Exception) {
                // A closed or broken connection, a read that timed out, or the answer's own end (NanoHTTPD's "Shutdown").
            } finally {
                linger()
                closeQuietly(output)
                closeQuietly(input)
                closeQuietly(socket)
                runner.closed(this)
            }
        }

        /** The answer to a head refused before NanoHTTPD saw it: the panel's headers, JSON, closed, and nothing of the request in it. */
        private fun refusal(head: RequestHead.Refused): ByteArray {
            val body = WebApi.error(head.code, head.message).toString().toByteArray(Charsets.UTF_8)
            val lines = StringBuilder()
                .append("HTTP/1.1 ").append(head.status).append(' ').append(RequestHead.reason(head.status)).append("\r\n")
                .append("Content-Type: ").append(JSON).append("\r\n")
                .append("Date: ").append(HTTP_DATE.format(ZonedDateTime.now(ZoneOffset.UTC))).append("\r\n")
            for ((name, value) in securityHeaders("${config.host}:$listeningPort")) lines.append(name).append(": ").append(value).append("\r\n")
            lines.append("Cache-Control: no-store\r\n")
                .append("Connection: close\r\n")
                .append("Content-Length: ").append(body.size).append("\r\n\r\n")
            return lines.toString().toByteArray(Charsets.ISO_8859_1) + body
        }

        /**
         * A refusal answered before the body was read (411, 413, 415…) would otherwise close on
         * unread bytes, and the peer's stack may then drop the answer with a reset. So the answer's
         * end is sent first, then up to [LINGER_BYTES] of what is still coming are read and thrown
         * away for at most [LINGER_MS] **in all** (audit W3: it was per read, so a peer sending a
         * byte every few hundred milliseconds kept the thread draining for hours): the browser sees
         * the refusal, never "connection reset", and no peer keeps the thread.
         */
        private fun linger() {
            if (socket.isClosed) return
            try {
                socket.shutdownOutput()
                val end = System.nanoTime() + LINGER_MS * NANOS_PER_MS
                val drain = ByteArray(COPY_BUFFER)
                var left = LINGER_BYTES
                val raw = socket.getInputStream()
                while (left > 0) {
                    val waitMs = (end - System.nanoTime()) / NANOS_PER_MS
                    if (waitMs <= 0) break
                    socket.soTimeout = waitMs.toInt()
                    val n = raw.read(drain, 0, minOf(drain.size, left))
                    if (n < 0) break
                    left -= n
                }
            } catch (e: IOException) {
                // The peer is gone, or kept sending: either way the answer went out first.
            }
        }
    }

    /**
     * At most [threads] connections are served at once and [queued] wait; past that a connection
     * is closed at once. Every response closes its connection, so a thread serves one request and is
     * free again; a socket holds one for as long as it is open ([WebSocketHub] keeps two at most).
     */
    private class BoundedRunner(threads: Int, queued: Int) : AsyncRunner {
        private val running: MutableSet<ClientHandler> = Collections.synchronizedSet(HashSet())
        private val executor = ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, ArrayBlockingQueue(queued), NamedThreads())

        override fun exec(code: ClientHandler) {
            running += code
            try {
                executor.execute(code)
            } catch (e: RejectedExecutionException) {
                running -= code
                code.close()
            }
        }

        override fun closed(clientHandler: ClientHandler) {
            running -= clientHandler
        }

        override fun closeAll() {
            val open = synchronized(running) { running.toList() }
            open.forEach { it.close() }
        }

        fun shutdown() {
            executor.shutdownNow()
        }
    }

    private class NamedThreads : ThreadFactory {
        private val count = AtomicInteger()

        override fun newThread(task: Runnable): Thread = Thread(task, "steven-piano-web-${count.incrementAndGet()}")
    }

    /** NanoHTTPD's temporary files, if it ever makes one (it would for a multipart form, which no route reads), go to `cacheDir/web`. */
    private class CacheTempFiles(private val dir: File) : TempFileManager {
        private val files = mutableListOf<File>()

        override fun clear() {
            files.forEach { it.delete() }
            files.clear()
        }

        override fun createTempFile(filename_hint: String?): TempFile {
            if (!dir.isDirectory) dir.mkdirs()
            val file = File.createTempFile("nano-", ".tmp", dir)
            files += file
            return object : TempFile {
                override fun open(): OutputStream = FileOutputStream(file)

                override fun delete() {
                    file.delete()
                }

                override fun getName(): String = file.absolutePath
            }
        }
    }

}

/** At most [length] bytes of [input]: an upload's body and nothing after it. */
internal class LimitedInputStream(private val input: InputStream, private var left: Long) : InputStream() {
    override fun read(): Int {
        if (left <= 0) return -1
        val b = input.read()
        if (b >= 0) left--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (left <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), left).toInt())
        if (n > 0) left -= n
        return n
    }
}

/**
 * The listening socket, steadied. NanoHTTPD's accept loop runs until its socket is closed and tries
 * again at once after any failure, so a socket Android destroyed under it (netd destroys the
 * sockets of an address that goes away, as the Wi-Fi's does when it drops) would keep a core at
 * 100 % (seen on `steven_piano`: a dummy interface's address removed). Here each failure in a row
 * waits a little longer ([ACCEPT_PAUSE_MS] more each time, at most [MAX_ACCEPT_PAUSE_MS]), a success
 * starts the count again, and after [MAX_ACCEPT_FAILURES] in a row the socket closes, which ends
 * NanoHTTPD's loop: the web service finds the listener gone at its next look and listens again.
 */
internal open class SteadyServerSocket(private val pause: (Long) -> Unit = ::pauseQuietly) : ServerSocket() {
    private var failures = 0

    override fun accept(): Socket {
        try {
            return acceptOnce().also { failures = 0 }
        } catch (e: IOException) {
            if (!isClosed) {
                failures++
                if (failures >= MAX_ACCEPT_FAILURES) close() else pause(minOf(failures * ACCEPT_PAUSE_MS, MAX_ACCEPT_PAUSE_MS))
            }
            throw e
        }
    }

    /** One accept, as `ServerSocket` does it (a test stands in its own). */
    protected open fun acceptOnce(): Socket = super.accept()

    companion object {
        const val MAX_ACCEPT_FAILURES = 20
        const val ACCEPT_PAUSE_MS = 20L
        const val MAX_ACCEPT_PAUSE_MS = 500L
    }
}

private fun pauseQuietly(ms: Long) {
    try {
        Thread.sleep(ms)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

/**
 * A connection's input with a deadline for the whole request on top of the socket's per-read
 * timeout (audit 1.5.1, W3). NanoHTTPD's only guard is that per-read timeout, so a peer sending one
 * byte every few seconds held a request thread for as long as its head took to reach 8 KB — hours —
 * and four such peers held a listener. Here no read starts once [deadlineMs] has passed since the
 * connection's thread took it up, and none waits past it ([readTimeoutMs] at most). [lift] ends the
 * deadline for what may rightly take long once it is checked and signed in (an upload's body, a
 * socket's life): their reads keep the per-read timeout alone, as before.
 */
internal class DeadlineInput(
    private val socket: Socket,
    private val raw: InputStream,
    private val readTimeoutMs: Int,
    deadlineMs: Long,
    private val nanos: () -> Long = System::nanoTime,
) : InputStream() {
    @Volatile
    private var deadline: Long = nanos() + deadlineMs * NANOS_PER_MS

    override fun read(): Int {
        arm()
        return raw.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        arm()
        return raw.read(b, off, len)
    }

    override fun available(): Int = raw.available()

    override fun close() = raw.close()

    /** No deadline from now on, only the per-read timeout. */
    fun lift() {
        deadline = LIFTED
        socket.soTimeout = readTimeoutMs
    }

    /** The next read waits at most what is left before the deadline; past it, none starts. */
    private fun arm() {
        val at = deadline
        if (at == LIFTED) return
        val leftMs = (at - nanos()) / NANOS_PER_MS
        if (leftMs <= 0) throw SocketTimeoutException("The request took too long to arrive")
        socket.soTimeout = minOf(readTimeoutMs.toLong(), leftMs).toInt()
    }

    private companion object {
        const val LIFTED = Long.MAX_VALUE
    }
}

/**
 * A request's head, its request line and headers, as it arrived: read by the server itself before
 * NanoHTTPD 2.3.1 sees it, at most [MAX_BYTES] within the request's deadline, and checked (audit
 * 1.5.1, W3). Everything NanoHTTPD would have answered by itself — without the panel's security
 * headers, keeping the connection alive, and echoing the method into its answer — or mishandled is
 * refused here instead, answered with them: a request line that isn't `METHOD /target HTTP/1.x`
 * (400), a method NanoHTTPD doesn't know (501), another HTTP version (505), a target whose percent
 * escapes don't decode (400; NanoHTTPD dropped the connection unanswered), a head over 8 KB (431;
 * NanoHTTPD cut it short and served what was left), a second `Host` (400, RFC 9112 § 3.2). [Ok]
 * carries every byte read, the head and whatever of the body came with it, for NanoHTTPD to read
 * again from the start. Lines are split as NanoHTTPD's reader splits them (CR, LF or CRLF).
 */
internal sealed interface RequestHead {
    class Ok(val bytes: ByteArray) : RequestHead

    data class Refused(val status: Int, val code: String, val message: String) : RequestHead

    /** The peer connected and closed, or waited out the deadline, without a byte: no answer is owed. */
    data object Empty : RequestHead

    companion object {
        /** NanoHTTPD's own head buffer: a longer head it would cut short without a word. */
        const val MAX_BYTES = 8 * 1024

        fun read(input: InputStream): RequestHead {
            val buffer = ByteArray(MAX_BYTES)
            var length = 0
            while (true) {
                val n = try {
                    input.read(buffer, length, MAX_BYTES - length)
                } catch (e: SocketTimeoutException) {
                    return if (length == 0) Empty else Refused(408, "slow", "The request took too long to arrive.")
                }
                if (n < 0) return if (length == 0) Empty else Refused(400, "request", "The request ended before its head did.")
                length += n
                val end = headEnd(buffer, length)
                if (end > 0) return check(buffer.copyOf(length), end)
                if (length == MAX_BYTES) return Refused(431, "too-large", "The request's head is larger than 8 KB.")
            }
        }

        /** The head that is [bytes]' first [end] bytes, checked as NanoHTTPD will read it. */
        fun check(bytes: ByteArray, end: Int): RequestHead {
            val lines = String(bytes, 0, end, Charsets.ISO_8859_1).split(LINE_BREAK)
            val line = lines.first()
            val parts = line.split(' ')
            if (parts.size != 3 || parts.any { it.isEmpty() } || line.any { it !in ' '..'~' }) return Refused(400, "request", NOT_A_REQUEST)
            val (method, target, version) = parts
            if (NanoHTTPD.Method.values().none { it.name == method }) return Refused(501, "method", "The panel doesn't answer that kind of request.")
            if (!target.startsWith('/')) return Refused(400, "request", NOT_A_REQUEST)
            if (version != "HTTP/1.1" && version != "HTTP/1.0") return Refused(505, "version", "The panel speaks HTTP/1.1.")
            if (!decodes(target)) return Refused(400, "request", "The address holds a broken escape.")
            val hosts = lines.drop(1).count { ':' in it && it.substringBefore(':').trim().equals("host", ignoreCase = true) }
            if (hosts > 1) return Refused(400, "host", "The request names its host twice.")
            return Ok(bytes)
        }

        /** The reason phrase for a status [check] or [read] answers with. */
        fun reason(status: Int): String = when (status) {
            400 -> "Bad Request"
            408 -> "Request Timeout"
            431 -> "Request Header Fields Too Large"
            501 -> "Not Implemented"
            505 -> "HTTP Version Not Supported"
            else -> "Error"
        }

        /** Whether NanoHTTPD's decoding (`URLDecoder`, UTF-8) takes [target]'s path and query without throwing. */
        private fun decodes(target: String): Boolean {
            val query = target.indexOf('?')
            return try {
                URLDecoder.decode(if (query >= 0) target.substring(0, query) else target, "UTF-8")
                if (query >= 0) URLDecoder.decode(target.substring(query + 1), "UTF-8")
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        /** Where the head ends, exactly as NanoHTTPD finds it (CRLF CRLF, or LF LF); 0 before it has. */
        private fun headEnd(buf: ByteArray, length: Int): Int {
            var at = 0
            while (at + 1 < length) {
                if (buf[at] == CR && buf[at + 1] == LF && at + 3 < length && buf[at + 2] == CR && buf[at + 3] == LF) return at + 4
                if (buf[at] == LF && buf[at + 1] == LF) return at + 2
                at++
            }
            return 0
        }

        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()
        private const val NOT_A_REQUEST = "That isn't a request the panel reads."
        private val LINE_BREAK = Regex("\r\n|\r|\n")
    }
}

/** The panel's cookies: the session, the guest's id, and reading a `Cookie` header. */
object WebCookies {
    const val SESSION = "sp_session"
    const val GUEST = "sp_guest"

    /**
     * How long a browser keeps the session cookie, in seconds: a year from the sign-in, [Sessions.KNOWN_MS]. Its session acts
     * for the first 90 days of it ([Sessions.VALID_MS]); after that the cookie marks a known device (v1.24 — M56).
     */
    const val SESSION_MAX_AGE_S = Sessions.KNOWN_MS / 1_000

    /** 16 random bytes, URL-safe base64 without padding. */
    val GUEST_ID = Regex("[A-Za-z0-9_-]{22}")

    /** The cookies a `Cookie` header names, the first of a name winning; at most 32 read. */
    fun parse(header: String?): Map<String, String> {
        if (header.isNullOrEmpty()) return emptyMap()
        val cookies = LinkedHashMap<String, String>()
        for (part in header.split(';').take(MAX_COOKIES)) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim().removeSurrounding("\"")
            if (name.isNotEmpty()) cookies.putIfAbsent(name, value)
        }
        return cookies
    }

    /**
     * The session cookie: never readable by scripts, never sent with another site's request, for the
     * whole panel at [path] (`/` on a listener, `/p/<id>/` through the relay), only over HTTPS when
     * [secure] (the relay), and kept a year from the sign-in ([SESSION_MAX_AGE_S], v1.15 — M42) rather
     * than until the browser closes: 90 days of a session, then a known device (v1.24 — M56).
     */
    fun session(token: String, path: String = "/", secure: Boolean = false): String =
        "$SESSION=$token; HttpOnly; SameSite=Strict; Path=$path; Max-Age=$SESSION_MAX_AGE_S" + secureFlag(secure)

    fun endSession(path: String = "/", secure: Boolean = false): String =
        "$SESSION=; HttpOnly; SameSite=Strict; Path=$path; Max-Age=0" + secureFlag(secure)

    /** A guest's id for the request limit, kept a year, at [path] as the session is. */
    fun guest(id: String, path: String = "/", secure: Boolean = false): String =
        "$GUEST=$id; HttpOnly; SameSite=Strict; Path=$path; Max-Age=31536000" + secureFlag(secure)

    private fun secureFlag(secure: Boolean): String = if (secure) "; Secure" else ""

    private const val MAX_COOKIES = 32
}

/**
 * The headers every response carries (CSP with the socket's [host], `host:port` on a listener): the
 * socket is `ws:` for a page served over [scheme] `http`, `wss:` over `https` (the relay, v1.10 — M26).
 */
fun securityHeaders(host: String, scheme: String = "http"): List<Pair<String, String>> = listOf(
    "X-Content-Type-Options" to "nosniff",
    "X-Frame-Options" to "DENY",
    "Referrer-Policy" to "no-referrer",
    "Content-Security-Policy" to
        "default-src 'self'; img-src 'self' data:; connect-src 'self' ${if (scheme == "https") "wss" else "ws"}://$host; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
    "Cross-Origin-Resource-Policy" to "same-origin",
)

private const val HOST = "host"
private const val COOKIE = "cookie"
private const val ORIGIN = "origin"
private const val CONTENT_TYPE = "content-type"
private const val CONTENT_LENGTH = "content-length"
internal const val TRANSFER_ENCODING = "transfer-encoding"
private const val PANEL_HEADER = "x-steven-piano"
private const val PANEL_HEADER_VALUE = "1"
private const val SOCKET_PATH = "/ws"
private const val POSTER_PATH = "/poster"
private const val REQUEST_PAGE = "/request"
internal const val JSON = "application/json; charset=utf-8"
private const val BINARY = "application/octet-stream"
private const val SWITCHING_PROTOCOLS = 101
internal const val COPY_BUFFER = 64 * 1024
private const val GUEST_ID_BYTES = 16
private const val LINGER_MS = 500
private const val LINGER_BYTES = 256 * 1024
private val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

/** How long a socket read may wait. */
const val SOCKET_READ_TIMEOUT_MS = 10_000

/** How long a whole request (its head, and any JSON body) may take to arrive; an upload's body and a socket are exempt once checked. */
const val REQUEST_DEADLINE_MS = 10_000L

private const val NANOS_PER_MS = 1_000_000L

/** An HTTP date (IMF-fixdate), for the answers the server writes itself. */
private val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)

/** How long a handler waits for the app. */
const val CALL_TIMEOUT_MS = 10_000L

/** Threads serving connections, and connections that may wait for one. */
const val POOL_THREADS = 4
const val QUEUE_LENGTH = 32

internal fun contentLength(headers: Map<String, String>): Long? = headers[CONTENT_LENGTH]?.trim()?.toLongOrNull()

/** A wait in whole seconds, rounded up (a page shows "Try again in 30 s"). */
internal fun seconds(ms: Long): Long = (ms + 999) / 1000

internal fun statusOf(code: Int): NanoHTTPD.Response.IStatus =
    NanoHTTPD.Response.Status.lookup(code) ?: object : NanoHTTPD.Response.IStatus {
        override fun getDescription(): String = "$code ${if (code == 507) "Insufficient Storage" else "Error"}"

        override fun getRequestStatus(): Int = code
    }

private fun closeQuietly(closeable: java.io.Closeable?) {
    try {
        closeable?.close()
    } catch (e: IOException) {
        // Already closed, or broken: nothing to do.
    }
}
