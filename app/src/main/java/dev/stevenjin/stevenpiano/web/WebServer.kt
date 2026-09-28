// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web

import android.annotation.SuppressLint
import dev.stevenjin.stevenpiano.data.TextLimits
import dev.stevenjin.stevenpiano.data.imports.ImportLimits
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.piano.PianoSettings
import dev.stevenjin.stevenpiano.player.PlaybackLimits
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
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
 *   or a 404, and a listener that is [Config.guestOnly] (the Wi-Fi one, unless Panel on Wi-Fi too)
 *   knows only the public ones, answering 404 to the rest.
 * - **Every request** must name this listener in its `Host` ([allowedHosts]: its address and port, or
 *   a name the person gave, `webHostName`), or it is refused 403: a page that rebinds its own name to
 *   the tablet's address never gets an answer.
 * - **The panel's routes** need the `sp_session` cookie ([Sessions]; 401 without); those that change
 *   anything also need `X-Steven-Piano: 1` (403 without), which no other site's page can send: the
 *   server never answers with CORS headers, so a browser refuses to send it cross-site. A request
 *   that says where it came from (`Origin`) must come from the panel itself. Login needs the header
 *   and no session; the public routes (the request page and its API, the poster) need neither.
 * - **Every response** carries [securityHeaders] and closes its connection (NanoHTTPD never skips a
 *   body a handler did not read, so keep-alive could take the rest of a refused upload for the next
 *   request; closing also frees the pool's threads at once). APIs are `Cache-Control: no-store`.
 * - **Bodies**: JSON only, at most 64 KB, strict UTF-8, four levels deep ([WebApi]); uploads are a raw
 *   `PUT /api/upload?name=` body with its `Content-Length`, refused 411 / 413 / 415 before a byte is
 *   read, one at a time.
 * - **Threads**: a pool of [POOL_THREADS] with a short queue ([BoundedRunner]); NanoHTTPD's reverse
 *   lookup of every peer's name is skipped ([createClientHandler]); a socket read waits at most
 *   [SOCKET_READ_TIMEOUT_MS] and a whole request at most [REQUEST_DEADLINE_MS] ([DeadlineInput]),
 *   its head read and checked before NanoHTTPD sees it ([RequestHead]); a handler that needs the
 *   app waits at most [CALL_TIMEOUT_MS] for it; a listening socket that keeps failing closes
 *   itself rather than spin ([SteadyServerSocket]).
 * - **The socket** (`/ws`, [WebSockets]) opens only for a session, from the panel's own origin, on a
 *   listener that serves the panel.
 */
class WebServer(
    private val config: Config,
    private val backend: WebBackend,
    private val sessions: Sessions,
    private val guard: LoginGuard,
    private val requests: GuestRequests,
    private val assets: AssetSource,
    private val sockets: WebSockets,
    private val posterPage: suspend (url: String) -> ByteArray? = { null },
    private val random: SecureRandom = SecureRandom(),
) : NanoWSD(config.host, config.port) {

    /**
     * One listener: the address it binds ([host]), its [port] (0 in tests: any free one),
     * [guestOnly] for the Wi-Fi address without Panel on Wi-Fi too, the other [names] requests may
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
    private val uploading = ReentrantLock()

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

    override fun serve(session: IHTTPSession): Response {
        val host = session.headers[HOST]?.trim()?.lowercase()
        val allowed = host != null && host in allowedHosts()
        val response = try {
            if (!allowed) refuse(403, "host", "This address isn't the panel's.") else dispatch(session, host)
        } catch (e: ApiError) {
            error(e)
        } catch (e: TimeoutCancellationException) {
            refuse(503, "busy", "The app is busy. Try again.")
        } catch (e: Exception) {
            refuse(500, "server", "Something went wrong.")
        } catch (e: OutOfMemoryError) {
            refuse(503, "memory", "The tablet is short of memory.")
        }
        return secure(response, if (allowed) host else "${config.host}:$listeningPort")
    }

    private fun dispatch(session: IHTTPSession, host: String): Response {
        if (isWebsocketRequested(session)) return socket(session, host)
        val path = session.uri ?: return notFound()
        val method = session.method
        val matching = routes.filter { (!config.guestOnly || it.access == Access.PUBLIC) && it.pattern.matches(path) }
        if (matching.isEmpty()) return asset(session, path)
        val route = matching.firstOrNull { it.method == method }
            ?: return refuse(405, "method", "Not allowed here.").also { r -> r.addHeader("Allow", matching.joinToString(", ") { it.method.name }) }
        val cookies = WebCookies.parse(session.headers[COOKIE])
        val token = cookies[WebCookies.SESSION]
        when (route.access) {
            Access.READ -> if (!sessions.isValid(token)) return refuse(401, "session", "Enter the PIN first.")
            Access.WRITE -> {
                if (!sessions.isValid(token)) return refuse(401, "session", "Enter the PIN first.")
                checkHeaderAndOrigin(session, host)?.let { return it }
            }
            Access.LOGIN -> checkHeaderAndOrigin(session, host)?.let { return it }
            Access.PUBLIC -> if (method != Method.GET) checkOrigin(session, host)?.let { return it }
        }
        val call = Call(session, route.pattern.matchEntire(path)!!.groupValues.drop(1), host, token, cookies, connection.get())
        return runBlocking {
            if (route.timed) withTimeout(CALL_TIMEOUT_MS) { route.handle(call) } else route.handle(call)
        }
    }

    /** The custom header (no other site's page can send it) and, when the request says where it came from, the panel's own origin. */
    private fun checkHeaderAndOrigin(session: IHTTPSession, host: String): Response? {
        if (session.headers[PANEL_HEADER] != PANEL_HEADER_VALUE) return refuse(403, "header", "This request didn't come from the panel.")
        return checkOrigin(session, host)
    }

    private fun checkOrigin(session: IHTTPSession, host: String): Response? {
        val origin = session.headers[ORIGIN] ?: return null
        return if (origin == "http://$host") null else refuse(403, "origin", "This request came from another site.")
    }

    // ---- Static files --------------------------------------------------------------------------

    private fun asset(session: IHTTPSession, path: String): Response {
        if (path == POSTER_PATH) return poster(session)
        val asset = WebAssets.PUBLIC[path] ?: (if (config.guestOnly) null else WebAssets.PANEL[path]) ?: return notFound()
        if (session.method != Method.GET) return refuse(405, "method", "Not allowed here.").also { it.addHeader("Allow", "GET") }
        val bytes = assets.read(asset.name) ?: return notFound()
        val response = bytesResponse(Response.Status.OK, asset.contentType, bytes)
        response.addHeader("Cache-Control", "no-cache")
        if (path == REQUEST_PAGE) guestCookie(WebCookies.parse(session.headers[COOKIE]))?.let { response.addHeader("Set-Cookie", WebCookies.guest(it)) }
        return response
    }

    private fun poster(session: IHTTPSession): Response {
        if (session.method != Method.GET) return refuse(405, "method", "Not allowed here.").also { it.addHeader("Allow", "GET") }
        val url = runBlocking { backend.state().web.guest } ?: return refuse(503, "offline", "The request page has no address yet.")
        val page = runBlocking { withTimeout(CALL_TIMEOUT_MS) { posterPage(url) } } ?: return notFound()
        return bytesResponse(Response.Status.OK, WebAssets.HTML, page).also { it.addHeader("Cache-Control", "no-cache") }
    }

    // ---- The socket ----------------------------------------------------------------------------

    private fun socket(session: IHTTPSession, host: String): Response {
        if (session.method != Method.GET || session.uri != SOCKET_PATH || config.guestOnly) return notFound()
        if (!sessions.isValid(WebCookies.parse(session.headers[COOKIE])[WebCookies.SESSION])) return refuse(401, "session", "Enter the PIN first.")
        if (session.headers[ORIGIN] != "http://$host") return refuse(403, "origin", "This request came from another site.")
        if (!sockets.hasRoom()) return refuse(503, "sockets", "Too many panels are open.")
        connection.get()?.lift()   // a socket lives long by design: its reads keep the per-read timeout, which the pings feed
        return super.serve(session)   // the handshake; its version and key are checked there
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
    ) {
        val address: String get() = session.remoteIpAddress ?: "unknown"

        fun body(): JSONObject {
            val headers = session.headers
            return WebApi.readObject(session.inputStream, contentLength(headers), headers[CONTENT_TYPE], headers.containsKey(TRANSFER_ENCODING))
        }

        fun param(name: String): String? = session.parameters[name]?.firstOrNull()
    }

    val routes: List<Route> = listOf(
        // The panel reads.
        Route(Method.GET, Regex("/api/state"), Access.READ, "/api/state") { json(WebApi.state(backend.state(), requests.pending.value.size)) },
        Route(Method.GET, Regex("/api/library"), Access.READ, "/api/library") { call -> library(call) },
        Route(Method.GET, Regex("/api/playlists"), Access.READ, "/api/playlists") {
            json(JSONObject().put("playlists", JSONArray().apply { backend.playlists().forEach { put(WebApi.playlist(it)) } }))
        },
        Route(Method.GET, Regex("/api/playlists/(\\d{1,18})"), Access.READ, "/api/playlists/10") { call ->
            val detail = backend.playlist(call.groups[0].toLong()) ?: return@Route notFound()
            json(JSONObject().put("playlist", WebApi.playlist(detail.playlist)).put("pieces", WebApi.pieces(detail.pieces)))
        },
        Route(Method.GET, Regex("/api/composers"), Access.READ, "/api/composers") {
            json(JSONObject().put("composers", JSONArray().apply { backend.composers().forEach { put(WebApi.composer(it)) } }))
        },
        Route(Method.GET, Regex("/api/composers/([^/]{1,120})"), Access.READ, "/api/composers/debussy") { call ->
            val key = composerKey(call.groups[0])
            val detail = backend.composer(key) ?: return@Route notFound()
            json(JSONObject().put("composer", WebApi.composer(detail.composer)).put("pieces", WebApi.pieces(detail.pieces)))
        },
        Route(Method.GET, Regex("/api/art/composer/([^/]{1,120})"), Access.READ, "/api/art/composer/debussy") { call ->
            val size = call.param("size")?.let { WebArtSize.of(it) ?: throw ApiError(400, "field", "size must be row or tile.") } ?: WebArtSize.ROW
            image(backend.composerArt(composerKey(call.groups[0]), size))
        },
        Route(Method.GET, Regex("/api/art/piece/(\\d{1,18})"), Access.READ, "/api/art/piece/1") { call -> image(backend.pieceArt(call.groups[0].toLong())) },
        Route(Method.GET, Regex("/api/channels"), Access.READ, "/api/channels") { json(WebApi.channels(backend.channels())) },
        Route(Method.GET, Regex("/api/requests"), Access.READ, "/api/requests") { json(WebApi.requests(requests.pending.value, backend.guestSettings())) },
        Route(Method.GET, Regex("/api/piano"), Access.READ, "/api/piano") { json(WebApi.piano(backend.piano())) },

        // The panel acts.
        Route(Method.POST, Regex("/api/play"), Access.WRITE, "/api/play") { call ->
            val body = call.body()
            WebApi.onlyKeys(body, setOf("pieceId", "queue"))
            if (backend.play(WebApi.id(body, "pieceId"), WebApi.idsOrNull(body, "queue"))) noContent() else notFound()
        },
        Route(Method.POST, Regex("/api/play-all"), Access.WRITE, "/api/play-all") { call ->
            val body = call.body()
            WebApi.onlyKeys(body, setOf("ids", "playlistId", "shuffle"))
            val shuffle = WebApi.boolOrNull(body, "shuffle") ?: false
            val ids = WebApi.idsOrNull(body, "ids")
            val playlist = WebApi.wholeOrNull(body, "playlistId")
            val started = when {
                ids != null && playlist == null -> ids.isNotEmpty() && backend.playAll(ids, shuffle)
                playlist != null && ids == null -> backend.playPlaylist(playlist, shuffle)
                else -> throw ApiError(400, "field", "Give ids or playlistId.")
            }
            if (started) noContent() else notFound()
        },
        Route(Method.POST, Regex("/api/transport"), Access.WRITE, "/api/transport") { call ->
            val body = call.body()
            WebApi.onlyKeys(body, setOf("action"))
            backend.transport(Transport.of(WebApi.string(body, "action", 16)) ?: throw ApiError(400, "field", "Unknown action."))
            noContent()
        },
        Route(Method.POST, Regex("/api/seek"), Access.WRITE, "/api/seek") { call ->
            val body = call.body()
            WebApi.onlyKeys(body, setOf("ms"))
            val ms = WebApi.whole(body, "ms")
            if (ms < 0) throw ApiError(400, "range", "ms must be 0 or more.")
            backend.seek(ms)
            noContent()
        },
        Route(Method.POST, Regex("/api/tempo"), Access.WRITE, "/api/tempo") { call ->
            val body = call.body()
            WebApi.onlyKeys(body, setOf("pct"))
            backend.setTempo(WebApi.int(body, "pct", PlaybackLimits.TempoPct))
            noContent()
        },
        Route(Method.POST, Regex("/api/shuffle"), Access.WRITE, "/api/shuffle") { call ->
            val body = call.body()
            WebApi.onlyKeys(body, setOf("on"))
            backend.setShuffle(WebApi.bool(body, "on"))
            noContent()
        },
        Route(Method.POST, Regex("/api/repeat"), Access.WRITE, "/api/repeat") { call ->
            val body = call.body()
            WebApi.onlyKeys(body, setOf("mode"))
            backend.setRepeat(WebApi.repeatOf(WebApi.string(body, "mode", 8)))
            noContent()
        },
        Route(Method.POST, Regex("/api/queue"), Access.WRITE, "/api/queue") { call ->
            if (backend.queue(WebApi.queueCommand(call.body()))) noContent() else notFound()
        },
        Route(Method.POST, Regex("/api/channels/([a-z0-9_-]{1,40})/play"), Access.WRITE, "/api/channels/calm/play") { call ->
            when (backend.playChannel(call.groups[0])) {
                ChannelStart.STARTED -> noContent()
                ChannelStart.TOO_SMALL -> refuse(409, "too-small", "Add more pieces: this channel needs three at least.")
                ChannelStart.UNKNOWN -> notFound()
            }
        },
        Route(Method.POST, Regex("/api/channels/stop"), Access.WRITE, "/api/channels/stop") {
            backend.stopChannel()
            noContent()
        },
        Route(Method.PUT, Regex("/api/channels/([a-z0-9_-]{1,40})/volume"), Access.WRITE, "/api/channels/calm/volume") { call ->
            val body = call.body()
            WebApi.onlyKeys(body, setOf("pct"))
            if (backend.setChannelVolume(call.groups[0], WebApi.int(body, "pct", 0..100))) noContent() else notFound()
        },
        Route(Method.POST, Regex("/api/requests/(\\d{1,18})/approve"), Access.WRITE, "/api/requests/1/approve") { call ->
            val request = requests.take(call.groups[0].toLong()) ?: return@Route notFound()
            requests.noteQueued(backend.queueRequested(request.pieceId))
            noContent()
        },
        Route(Method.POST, Regex("/api/requests/(\\d{1,18})/dismiss"), Access.WRITE, "/api/requests/1/dismiss") { call ->
            if (requests.dismiss(call.groups[0].toLong())) noContent() else notFound()
        },
        Route(Method.PUT, Regex("/api/piano/([a-z_]{1,32})"), Access.WRITE, "/api/piano/volume") { call ->
            val setting = PianoSettings.named(call.groups[0]) ?: return@Route notFound()
            val body = call.body()
            WebApi.onlyKeys(body, setOf("value"))
            if (!body.has("value")) throw ApiError(400, "field", "value is missing.")
            backend.setPiano(setting.name, WebApi.pianoWire(setting, body.get("value")))
            noContent()
        },
        Route(Method.POST, Regex("/api/piano/preset"), Access.WRITE, "/api/piano/preset") { call ->
            val body = call.body()
            WebApi.onlyKeys(body, setOf("name"))
            val name = WebApi.string(body, "name", 32)
            val preset = PianoSettings.presets.firstOrNull { it.command == name } ?: throw ApiError(400, "field", "Unknown preset.")
            backend.pianoPreset(preset.command)
            noContent()
        },
        Route(Method.POST, Regex("/api/piano/action"), Access.WRITE, "/api/piano/action") { call ->
            val body = call.body()
            WebApi.onlyKeys(body, setOf("name"))
            val action = PANEL_ACTIONS[WebApi.string(body, "name", 16)] ?: throw ApiError(400, "field", "The action must be off, save or status.")
            backend.pianoAction(action)
            noContent()
        },
        Route(Method.PUT, Regex("/api/settings"), Access.WRITE, "/api/settings") { call ->
            backend.applySettings(WebApi.settingsChange(call.body()))
            noContent()
        },
        Route(Method.PUT, Regex("/api/upload"), Access.WRITE, "/api/upload?name=a.mid", timed = false) { call -> upload(call) },
        Route(Method.POST, Regex("/api/logout"), Access.WRITE, "/api/logout") { call ->
            sessions.close(call.token)
            noContent().also { it.addHeader("Set-Cookie", WebCookies.endSession()) }
        },

        // Logging in.
        Route(Method.POST, Regex("/api/login"), Access.LOGIN, "/api/login") { call -> login(call) },

        // Guests.
        Route(Method.GET, Regex("/api/public/catalogue"), Access.PUBLIC, "/api/public/catalogue") {
            val guests = backend.guestSettings()
            json(WebApi.catalogue(guests.open, if (guests.open) backend.catalogue() else emptyList()))
        },
        Route(Method.POST, Regex("/api/public/request"), Access.PUBLIC, "/api/public/request") { call -> guestRequest(call) },
    )

    private suspend fun library(call: Call): Response {
        val query = call.param("q")?.let { TextLimits.clip(it, TextLimits.TITLE) }?.trim()
        val category = call.param("category")?.let { LibraryCategory.of(it) ?: throw ApiError(400, "field", "category must be all, favorites or recent.") } ?: LibraryCategory.ALL
        val offset = call.param("offset")?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: throw ApiError(400, "field", "offset must be 0 or more.") } ?: 0
        val limit = call.param("limit")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..WebLimits.PAGE_MAX } ?: throw ApiError(400, "field", "limit must be from 1 to ${WebLimits.PAGE_MAX}.") } ?: DEFAULT_PAGE
        return json(WebApi.page(backend.library(query, category, offset, limit)))
    }

    private suspend fun login(call: Call): Response {
        val body = call.body()
        WebApi.onlyKeys(body, setOf("pin"))
        val pin = WebApi.string(body, "pin", MAX_PIN_TEXT)
        val address = call.address
        val wait = guard.waitMs(address)
        if (wait > 0) return waitResponse(wait)
        val hash = backend.pinHash() ?: return refuse(403, "no-pin", "Set a PIN on the tablet first.")
        // Checked and counted as one step, one try at a time (LoginGuard.attempt): tries sent at once can't all slip past the wait.
        return when (val attempt = guard.attempt(address) { hash.matches(pin) }) {
            is LoginGuard.Attempt.Wait -> waitResponse(attempt.ms)
            is LoginGuard.Attempt.Wrong ->
                json(WebApi.error("wrong", "That PIN isn't right.").put("retryAfter", seconds(attempt.waitMs)), Response.Status.UNAUTHORIZED)
            LoginGuard.Attempt.Right -> noContent().also { it.addHeader("Set-Cookie", WebCookies.session(sessions.open())) }
        }
    }

    private suspend fun guestRequest(call: Call): Response {
        val body = call.body()
        WebApi.onlyKeys(body, setOf("pieceId"))
        val pieceId = WebApi.id(body, "pieceId")
        val guests = backend.guestSettings()
        if (!guests.open) return refuse(403, "closed", "Requests are closed right now.")
        val piece = backend.catalogue().asSequence().flatMap { it.pieces }.firstOrNull { it.id == pieceId }
            ?: return refuse(400, "not-offered", "That piece isn't on the list.")
        val cookie = guestCookie(call.cookies)
        val guest = cookie ?: call.cookies[WebCookies.GUEST]!!
        val keys = listOf("guest:$guest", "address:${call.address}")
        val response = when (val outcome = requests.submit(piece, keys, guests.approveFirst)) {
            is GuestRequests.Outcome.Wait -> waitResponse(outcome.retryAfterMs)
            GuestRequests.Outcome.Full -> refuse(503, "full", "The list is full for now. Try again later.")
            is GuestRequests.Outcome.Pending -> json(JSONObject().put("status", "pending"), Response.Status.ACCEPTED)
            GuestRequests.Outcome.Queue -> {
                requests.noteQueued(backend.queueRequested(piece.id))
                json(JSONObject().put("status", "queued"), Response.Status.ACCEPTED)
            }
        }
        cookie?.let { response.addHeader("Set-Cookie", WebCookies.guest(it)) }
        return response
    }

    /** A new guest id when the request carries none that looks like one; null when it has one. */
    private fun guestCookie(cookies: Map<String, String>): String? {
        if (cookies[WebCookies.GUEST]?.let(WebCookies.GUEST_ID::matches) == true) return null
        return ByteArray(GUEST_ID_BYTES).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    }

    /**
     * `PUT /api/upload?name=<file>`: the file is the whole body. Its name must end .mid, .midi or .zip
     * (415), its length must be declared and not chunked (411), a MIDI file at most [MIDI_BYTES] and a
     * zip at most [ZIP_BYTES] (413), all before a byte is read, and one upload at a time (409). A MIDI
     * file is read to memory through the importer's own cap; a zip is streamed to `cacheDir/web` with
     * the free-space margin kept. Then the import starts in the app and the answer is 202.
     */
    private suspend fun upload(call: Call): Response {
        val name = uploadName(call.param("name")) ?: throw ApiError(400, "name", "The file needs a name.")
        val kind = UploadKind.of(name) ?: throw ApiError(415, "type", "Only .mid, .midi and .zip files can be added.")
        val headers = call.session.headers
        if (headers.containsKey(TRANSFER_ENCODING)) throw ApiError(411, "length", "The upload must say how long it is.")
        val length = contentLength(headers) ?: throw ApiError(411, "length", "The upload must say how long it is.")
        if (length < 0) throw ApiError(400, "length", "The upload's length is not a length.")
        if (length > kind.cap) throw ApiError(413, "too-large", kind.tooLarge)
        if (length == 0L) throw ApiError(400, "empty", "The file is empty.")
        if (!uploading.tryLock()) throw ApiError(409, "busy", "Another file is being added. Try again in a moment.")
        try {
            // Signed in and checked: the body may take longer than a request's deadline (64 MB over slow Wi-Fi);
            // each read still waits at most the socket's per-read timeout.
            call.connection?.lift()
            val input = LimitedInputStream(call.session.inputStream, length)
            when (kind) {
                UploadKind.MIDI -> {
                    val bytes = try {
                        ImportLimits.readCapped(input, MIDI_BYTES.toInt())
                    } catch (e: IOException) {
                        null
                    }
                    if (bytes == null || bytes.size.toLong() != length) throw ApiError(400, "short", "The upload ended early.")
                    backend.importMidi(name, bytes)
                }
                UploadKind.ZIP -> backend.importZip(name, saveZip(input, length))
            }
        } finally {
            uploading.unlock()
        }
        return json(JSONObject().put("name", name), Response.Status.ACCEPTED)
    }

    /**
     * The zip's bytes into `cacheDir/web/upload-….zip`, all [length] of them, with the cache's
     * free-space margin kept; deleted on any failure. The margin is a floor under a file of at
     * most 64 MB, as the importer's own zip copy keeps it, not an allocation: `usableSpace` is the
     * question to ask.
     */
    @SuppressLint("UsableSpace")
    private fun saveZip(input: InputStream, length: Long): File {
        val dir = backend.uploadDir
        if (!dir.isDirectory) dir.mkdirs()
        if (dir.usableSpace < length + ImportLimits.SPACE_MARGIN_BYTES) throw ApiError(507, "space", "The tablet hasn't the room for this file.")
        val file = File.createTempFile(UPLOAD_PREFIX, ".zip", dir)
        try {
            var total = 0L
            FileOutputStream(file).use { out ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    out.write(buffer, 0, n)
                }
            }
            if (total != length) throw ApiError(400, "short", "The upload ended early.")
            return file
        } catch (e: IOException) {
            file.delete()
            throw ApiError(400, "short", "The upload ended early.")
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    // ---- Responses -----------------------------------------------------------------------------

    private fun json(body: JSONObject, status: Response.Status = Response.Status.OK): Response =
        bytesResponse(status, JSON, body.toString().toByteArray(Charsets.UTF_8)).also { it.addHeader("Cache-Control", "no-store") }

    private fun noContent(): Response =
        newFixedLengthResponse(Response.Status.NO_CONTENT, null, ByteArrayInputStream(ByteArray(0)), 0).also { it.addHeader("Cache-Control", "no-store") }

    private fun notFound(): Response = refuse(404, "not-found", "Not here.")

    private fun image(image: WebImage?): Response {
        if (image == null || image.contentType !in IMAGE_TYPES) return notFound()
        return bytesResponse(Response.Status.OK, image.contentType, image.bytes).also { it.addHeader("Cache-Control", "private, max-age=3600") }
    }

    private fun refuse(status: Int, code: String, message: String): Response =
        bytesResponse(statusOf(status), JSON, WebApi.error(code, message).toString().toByteArray(Charsets.UTF_8)).also { it.addHeader("Cache-Control", "no-store") }

    private fun error(e: ApiError): Response = refuse(e.status, e.code, e.message)

    /** 429 with how long to wait, in the body and in `Retry-After`. */
    private fun waitResponse(ms: Long): Response {
        val secs = seconds(ms)
        return json(WebApi.error("wait", "Try again in $secs s.").put("retryAfter", secs), Response.Status.TOO_MANY_REQUESTS)
            .also { it.addHeader("Retry-After", secs.toString()) }
    }

    private fun bytesResponse(status: Response.IStatus, type: String?, bytes: ByteArray): Response =
        newFixedLengthResponse(status, type, ByteArrayInputStream(bytes), bytes.size.toLong())

    /**
     * The headers on every response: no sniffing, no framing, no referrer, the content security
     * policy (this origin only, the socket at [host], no frames, no base, no forms), resources for
     * this origin only; and the connection closes after it, but for a socket's handshake.
     */
    private fun secure(response: Response, host: String): Response {
        for ((name, value) in securityHeaders(host)) response.addHeader(name, value)
        if (response.status.requestStatus != SWITCHING_PROTOCOLS) response.closeConnection(true)
        return response
    }

    private companion object {
        /** The piano's actions the panel may send: All keys off, Save now, Read status (no strike or LED tests from afar). */
        val PANEL_ACTIONS = mapOf("off" to PianoAction.AllKeysOff, "save" to PianoAction.Save, "status" to PianoAction.Status)
    }

    /** What kinds of file may be uploaded, and how large. */
    private enum class UploadKind(val cap: Long, val tooLarge: String) {
        MIDI(MIDI_BYTES, "A MIDI file can be 8 MB at most."),
        ZIP(ZIP_BYTES, "A zip can be 64 MB at most."),
        ;

        companion object {
            fun of(name: String): UploadKind? {
                val lower = name.lowercase()
                return when {
                    lower.endsWith(".mid") || lower.endsWith(".midi") -> MIDI
                    lower.endsWith(".zip") -> ZIP
                    else -> null
                }
            }
        }
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

    /** At most [length] bytes of [input]: an upload's body and nothing after it. */
    private class LimitedInputStream(private val input: InputStream, private var left: Long) : InputStream() {
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

    /** Failures in a row so far. */
    val failuresInARow: Int get() = failures

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

    /** Whether the deadline still holds. */
    val deadlined: Boolean get() = deadline != LIFTED

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

    /** The session cookie: never readable by scripts, never sent with another site's request, for the whole panel. */
    fun session(token: String): String = "$SESSION=$token; HttpOnly; SameSite=Strict; Path=/"

    fun endSession(): String = "$SESSION=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0"

    /** A guest's id for the request limit, kept a year. */
    fun guest(id: String): String = "$GUEST=$id; HttpOnly; SameSite=Strict; Path=/; Max-Age=31536000"

    private const val MAX_COOKIES = 32
}

/** The headers every response carries (CSP with the socket's [host], `host:port`). */
fun securityHeaders(host: String): List<Pair<String, String>> = listOf(
    "X-Content-Type-Options" to "nosniff",
    "X-Frame-Options" to "DENY",
    "Referrer-Policy" to "no-referrer",
    "Content-Security-Policy" to
        "default-src 'self'; img-src 'self' data:; connect-src 'self' ws://$host; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
    "Cross-Origin-Resource-Policy" to "same-origin",
)

private const val HOST = "host"
private const val COOKIE = "cookie"
private const val ORIGIN = "origin"
private const val CONTENT_TYPE = "content-type"
private const val CONTENT_LENGTH = "content-length"
private const val TRANSFER_ENCODING = "transfer-encoding"
private const val PANEL_HEADER = "x-steven-piano"
private const val PANEL_HEADER_VALUE = "1"
private const val SOCKET_PATH = "/ws"
private const val POSTER_PATH = "/poster"
private const val REQUEST_PAGE = "/request"
private const val JSON = "application/json; charset=utf-8"
private const val SWITCHING_PROTOCOLS = 101
private const val UPLOAD_PREFIX = "upload-"
private const val COPY_BUFFER = 64 * 1024
private const val GUEST_ID_BYTES = 16
private const val MAX_PIN_TEXT = 16
private const val LINGER_MS = 500
private const val LINGER_BYTES = 256 * 1024
private const val DEFAULT_PAGE = 50
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

/** Upload caps: a MIDI file as the importer's own, a zip well under the importer's 512 MB. */
const val MIDI_BYTES = 8L * 1024 * 1024
const val ZIP_BYTES = 64L * 1024 * 1024

/** A composer key from a path: already percent-decoded by NanoHTTPD, cut as the library cuts keys, no control characters. */
private fun composerKey(raw: String): String {
    if (raw.any { it.isISOControl() }) throw ApiError(400, "field", "Not a composer.")
    return TextLimits.clip(raw, TextLimits.COMPOSER)
}

/** An upload's file name: the last part of what was given (no folders), no control characters, cut to a display name's length. */
private fun uploadName(raw: String?): String? {
    val base = raw?.substringAfterLast('/')?.substringAfterLast('\\')?.filterNot { it.isISOControl() }?.trim() ?: return null
    return TextLimits.clip(base, TextLimits.DISPLAY_NAME).takeIf { it.isNotEmpty() && it != "." && it != ".." }
}

private fun contentLength(headers: Map<String, String>): Long? = headers[CONTENT_LENGTH]?.trim()?.toLongOrNull()

/** A wait in whole seconds, rounded up (a page shows "Try again in 30 s"). */
private fun seconds(ms: Long): Long = (ms + 999) / 1000

private fun statusOf(code: Int): NanoHTTPD.Response.IStatus =
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
