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
import dev.stevenjin.stevenpiano.data.Genres
import dev.stevenjin.stevenpiano.data.LibraryScope
import dev.stevenjin.stevenpiano.data.TextLimits
import dev.stevenjin.stevenpiano.data.art.CoverChange
import dev.stevenjin.stevenpiano.data.art.CoverPicker
import dev.stevenjin.stevenpiano.data.art.CoverSearch
import dev.stevenjin.stevenpiano.data.imports.ImportLimits
import dev.stevenjin.stevenpiano.piano.PianoAction
import dev.stevenjin.stevenpiano.piano.PianoSettings
import dev.stevenjin.stevenpiano.player.PlaybackLimits
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.quiet.QuietCopy
import dev.stevenjin.stevenpiano.web.WebServer.Access
import dev.stevenjin.stevenpiano.web.WebServer.Call
import dev.stevenjin.stevenpiano.web.WebServer.Route
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * The panel's routes ([WebServer.routes], v1.27 — M63: apart from the server's plumbing in WebServer.kt): every path the
 * API answers, who may call it ([WebServer.Access]), a sample path the tests call, and its handler, here as the
 * server's own extensions. The checks a route meets before its handler runs (the host, the session, the header and the
 * origin) and how answers are made are the server's.
 */
internal fun WebServer.routeTable(): List<Route> = listOf(
    // The panel reads.
    Route(Method.GET, Regex("/api/state"), Access.READ, "/api/state") { json(WebApi.state(backend.state(), requests.pending.value.size)) },
    Route(Method.GET, Regex("/api/library"), Access.READ, "/api/library") { call -> library(call) },
    Route(Method.GET, Regex("/api/playlists"), Access.READ, "/api/playlists") { call ->
        json(JSONObject().put("playlists", JSONArray().apply { backend.playlists(scope(call)).forEach { put(WebApi.playlist(it)) } }))
    },
    Route(Method.GET, Regex("/api/playlists/(\\d{1,18})"), Access.READ, "/api/playlists/10") { call ->
        val detail = backend.playlist(call.groups[0].toLong()) ?: return@Route notFound()
        json(JSONObject().put("playlist", WebApi.playlist(detail.playlist)).put("pieces", WebApi.pieces(detail.pieces)))
    },
    Route(Method.GET, Regex("/api/composers"), Access.READ, "/api/composers") { call ->
        json(JSONObject().put("composers", JSONArray().apply { backend.composers(scope(call)).forEach { put(WebApi.composer(it)) } }))
    },
    Route(Method.GET, Regex("/api/composers/([^/]{1,120})"), Access.READ, "/api/composers/debussy") { call ->
        val key = composerKey(call.groups[0])
        val detail = backend.composer(key, scope(call)) ?: return@Route notFound()
        json(JSONObject().put("composer", WebApi.composer(detail.composer)).put("pieces", WebApi.pieces(detail.pieces)))
    },
    Route(Method.GET, Regex("/api/art/composer/([^/]{1,120})"), Access.READ, "/api/art/composer/debussy") { call ->
        val size = call.param("size")?.let { WebArtSize.of(it) ?: throw ApiError(400, "field", "size must be row, tile or full.") } ?: WebArtSize.ROW
        image(backend.composerArt(composerKey(call.groups[0]), size), versioned = call.param("v") != null)
    },
    // A piece's art (v1.17 — M45): `kind` cover or roll, one picture each (v1.27 — M63: always named; the chain older pages
    // took without one is gone); `size` row, tile or (v1.18 — M47) full, Now playing's (absent: tile).
    Route(Method.GET, Regex("/api/art/piece/(\\d{1,18})"), Access.READ, "/api/art/piece/1?kind=cover") { call ->
        val kind = WebArtKind.of(call.param("kind")) ?: throw ApiError(400, "field", "kind must be cover or roll.")
        val size = call.param("size")?.let { WebArtSize.of(it) ?: throw ApiError(400, "field", "size must be row, tile or full.") } ?: WebArtSize.TILE
        image(backend.pieceArt(call.groups[0].toLong(), kind, size), versioned = call.param("v") != null)
    },
    Route(Method.GET, Regex("/api/channels"), Access.READ, "/api/channels") { json(WebApi.channels(backend.channels())) },
    Route(Method.GET, Regex("/api/requests"), Access.READ, "/api/requests") { json(WebApi.requests(requests.pending.value, backend.guestSettings())) },
    Route(Method.GET, Regex("/api/piano"), Access.READ, "/api/piano") { json(WebApi.piano(backend.piano())) },
    Route(Method.GET, Regex("/api/studio/seed"), Access.READ, "/api/studio/seed") { call -> studioSeed(call) },
    // The views of the piece playing (v1.13 — M32): its notes, its score's index and pages, the score's font. Read-only,
    // each input a bounded integer, the work bounded by NowViews.
    Route(Method.GET, Regex("/api/now/notes"), Access.READ, "/api/now/notes") { call -> now(backend.nowNotes(rev(call))) },
    Route(Method.GET, Regex("/api/now/score"), Access.READ, "/api/now/score?w=800&h=600") { call ->
        now(backend.nowScore(rev(call), bounded(call, "w", WebLimits.VIEW_SIZE), bounded(call, "h", WebLimits.VIEW_SIZE)))
    },
    Route(Method.GET, Regex("/api/now/score/(\\d{1,10})/page/(\\d{1,5})"), Access.READ, "/api/now/score/1/page/0") { call ->
        val id = call.groups[0].toLong().takeIf { it <= Int.MAX_VALUE } ?: throw ApiError(400, "field", "Not a layout.")
        now(backend.nowScorePage(id.toInt(), call.groups[1].toInt()))
    },
    Route(Method.GET, Regex("/api/font/bravura\\.otf"), Access.READ, "/api/font/bravura.otf") { font() },
    // The System page (v1.18 — M46): the tablet, the app and the piano now, a day of it, and Share diagnostics' zip.
    Route(Method.GET, Regex("/api/system"), Access.READ, "/api/system") { json(WebApi.system(backend.system())) },
    Route(Method.GET, Regex("/api/system/history"), Access.READ, "/api/system/history") { json(WebApi.systemHistory(backend.systemHistory())) },
    Route(Method.GET, Regex("/api/system/diagnostics"), Access.READ, "/api/system/diagnostics") { diagnosticsZip() },
    // The Settings page's Playback and Panel (v1.18 — M47b): the app's own settings, their limits, what the piano says of them.
    Route(Method.GET, Regex("/api/settings"), Access.READ, "/api/settings") { json(WebApi.settings(backend.settings())) },

    // The panel acts.
    Route(Method.POST, Regex("/api/play"), Access.WRITE, "/api/play") { call ->
        val body = call.body()
        WebApi.onlyKeys(body, setOf("pieceId", "queue"))
        val pieceId = WebApi.id(body, "pieceId")
        val queue = WebApi.idsOrNull(body, "queue")
        startRefusal()?.let { return@Route it }
        if (backend.play(pieceId, queue)) noContent() else notFound()
    },
    Route(Method.POST, Regex("/api/play-all"), Access.WRITE, "/api/play-all") { call ->
        val body = call.body()
        // v1.26 — M61: no shuffle anywhere; a body that still sends one is answered 400 (an unknown field).
        WebApi.onlyKeys(body, setOf("ids", "playlistId"))
        val ids = WebApi.idsOrNull(body, "ids")
        val playlist = WebApi.wholeOrNull(body, "playlistId")
        if ((ids == null) == (playlist == null)) throw ApiError(400, "field", "Give ids or playlistId.")
        startRefusal()?.let { return@Route it }
        val started = if (ids != null) ids.isNotEmpty() && backend.playAll(ids) else backend.playPlaylist(playlist!!)
        if (started) noContent() else notFound()
    },
    Route(Method.POST, Regex("/api/transport"), Access.WRITE, "/api/transport") { call ->
        val body = call.body()
        WebApi.onlyKeys(body, setOf("action"))
        val action = Transport.of(WebApi.string(body, "action", 16)) ?: throw ApiError(400, "field", "Unknown action.")
        if (startsPlaying(action)) startRefusal()?.let { return@Route it }
        backend.transport(action)
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
    Route(Method.POST, Regex("/api/repeat"), Access.WRITE, "/api/repeat") { call ->
        val body = call.body()
        WebApi.onlyKeys(body, setOf("mode"))
        backend.setRepeat(WebApi.repeatOf(WebApi.string(body, "mode", 8)))
        noContent()
    },
    Route(Method.POST, Regex("/api/queue"), Access.WRITE, "/api/queue") { call ->
        val command = WebApi.queueCommand(call.body())
        // Playing an entry now starts it; what is added waits while quiet (the player keeps it, loaded, for Play).
        if (command is QueueCommand.Skip) startRefusal()?.let { return@Route it }
        if (backend.queue(command)) noContent() else notFound()
    },
    Route(Method.POST, Regex("/api/channels/([a-z0-9_-]{1,40})/play"), Access.WRITE, "/api/channels/calm/play") { call ->
        startRefusal()?.let { return@Route it }
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
    // Studio (v1.7 — M23): a recording to transcribe, and a job's Cancel.
    Route(Method.PUT, Regex("/api/studio/audio"), Access.WRITE, "/api/studio/audio?name=a.wav", timed = false) { call -> studioUpload(call) },
    Route(Method.POST, Regex("/api/studio/jobs/([1-9]\\d{0,17})/cancel"), Access.WRITE, "/api/studio/jobs/1/cancel") { call ->
        if (backend.cancelStudioJob(call.groups[0].toLong())) noContent() else notFound()
    },
    // Composing (v1.7 — M24): a composition from a piece of the library, with the form's choices.
    Route(Method.POST, Regex("/api/studio/compose"), Access.WRITE, "/api/studio/compose") { call -> studioCompose(call) },
    Route(Method.POST, Regex("/api/logout"), Access.WRITE, "/api/logout") { call ->
        sessions.close(call.token)
        noContent().also { it.addHeader("Set-Cookie", WebCookies.endSession(call.edge.cookiePath, call.edge.secure)) }
    },
    // Confirm it's you (v1.24 — M56): the PIN again, before the microphone or an update starts.
    Route(Method.POST, Regex("/api/confirm"), Access.WRITE, "/api/confirm") { call -> confirm(call) },
    // The System page's two (v1.18 — M46): the piano's live facts now (the body is not read), and a tool by name.
    Route(Method.POST, Regex("/api/system/refresh"), Access.WRITE, "/api/system/refresh") { json(JSONObject().put("refreshed", backend.refreshPiano())) },
    Route(Method.POST, Regex("/api/system/tool"), Access.WRITE, "/api/system/tool") { call -> systemTool(call) },
    // The cover picker (v1.18 — M48): Apple's catalogue searched by hand, and a piece's cover chosen from it or taken away.
    Route(Method.POST, Regex("/api/covers/search"), Access.WRITE, "/api/covers/search") { call -> coverSearch(call) },
    Route(Method.POST, Regex("/api/covers/choose"), Access.WRITE, "/api/covers/choose") { call -> coverChoose(call) },
    Route(Method.POST, Regex("/api/covers/remove"), Access.WRITE, "/api/covers/remove") { call -> coverRemove(call) },

    // Logging in.
    Route(Method.POST, Regex("/api/login"), Access.LOGIN, "/api/login") { call -> login(call) },

    // Guests.
    Route(Method.GET, Regex("/api/public/catalogue"), Access.PUBLIC, "/api/public/catalogue") {
        val guests = backend.guestSettings()
        json(
            WebApi.catalogue(guests.open, if (guests.open) backend.catalogue() else emptyList())
                .put("quiet", if (guests.open) WebApi.guestQuiet(backend.quietNow()) else JSONObject.NULL),   // v1.20 — M54
        )
    },
    Route(Method.POST, Regex("/api/public/request"), Access.PUBLIC, "/api/public/request") { call -> guestRequest(call) },

    // Quiet times (v1.20 — M54): the sections and the quiet now; every section at once; Play anyway.
    Route(Method.GET, Regex("/api/quiet"), Access.READ, "/api/quiet") { json(WebApi.quiet(backend.quiet())) },
    Route(Method.PUT, Regex("/api/quiet"), Access.WRITE, "/api/quiet") { call ->
        backend.saveQuiet(WebApi.quietSections(call.body(WebApi.QUIET_DEPTH)))
        noContent()
    },
    Route(Method.POST, Regex("/api/quiet/override"), Access.WRITE, "/api/quiet/override") {
        if (backend.overrideQuiet()) noContent() else refuse(409, "not-quiet", "It isn't a quiet time now.")
    },

    // Listening and the key test (v1.23 — M55): the tablet's microphone, live, to the session that asks alone; and every key
    // struck in turn and heard. The bodies are not read; a refusal is 409 {error, message} in the tablet's words. A start
    // needs the session to have confirmed it's you (v1.24 — M56); a stop never does.
    Route(Method.POST, Regex("/api/listen/start"), Access.WRITE, "/api/listen/start") { call -> needsConfirm(call) ?: heard(call) { backend.listenStart(it) } },
    Route(Method.POST, Regex("/api/listen/stop"), Access.WRITE, "/api/listen/stop") {
        backend.listenStop()
        noContent()
    },
    Route(Method.POST, Regex("/api/keytest/start"), Access.WRITE, "/api/keytest/start") { call -> needsConfirm(call) ?: heard(call) { backend.keyTestStart(it) } },
    Route(Method.POST, Regex("/api/keytest/stop"), Access.WRITE, "/api/keytest/stop") {
        backend.keyTestStop()
        noContent()
    },
)

/**
 * The genre a Library read asks for (v1.14 — M37, the panel's switch): absent, every piece; `genre=classical` or
 * `genre=modern` ([Genres.named]); anything else is refused.
 */
private fun scope(call: Call): LibraryScope {
    val raw = call.param("genre") ?: return LibraryScope.All
    val genre = Genres.named(raw) ?: throw ApiError(400, "field", "genre must be classical or modern.")
    return LibraryScope.entries.first { it.genre == genre }
}

/** A `rev` the views were asked for: absent (the current one), or a whole number that fits an Int. */
private fun rev(call: Call): Int? {
    val raw = call.param("rev") ?: return null
    return raw.takeIf { REV.matches(it) }?.toLong()?.takeIf { it <= Int.MAX_VALUE }?.toInt() ?: throw ApiError(400, "field", "rev must be a whole number.")
}

/** A required whole-number parameter within [range]. */
private fun bounded(call: Call, name: String, range: IntRange): Int =
    call.param(name)?.takeIf { REV.matches(it) }?.toLong()?.takeIf { it in range.first..range.last }?.toInt()
        ?: throw ApiError(400, "field", "$name must be from ${range.first} to ${range.last}.")

/** A views answer ([NowAnswer]) as HTTP: the bytes, 202 while a layout runs, 409 stale, 404 nothing playing, 413, 429. */
private fun WebServer.now(answer: NowAnswer): Response = when (answer) {
    is NowAnswer.Ready -> binary(answer.bytes)
    is NowAnswer.Working -> working(answer.retryAfterMs)
    NowAnswer.Stale -> refuse(409, "stale", "The piece or its layout has changed. Ask again.")
    NowAnswer.NoPiece -> refuse(404, "no-piece", "Nothing is playing.")
    NowAnswer.TooLarge -> refuse(413, "too-large", "This score is too large to show.")
    is NowAnswer.Busy -> waitResponse(answer.retryAfterMs)
}

/** The score's font, Bravura, exactly as the app carries it (its licence reserves the name: never changed), cached for a year under its versioned address. */
private fun WebServer.font(): Response {
    val bytes = assets.read(WebAssets.FONT.name) ?: return notFound()
    return bytesResponse(Response.Status.OK, WebAssets.FONT.contentType, bytes).also { it.addHeader("Cache-Control", "private, max-age=31536000, immutable") }
}

private suspend fun WebServer.library(call: Call): Response {
    val query = call.param("q")?.let { TextLimits.clip(it, TextLimits.TITLE) }?.trim()
    val category = call.param("category")?.let { LibraryCategory.of(it) ?: throw ApiError(400, "field", "category must be all, favorites or recent.") } ?: LibraryCategory.ALL
    val offset = call.param("offset")?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: throw ApiError(400, "field", "offset must be 0 or more.") } ?: 0
    val limit = call.param("limit")?.let { it.toIntOrNull()?.takeIf { n -> n in 1..WebLimits.PAGE_MAX } ?: throw ApiError(400, "field", "limit must be from 1 to ${WebLimits.PAGE_MAX}.") } ?: DEFAULT_PAGE
    return json(WebApi.page(backend.library(query, category, offset, limit, scope(call))))
}

/**
 * `POST /api/login {pin}`: a new session on this way in, its cookie the answer's one `Set-Cookie`. A try is weighed by its
 * address and the gate for everyone; but a browser whose cookie marks a known device here (v1.24 — M56: signed in on this
 * transport within the year, [Sessions.knownDevice]) is weighed under its own key, `device:<digest>`, outside the gate for
 * everyone, so whoever holds that gate shut (the relay's) keeps no such browser out. Its fifth wrong PIN in a row (the
 * key's own lock, [LoginGuard.THRESHOLD]) forgets the device, and its next tries meet the gates as anyone's; a right PIN
 * replaces its session with the new one.
 */
private suspend fun WebServer.login(call: Call): Response {
    val body = call.body()
    WebApi.onlyKeys(body, setOf("pin"))
    val pin = WebApi.string(body, "pin", MAX_PIN_TEXT)
    val transport = call.edge.transport
    val device = call.token?.takeIf { sessions.knownDevice(it, transport) }
    val key = device?.let { DEVICE_KEY + Sessions.digest(it) } ?: call.address
    val shared = device == null
    val wait = guard.waitMs(key, shared)
    if (wait > 0) return waitResponse(wait)
    val hash = backend.pinHash() ?: return refuse(403, "no-pin", "Set a PIN on the tablet first.")
    // Checked and counted as one step, one try at a time (LoginGuard.attempt): tries sent at once can't all slip past the wait.
    return when (val attempt = guard.attempt(key, shared) { hash.matches(pin) }) {
        is LoginGuard.Attempt.Wait -> waitResponse(attempt.ms)
        is LoginGuard.Attempt.Wrong -> {
            if (device != null && attempt.waitMs > 0) sessions.close(device)   // the device's fifth wrong PIN in a row
            json(WebApi.error("wrong", "That PIN isn't right.").put("retryAfter", seconds(attempt.waitMs)), Response.Status.UNAUTHORIZED)
        }
        LoginGuard.Attempt.Right -> {
            device?.let(sessions::close)   // the browser's new session takes its place
            noContent().also { it.addHeader("Set-Cookie", WebCookies.session(sessions.open(transport), call.edge.cookiePath, call.edge.secure)) }
        }
    }
}

/**
 * `POST /api/confirm {pin}` (v1.24 — M56, "Confirm it's you"): the tablet's PIN again, from a signed-in session, before
 * the microphone or an update starts ([needsConfirm]). Weighed one try at a time under the session's own key,
 * `confirm:<digest>`, outside the gate for everyone (the session is the gate here). Right: 204, and the session may start
 * them for [Sessions.CONFIRM_MS]. Wrong: 403 `{error: "wrong", retryAfter}`, not 401 (which sends the panel back to its
 * sign-in); the third wrong PIN in a row ends the session: 401, its cookie cleared.
 */
private suspend fun WebServer.confirm(call: Call): Response {
    val body = call.body()
    WebApi.onlyKeys(body, setOf("pin"))
    val pin = WebApi.string(body, "pin", MAX_PIN_TEXT)
    val token = call.token ?: return refuse(401, "session", "Enter the PIN first.")
    val hash = backend.pinHash() ?: return refuse(403, "no-pin", "Set a PIN on the tablet first.")
    return when (val attempt = guard.attempt(CONFIRM_KEY + Sessions.digest(token), shared = false) { hash.matches(pin) }) {
        is LoginGuard.Attempt.Wait -> waitResponse(attempt.ms)
        is LoginGuard.Attempt.Wrong -> if (sessions.wrongConfirm(token)) {
            refuse(401, "session", "That PIN wasn't right three times. Enter it again to sign in.")
                .also { it.addHeader("Set-Cookie", WebCookies.endSession(call.edge.cookiePath, call.edge.secure)) }
        } else {
            json(WebApi.error("wrong", "That PIN isn't right.").put("retryAfter", seconds(attempt.waitMs)), Response.Status.FORBIDDEN)
        }
        LoginGuard.Attempt.Right -> {
            sessions.confirm(token)
            noContent()
        }
    }
}

/**
 * 403 `{error: "confirm"}` unless [call]'s session confirmed it's you, or signed in, within the last five minutes (v1.24 —
 * M56): Listen, Test every key and Install the update. The panel then asks for the PIN (`POST /api/confirm`) and sends the
 * request once more. Null when it may go on.
 */
private fun WebServer.needsConfirm(call: Call): Response? =
    if (sessions.confirmed(call.token)) null else refuse(403, "confirm", "Confirm it's you: enter the tablet's PIN.")

private suspend fun WebServer.guestRequest(call: Call): Response {
    val body = call.body()
    WebApi.onlyKeys(body, setOf("pieceId"))
    val pieceId = WebApi.id(body, "pieceId")
    val guests = backend.guestSettings()
    if (!guests.open) return refuse(403, "closed", "Requests are closed right now.")
    // One piece looked up (v1.14 — M37), never the whole catalogue built again for each request.
    val piece = backend.offered(pieceId) ?: return refuse(400, "not-offered", "That piece isn't on the list.")
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
    cookie?.let { response.addHeader("Set-Cookie", WebCookies.guest(it, call.edge.cookiePath, call.edge.secure)) }
    return response
}

/**
 * `PUT /api/upload?name=<file>`: the file is the whole body. Its name must end .mid, .midi or .zip
 * (415), its length must be declared and not chunked (411), a MIDI file at most [MIDI_BYTES] and a
 * zip at most [ZIP_BYTES] (413), all before a byte is read, and one upload at a time (409). A MIDI
 * file is read to memory through the importer's own cap; a zip is streamed to `cacheDir/web` with
 * the free-space margin kept. Then the import starts in the app and the answer is 202.
 */
private suspend fun WebServer.upload(call: Call): Response {
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

/** `GET /api/studio/seed?piece=<id>` (v1.7 — M24): the seed and its key and tempo for the compose form (no `piece`: the default one); 404 when there is none. */
private suspend fun WebServer.studioSeed(call: Call): Response {
    val piece = call.param("piece")?.let { it.toLongOrNull()?.takeIf { id -> id > 0 } ?: throw ApiError(400, "field", "piece must be a piece's id.") }
    val seed = backend.composeSeed(piece) ?: return notFound()
    return json(WebApi.seed(seed))
}

/**
 * `POST /api/studio/compose` (v1.7 — M24): the form's choices, every field checked
 * ([WebApi.composeOrder]); Studio able to run (409 "unavailable") with room for another job (409
 * "full", [STUDIO_JOBS_MAX]); the piece in the library (404); then the job is queued: 202 `{job}`.
 */
private suspend fun WebServer.studioCompose(call: Call): Response {
    val order = WebApi.composeOrder(call.body())
    val studio = backend.studio()
    if (!studio.available) return refuse(409, "unavailable", studio.reason ?: "Studio isn't available on this device.")
    studioFull(studio)?.let { return it }
    return when (val outcome = backend.compose(order)) {
        is StudioCompose.Queued -> json(JSONObject().put("job", outcome.jobId), Response.Status.ACCEPTED)
        is StudioCompose.Refused -> refuse(409, "unavailable", outcome.reason)
        StudioCompose.NoSuchPiece -> notFound()
    }
}

/**
 * `PUT /api/studio/audio?name=<file>` (v1.7 — M23): a recording for Studio to transcribe, the file as
 * the whole body, as [upload] takes a zip: its name must end as a recording's does (415), its length
 * declared and not chunked (411), at most [AUDIO_BYTES] (413), not empty (400), Studio able to run
 * here (409 "unavailable") with room for another job (409 "full", [STUDIO_JOBS_MAX]), all before a
 * byte is read, one upload at a time (409 "busy"); then it is streamed to `cacheDir/web/studio-….<ext>`
 * with the free-space margin kept (507), and the job is queued: 202 `{name, job}`.
 */
private suspend fun WebServer.studioUpload(call: Call): Response {
    val name = uploadName(call.param("name")) ?: throw ApiError(400, "name", "The file needs a name.")
    val extension = AUDIO_EXTENSIONS.firstOrNull { name.lowercase(Locale.ROOT).endsWith(".$it") }
        ?: throw ApiError(415, "type", "Only recordings can go to Studio: .wav, .mp3, .m4a, .flac, .ogg and the like.")
    val headers = call.session.headers
    if (headers.containsKey(TRANSFER_ENCODING)) throw ApiError(411, "length", "The upload must say how long it is.")
    val length = contentLength(headers) ?: throw ApiError(411, "length", "The upload must say how long it is.")
    if (length < 0) throw ApiError(400, "length", "The upload's length is not a length.")
    if (length > AUDIO_BYTES) throw ApiError(413, "too-large", "A recording can be 200 MB at most.")
    if (length == 0L) throw ApiError(400, "empty", "The file is empty.")
    val studio = backend.studio()
    if (!studio.available) return refuse(409, "unavailable", studio.reason ?: "Studio isn't available on this device.")
    studioFull(studio)?.let { return it }
    if (!uploading.tryLock()) throw ApiError(409, "busy", "Another file is being added. Try again in a moment.")
    val file = try {
        call.connection?.lift()
        save(LimitedInputStream(call.session.inputStream, length), length, STUDIO_PREFIX, ".$extension")
    } finally {
        uploading.unlock()
    }
    return when (val outcome = backend.transcribeUpload(name, file)) {
        is StudioUpload.Queued -> json(JSONObject().put("name", name).put("job", outcome.jobId), Response.Status.ACCEPTED)
        is StudioUpload.Refused -> refuse(409, "unavailable", outcome.reason)
    }
}

/**
 * 409 "full" when Studio already has [STUDIO_JOBS_MAX] jobs to do (waiting or running), before a byte of a
 * recording is read (audit delta 2): the panel could queue without end, each recording kept in the tablet's
 * storage until its turn and each composition adding a piece. Null when there is room.
 */
private fun WebServer.studioFull(studio: WebStudio): Response? {
    val toDo = studio.jobs.count { it.state == "queued" || it.state == "running" }
    return if (toDo < STUDIO_JOBS_MAX) null else refuse(409, "full", "Studio has $STUDIO_JOBS_MAX jobs to do already. Try again when one has finished.")
}

/** A zip upload, saved as [save] does, `upload-….zip`. */
private fun WebServer.saveZip(input: InputStream, length: Long): File = save(input, length, UPLOAD_PREFIX, ".zip")

/**
 * An upload's bytes into `cacheDir/web/<prefix>….<suffix>`, all [length] of them, with the cache's
 * free-space margin kept; deleted on any failure. The margin is a floor under a file of at
 * most 64 MB (a zip) or 200 MB (a recording), as the importer's own zip copy keeps it, not an
 * allocation: `usableSpace` is the question to ask.
 */
@SuppressLint("UsableSpace")
private fun WebServer.save(input: InputStream, length: Long, prefix: String, suffix: String): File {
    val dir = backend.uploadDir
    if (!dir.isDirectory) dir.mkdirs()
    if (dir.usableSpace < length + ImportLimits.SPACE_MARGIN_BYTES) throw ApiError(507, "space", "The tablet hasn't the room for this file.")
    val file = File.createTempFile(prefix, suffix, dir)
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

// ---- The System page (v1.18 — M46) ---------------------------------------------------------

/**
 * `POST /api/system/tool` `{name}`: `covers`, `reconnect` or `update` (409 busy while a firmware update runs; `update` only
 * once the session confirmed it's you, v1.24 — M56); any other name 400.
 */
private suspend fun WebServer.systemTool(call: Call): Response {
    val body = call.body()
    WebApi.onlyKeys(body, setOf("name"))
    val tool = SystemTool.of(WebApi.string(body, "name", 16)) ?: throw ApiError(400, "field", "The tool must be covers, reconnect or update.")
    if (tool == SystemTool.UPDATE) needsConfirm(call)?.let { return it }
    return when (backend.systemTool(tool)) {
        SystemToolResult.DONE -> noContent()
        SystemToolResult.BUSY -> refuse(409, "busy", "The piano is being updated. Try again once it has finished.")
    }
}

/** `GET /api/system/diagnostics`: Share diagnostics' zip, to be saved as [DIAGNOSTICS_FILE], never stored by the browser. */
private suspend fun WebServer.diagnosticsZip(): Response {
    val bytes = backend.diagnostics() ?: return refuse(503, "diagnostics", "The diagnostics couldn't be gathered. Try again.")
    return bytesResponse(Response.Status.OK, ZIP, bytes).also {
        it.addHeader("Cache-Control", "no-store")
        it.addHeader("Content-Disposition", "attachment; filename=\"$DIAGNOSTICS_FILE\"")
    }
}

// ---- The cover picker (v1.18 — M48) ---------------------------------------------------------

/**
 * `POST /api/covers/search` `{text}`: the text trimmed, 2 to 80 characters with no control character (400 `field`
 * otherwise, before anything is asked); 200 with the covers found ([WebApi.coverResults]); 429 `wait` within the
 * picker's 4 s, with `retryAfterMs`; 503 `busy` while Apple's hour-long stop is on, with when it lifts; 503
 * `unreachable` when Apple couldn't be reached.
 */
private suspend fun WebServer.coverSearch(call: Call): Response {
    val body = call.body()
    WebApi.onlyKeys(body, setOf("text"))
    val term = CoverPicker.term(WebApi.string(body, "text", TextLimits.TITLE))
        ?: throw ApiError(400, "field", "A search is ${CoverPicker.MIN_TEXT} to ${CoverPicker.MAX_TEXT} characters.")
    return when (val answer = backend.coverSearch(term)) {
        is CoverSearch.Found -> json(WebApi.coverResults(answer))
        is CoverSearch.Wait -> {
            val secs = seconds(answer.retryAfterMs)
            coverAnswer(429, WebApi.error("wait", "Try again in $secs s.").put("retryAfter", secs).put("retryAfterMs", answer.retryAfterMs))
                .also { it.addHeader("Retry-After", secs.toString()) }
        }
        is CoverSearch.Busy -> appleBusy(answer.retryAfterMs)
        CoverSearch.Unreachable -> refuse(503, "unreachable", "Apple's catalogue can't be reached. Try again.")
        CoverSearch.BadText -> refuse(400, "field", "A search is ${CoverPicker.MIN_TEXT} to ${CoverPicker.MAX_TEXT} characters.")
    }
}

/**
 * `POST /api/covers/choose` `{pieceId, searchId, index}`: 204 once the cover is the piece's own; 404 `not-found` for a
 * piece, a search or an index that is not there; 409 `made-here`; 503 `busy` or `unreachable` when it can't come down.
 */
private suspend fun WebServer.coverChoose(call: Call): Response {
    val body = call.body()
    WebApi.onlyKeys(body, setOf("pieceId", "searchId", "index"))
    val pieceId = WebApi.id(body, "pieceId")
    val searchId = WebApi.string(body, "searchId", SEARCH_ID_MAX)
    val index = WebApi.whole(body, "index")
    if (index !in 0 until CoverPicker.MAX_PICKS) return notFound()
    return coverChanged(backend.coverChoose(pieceId, searchId, index.toInt()))
}

/** `POST /api/covers/remove` `{pieceId}`: 204 once the piece's own cover is gone; 404 `not-found`; 409 `made-here`. */
private suspend fun WebServer.coverRemove(call: Call): Response {
    val body = call.body()
    WebApi.onlyKeys(body, setOf("pieceId"))
    return coverChanged(backend.coverRemove(WebApi.id(body, "pieceId")))
}

private fun WebServer.coverChanged(change: CoverChange): Response = when (change) {
    CoverChange.Done -> noContent()
    CoverChange.NotFound -> notFound()
    CoverChange.MadeHere -> refuse(409, "made-here", "Studio's pieces and the recordings keep the cover drawn from their music.")
    is CoverChange.Busy -> appleBusy(change.retryAfterMs)
    CoverChange.Unreachable -> refuse(503, "unreachable", "The cover couldn't be downloaded. Try again.")
    CoverChange.Failed -> refuse(500, "cover", "The tablet couldn't keep that cover.")
}

/** 503 `busy`: Apple's hour-long stop, lifting in `retryAfterMs` (and `Retry-After`), at `blockedUntil` (epoch ms, the tablet's clock). */
private fun WebServer.appleBusy(ms: Long): Response {
    val body = WebApi.error("busy", "Apple asked to slow down. Try again later.").put("retryAfterMs", ms).put("blockedUntil", System.currentTimeMillis() + ms)
    return coverAnswer(503, body).also { it.addHeader("Retry-After", seconds(ms).toString()) }
}

/** A JSON answer with any [status], never stored, as [refuse]'s are. */
private fun WebServer.coverAnswer(status: Int, body: JSONObject): Response =
    bytesResponse(statusOf(status), JSON, body.toString().toByteArray(Charsets.UTF_8)).also { it.addHeader("Cache-Control", "no-store") }

// ---- Quiet times (v1.20 — M54) -------------------------------------------------------------------------------------

/**
 * 409 `quiet` while a quiet time holds the piano and nobody chose Play anyway ("Quiet until 9:30. Use Play anyway.",
 * the time on the tablet's clock); null when a route that would play may.
 */
private suspend fun WebServer.quietRefusal(): Response? {
    val quiet = backend.quietNow()
    val until = quiet.until?.takeIf { quiet.holds } ?: return null
    val zone = java.time.ZoneId.systemDefault()
    val now = QuietCopy.at(System.currentTimeMillis(), zone)
    return refuse(409, "quiet", QuietCopy.refusal(QuietCopy.until(QuietCopy.at(until, zone), now)))
}

/**
 * Why a route that would start a piece is refused now: the safety check's lock, or a check due while the app is away
 * (v1.26 — M61: 409 `safety`, the tablet's words; the panel can't start a check, the microphone needs the tablet in
 * front), then a quiet time (409 `quiet`); null when the start may go.
 */
private suspend fun WebServer.startRefusal(): Response? {
    backend.safetyRefusal()?.let { return refuse(409, "safety", it) }
    return quietRefusal()
}

/** Whether [action] would start a piece: Play (a toggle while nothing plays, or Resume), Next, Previous. */
private suspend fun WebServer.startsPlaying(action: Transport): Boolean = when (action) {
    Transport.RESUME, Transport.NEXT, Transport.PREVIOUS -> true
    Transport.TOGGLE -> backend.state().player.status != PlaybackStatus.Playing
    Transport.PAUSE, Transport.STOP -> false
}

// ---- Listening and the key test (v1.23 — M55) ----------------------------------------------------------------------

/**
 * A start asked by [call]'s session (its token, which the microphone's frames are sent to and nothing else): 204 when
 * it began, 409 `{error, message}` with the tablet's reason when refused.
 */
private suspend fun WebServer.heard(call: Call, start: suspend (String) -> dev.stevenjin.stevenpiano.audio.ListenAnswer): Response {
    val token = call.token ?: return refuse(401, "session", "Enter the PIN first.")
    return when (val answer = start(token)) {
        dev.stevenjin.stevenpiano.audio.ListenAnswer.Started -> noContent()
        is dev.stevenjin.stevenpiano.audio.ListenAnswer.Refused -> refuse(409, answer.code, answer.message)
    }
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

/** The piano's actions the panel may send: All keys off, Save now, Read status (no strike or LED tests from afar). */
private val PANEL_ACTIONS = mapOf("off" to PianoAction.AllKeysOff, "save" to PianoAction.Save, "status" to PianoAction.Status)

/** The login guard's keys (v1.24 — M56) for a known device's sign-in and a session's "Confirm it's you", each before a token's digest. */
private const val DEVICE_KEY = "device:"
private const val CONFIRM_KEY = "confirm:"

/** A whole number as a query may give it: digits only, at most ten. */
private val REV = Regex("\\d{1,10}")
private const val UPLOAD_PREFIX = "upload-"
private const val STUDIO_PREFIX = "studio-"
private const val MAX_PIN_TEXT = 16
private const val DEFAULT_PAGE = 50

/** Upload caps: a MIDI file as the importer's own, a zip well under the importer's 512 MB, a recording as Studio's own. */
const val MIDI_BYTES = 8L * 1024 * 1024
const val ZIP_BYTES = 64L * 1024 * 1024
const val AUDIO_BYTES = 200L * 1024 * 1024

/**
 * The most Studio jobs the panel lets wait or run at once (audit delta 2): a person's batch of recordings
 * fits, a script's endless stream doesn't. The tablet's own Studio page and + sheet are not held to it.
 */
const val STUDIO_JOBS_MAX = 8

/** What a recording sent to Studio may be called: the containers Android's decoders read (a video's sound included). */
val AUDIO_EXTENSIONS = listOf("wav", "wave", "mp3", "m4a", "mp4", "aac", "flac", "ogg", "oga", "opus", "webm", "3gp", "amr")

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

/** The diagnostics zip's type and the name a browser saves it as (v1.18 — M46). */
private const val ZIP = "application/zip"
private const val DIAGNOSTICS_FILE = "steven-piano-diagnostics.zip"

/** A cover search's id as a body may give it, at most (v1.18 — M48: the picker's are 16 characters). */
private const val SEARCH_ID_MAX = 64
