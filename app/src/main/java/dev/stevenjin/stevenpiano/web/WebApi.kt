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
import dev.stevenjin.stevenpiano.studio.SeedChoice
import dev.stevenjin.stevenpiano.studio.compose.ComposeRequest
import dev.stevenjin.stevenpiano.studio.compose.Mood
import dev.stevenjin.stevenpiano.studio.compose.MusicKey
import dev.stevenjin.stevenpiano.studio.compose.PromptBuilder
import dev.stevenjin.stevenpiano.data.TextLimits
import dev.stevenjin.stevenpiano.data.db.ScheduleKind
import dev.stevenjin.stevenpiano.data.imports.ImportProgress
import dev.stevenjin.stevenpiano.piano.PianoPage
import dev.stevenjin.stevenpiano.piano.PianoSetting
import dev.stevenjin.stevenpiano.piano.PianoSettings
import dev.stevenjin.stevenpiano.piano.SettingKind
import dev.stevenjin.stevenpiano.player.DynamicRange
import dev.stevenjin.stevenpiano.player.ExpressionLevel
import dev.stevenjin.stevenpiano.player.Performance
import dev.stevenjin.stevenpiano.player.PlaybackLimits
import dev.stevenjin.stevenpiano.player.PlaybackStatus
import dev.stevenjin.stevenpiano.player.RepeatMode
import dev.stevenjin.stevenpiano.schedule.ScheduleDraft
import dev.stevenjin.stevenpiano.settings.NoteDisplay
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A request the panel's API refuses: the HTTP [status], a short [code] for the page, and a plain [message]. */
class ApiError(val status: Int, val code: String, override val message: String) : Exception(message)

/**
 * The panel's JSON, both ways (BUILD_SPEC.md › v1.5.1 — M18 › JSON shapes). Bodies are read only
 * when their `Content-Length` is given and at most [MAX_BODY] bytes, decoded as strict UTF-8,
 * refused when nested deeper than [MAX_DEPTH] before `org.json` sees them, and must be one object.
 * Fields are read strictly: ids are whole numbers that fit a Long, flags are booleans, strings are
 * cut to [TextLimits] and choices are one of a few names. What goes out is built here too.
 */
object WebApi {
    const val MAX_BODY = 64 * 1024
    const val MAX_DEPTH = 4

    /** A roll card's art version (v1.17 — M45): drawn from the piece's own notes, it never changes. */
    const val ROLL_ART_VERSION = 1L

    // ---- In ------------------------------------------------------------------------------

    /**
     * The body of a request that declared [contentLength] bytes (null: none declared), read from
     * [input] to exactly that length and never past [MAX_BODY]; then parsed ([parse]).
     */
    fun readObject(input: InputStream, contentLength: Long?, contentType: String?, chunked: Boolean): JSONObject {
        if (chunked || contentLength == null) throw ApiError(411, "length", "The request must say how long its body is.")
        if (contentLength > MAX_BODY) throw ApiError(413, "too-large", "The request is larger than 64 KB.")
        if (contentLength < 0) throw ApiError(400, "length", "The request's length is not a length.")
        if (!isJson(contentType)) throw ApiError(415, "type", "The request must be JSON.")
        val bytes = readExactly(input, contentLength.toInt())
        return parse(bytes)
    }

    /** Whether [contentType] is JSON (`application/json`, with or without a charset, which must then be UTF-8). */
    fun isJson(contentType: String?): Boolean {
        val parts = contentType?.split(';')?.map { it.trim().lowercase() } ?: return false
        if (parts.first() != "application/json") return false
        val charset = parts.drop(1).firstOrNull { it.startsWith("charset=") }?.removePrefix("charset=")?.trim('"')
        return charset == null || charset == "utf-8"
    }

    /** [bytes] as one JSON object: strict UTF-8, nested at most [MAX_DEPTH] deep. */
    fun parse(bytes: ByteArray): JSONObject {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw ApiError(400, "encoding", "The request is not UTF-8.")
        }
        if (depthOf(text) > MAX_DEPTH) throw ApiError(400, "depth", "The request is nested too deeply.")
        val value = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw ApiError(400, "json", "The request is not a JSON object.")
        } catch (e: StackOverflowError) {
            throw ApiError(400, "depth", "The request is nested too deeply.")
        }
        return value
    }

    /** How deeply [text]'s objects and arrays nest, counting outside strings only. */
    fun depthOf(text: String): Int {
        var depth = 0
        var deepest = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > deepest) deepest = depth
                }
                '}', ']' -> depth--
            }
        }
        return deepest
    }

    /** Exactly [length] bytes from [input], or an [ApiError] when it ends first. */
    fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length)
        val buffer = ByteArray(minOf(length, 16 * 1024).coerceAtLeast(1))
        var left = length
        try {
            while (left > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size, left))
                if (n < 0) break
                out.write(buffer, 0, n)
                left -= n
            }
        } catch (e: IOException) {
            throw ApiError(400, "short", "The request ended early.")
        }
        if (left > 0) throw ApiError(400, "short", "The request ended early.")
        return out.toByteArray()
    }

    /** A whole number that fits a Long, and nothing else (no decimals, no strings); null when absent. */
    fun wholeOrNull(json: JSONObject, name: String): Long? {
        if (!json.has(name) || json.isNull(name)) return null
        return whole(json.get(name)) ?: throw ApiError(400, "field", "$name must be a whole number.")
    }

    fun whole(json: JSONObject, name: String): Long = wholeOrNull(json, name) ?: throw ApiError(400, "field", "$name is missing.")

    /** An id: a whole number above 0. */
    fun id(json: JSONObject, name: String): Long = whole(json, name).also { if (it <= 0) throw ApiError(400, "field", "$name must be above 0.") }

    fun int(json: JSONObject, name: String, range: IntRange): Int {
        val value = whole(json, name)
        if (value !in range.first.toLong()..range.last.toLong()) throw ApiError(400, "range", "$name must be from ${range.first} to ${range.last}.")
        return value.toInt()
    }

    fun boolOrNull(json: JSONObject, name: String): Boolean? {
        if (!json.has(name) || json.isNull(name)) return null
        return json.get(name) as? Boolean ?: throw ApiError(400, "field", "$name must be true or false.")
    }

    fun bool(json: JSONObject, name: String): Boolean = boolOrNull(json, name) ?: throw ApiError(400, "field", "$name is missing.")

    /** A string, cut to [max] characters as the library cuts what comes in. */
    fun stringOrNull(json: JSONObject, name: String, max: Int): String? {
        if (!json.has(name) || json.isNull(name)) return null
        val value = json.get(name) as? String ?: throw ApiError(400, "field", "$name must be text.")
        return TextLimits.clip(value, max)
    }

    fun string(json: JSONObject, name: String, max: Int): String = stringOrNull(json, name, max) ?: throw ApiError(400, "field", "$name is missing.")

    /** A list of ids (whole numbers above 0), at most [max] of them; null when absent. */
    fun idsOrNull(json: JSONObject, name: String, max: Int = WebLimits.IDS_MAX): List<Long>? {
        if (!json.has(name) || json.isNull(name)) return null
        val array = json.get(name) as? JSONArray ?: throw ApiError(400, "field", "$name must be a list of ids.")
        if (array.length() > max) throw ApiError(413, "too-many", "$name holds more than $max ids.")
        return (0 until array.length()).map { i ->
            val id = whole(array.get(i)) ?: throw ApiError(400, "field", "$name must be a list of ids.")
            if (id <= 0) throw ApiError(400, "field", "$name must be a list of ids.")
            id
        }
    }

    /** Only the keys [allowed]: anything else is a mistake worth saying, never silently ignored. */
    fun onlyKeys(json: JSONObject, allowed: Set<String>) {
        val unknown = json.keys().asSequence().firstOrNull { it !in allowed } ?: return
        throw ApiError(400, "field", "Unknown field ${TextLimits.clip(unknown, 40)}.")
    }

    private fun whole(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is BigInteger -> if (value.bitLength() < Long.SIZE_BITS) value.toLong() else null
        else -> null
    }

    /** A finite number of any form (the sliders' values), or null. */
    private fun number(value: Any?): Double? = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

    /** The repeat mode a name gives ("off", "all", "one"). */
    fun repeatOf(name: String?): RepeatMode = RepeatMode.entries.firstOrNull { it.name.lowercase() == name }
        ?: throw ApiError(400, "field", "mode must be off, all or one.")

    /** A change to Up next from `{action, ids?, uid?, toIndex?}`. */
    fun queueCommand(json: JSONObject): QueueCommand {
        onlyKeys(json, setOf("action", "ids", "uid", "toIndex"))
        return when (val action = string(json, "action", 16)) {
            "playNext" -> QueueCommand.PlayNext(idsOrNull(json, "ids")?.takeIf { it.isNotEmpty() } ?: throw ApiError(400, "field", "ids is missing."))
            "add" -> QueueCommand.Add(idsOrNull(json, "ids")?.takeIf { it.isNotEmpty() } ?: throw ApiError(400, "field", "ids is missing."))
            "remove" -> QueueCommand.Remove(id(json, "uid"))
            "move" -> QueueCommand.Move(id(json, "uid"), int(json, "toIndex", 0..WebLimits.IDS_MAX * 10))
            "clear" -> QueueCommand.Clear
            "skip" -> QueueCommand.Skip(id(json, "uid"))
            else -> throw ApiError(400, "field", "Unknown queue action ${TextLimits.clip(action, 16)}.")
        }
    }

    /** `PUT /api/settings`: only the preferences the panel may change, each checked here or clamped by the settings. */
    fun settingsChange(json: JSONObject): SettingsChange {
        onlyKeys(json, SETTINGS_KEYS)
        val host = stringOrNull(json, "webHostName", MAX_HOST_NAME + 1)?.trim()?.lowercase()
        if (host != null && host.isNotEmpty() && (!HOST_NAME.matches(host) || ADDRESS_LIKE.matches(host))) throw ApiError(400, "field", "webHostName must be a host name.")
        val change = SettingsChange(
            preRollMs = if (json.has("preRollMs")) int(json, "preRollMs", PlaybackLimits.PreRollMs) else null,
            defaultTempoPct = if (json.has("defaultTempoPct")) int(json, "defaultTempoPct", PlaybackLimits.TempoPct) else null,
            transpose = if (json.has("transpose")) int(json, "transpose", PlaybackLimits.Transpose) else null,
            velocityPct = if (json.has("velocityPct")) int(json, "velocityPct", PlaybackLimits.VelocityPct) else null,
            foldOutOfRange = boolOrNull(json, "foldOutOfRange"),
            skipDrumChannel = boolOrNull(json, "skipDrumChannel"),
            dynamicRange = stringOrNull(json, "dynamicRange", 16)?.let {
                DYNAMIC_RANGES[it] ?: throw ApiError(400, "field", "dynamicRange must be narrow, natural or wide.")
            },
            velocityFloor = if (json.has("velocityFloor")) int(json, "velocityFloor", PlaybackLimits.VelocityFloor) else null,
            expression = stringOrNull(json, "expression", 16)?.let {
                EXPRESSIONS[it] ?: throw ApiError(400, "field", "expression must be off, light or full.")
            },
            restrikeMs = if (json.has("restrikeMs")) restrikeMs(json) else null,
            webGuests = boolOrNull(json, "webGuests"),
            webApproveFirst = boolOrNull(json, "webApproveFirst"),
            webHostName = host,
            tabletVolume = if (json.has("tabletVolume")) int(json, "tabletVolume", 0..100) else null,
            noteDisplay = stringOrNull(json, "noteDisplay", 16)?.let { NOTE_DISPLAYS[it] ?: throw ApiError(400, "field", "noteDisplay must be paperRoll or falling.") },
            fingering = boolOrNull(json, "fingering"),
            chordNames = boolOrNull(json, "chordNames"),
            handColours = boolOrNull(json, "handColours"),
            albumBackdrop = boolOrNull(json, "albumBackdrop"),
        )
        if (change.isEmpty) throw ApiError(400, "field", "Nothing to change.")
        return change
    }

    /** The re-strike time as the Playback page offers it: 0 (Auto), or 60 to 250 ms in tens. */
    private fun restrikeMs(json: JSONObject): Int {
        val ms = int(json, "restrikeMs", Performance.AUTO..PlaybackLimits.RestrikeMs.last)
        if (ms != Performance.AUTO && (ms !in PlaybackLimits.RestrikeMs || ms % PlaybackLimits.RESTRIKE_STEP_MS != 0)) {
            throw ApiError(400, "range", "restrikeMs must be 0 (Auto), or 60 to 250 in tens.")
        }
        return ms
    }

    /**
     * `POST /api/schedules` and `PUT /api/schedules/{id}`: a schedule's fields, `{days, startMinute,
     * kind, target, endMinute?, volumePct?, enabled?}`, read strictly and checked as the app's editor
     * checks them ([ScheduleRules]): days a bitmask from 1 to 127 (Monday 1 … Sunday 64), minutes after
     * midnight, kind playlist · channel · piece, the target a channel's key or an id written as text,
     * endMinute null (or absent) to play until the end, volumePct null (or absent) for none, enabled
     * true unless it says false. [id] is the schedule edited, 0 for a new one.
     */
    fun scheduleDraft(json: JSONObject, id: Long = 0): ScheduleDraft {
        onlyKeys(json, SCHEDULE_KEYS)
        val kind = ScheduleKind.entries.firstOrNull { it.name.lowercase() == string(json, "kind", 16) }
            ?: throw ApiError(400, "field", "kind must be playlist, channel or piece.")
        // Whole numbers here; whether they make a schedule is the rules' to say, in the editor's words.
        val draft = ScheduleDraft(
            id = id,
            days = ruled(whole(json, "days")),
            startMinute = ruled(whole(json, "startMinute")),
            kind = kind,
            target = string(json, "target", MAX_TARGET).trim(),
            endMinute = wholeOrNull(json, "endMinute")?.let(::ruled),
            volumePct = wholeOrNull(json, "volumePct")?.let(::ruled),
            enabled = boolOrNull(json, "enabled") ?: true,
        )
        draft.problem?.let { throw ApiError(400, "schedule", it) }
        return draft
    }

    /** A whole number as an Int for the rules, which refuse anything outside their ranges; past an Int's own, the nearest end. */
    private fun ruled(value: Long): Int = value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    /**
     * Setting [setting]'s new value from the panel as the piano takes it (the wire form the app's own
     * rows send), or an [ApiError]: read-only settings are never written (the key-force pair), and a
     * value outside the table's range is refused, never clamped.
     */
    fun pianoWire(setting: PianoSetting, value: Any?): String {
        if (setting.readOnly) throw ApiError(403, "read-only", "${setting.label} is set at the piano's USB console.")
        fun outOfRange(): Nothing = throw ApiError(400, "range", "${setting.label} is out of range.")
        return when (val kind = setting.kind) {
            SettingKind.Switch -> when {
                value is Boolean -> PianoSettings.wire(value)
                whole(value) == 0L -> PianoSettings.wire(false)
                whole(value) == 1L -> PianoSettings.wire(true)
                else -> throw ApiError(400, "field", "value must be true or false.")
            }
            is SettingKind.Stepper -> {
                val v = whole(value) ?: throw ApiError(400, "field", "value must be a whole number.")
                if (v !in kind.min.toLong()..kind.max.toLong()) outOfRange()
                val on = kind.lowestOn
                if (on != null && v in 1 until on) outOfRange()
                PianoSettings.wire(v.toInt())
            }
            is SettingKind.Slider -> {
                val v = number(value) ?: throw ApiError(400, "field", "value must be a number.")
                if (v < kind.min - SLIDER_SLACK || v > kind.max + SLIDER_SLACK) outOfRange()
                val snapped = kind.snap(v.toFloat())
                if (kind.decimals > 0) PianoSettings.wire(snapped) else PianoSettings.wire(Math.round(snapped))
            }
            is SettingKind.Choice -> {
                val v = whole(value) ?: throw ApiError(400, "field", "value must be a choice's number.")
                if (v !in kind.options.indices.first.toLong()..kind.options.indices.last.toLong()) outOfRange()
                PianoSettings.wire(v.toInt())
            }
        }
    }

    // ---- Out -----------------------------------------------------------------------------

    fun error(code: String, message: String): JSONObject = JSONObject().put("error", code).put("message", message)

    fun piece(p: WebPiece): JSONObject = JSONObject()
        .put("id", p.id)
        .put("title", p.title)
        .put("composer", p.composer)
        .put("composerShort", p.composerShort)
        .put("composerKey", p.composerKey)
        .put("durationMs", p.durationMs)
        .put("favorite", p.favorite)
        .put("art", if (p.cover) "cover" else if (p.portrait) "portrait" else "roll")
        .put("artVersion", if (p.cover || p.portrait) p.artVersion else ROLL_ART_VERSION)   // every kind's (v1.17 — M45)
        .apply { p.genre?.let { put("genre", it) } }   // v1.14 — M37: only when it has one

    fun pieces(list: List<WebPiece>): JSONArray = JSONArray().apply { list.forEach { put(piece(it)) } }

    fun page(page: WebPage): JSONObject = JSONObject().put("total", page.total).put("offset", page.offset).put("pieces", pieces(page.pieces))

    fun playlist(p: WebPlaylist): JSONObject = JSONObject()
        .put("id", p.id).put("name", p.name).put("pieceCount", p.pieceCount).put("durationMs", p.durationMs).put("builtIn", p.builtIn)

    fun composer(c: WebComposer): JSONObject = JSONObject()
        .put("key", c.key).put("name", c.name).put("pieceCount", c.pieceCount).put("portrait", c.portrait).put("artVersion", c.artVersion)

    fun channels(list: List<WebChannel>): JSONObject = JSONObject()
        .put("playing", list.firstOrNull { it.playing }?.key ?: JSONObject.NULL)
        .put(
            "channels",
            JSONArray().apply {
                list.forEach { c ->
                    put(
                        JSONObject()
                            .put("key", c.key).put("name", c.name).put("size", c.size).put("playable", c.playable)
                            .put("playing", c.playing).put("volume", c.volume)
                            .put("composers", JSONArray().apply { c.composers.forEach { put(JSONObject().put("key", it.key).put("name", it.name).put("portrait", it.portrait).put("artVersion", it.artVersion)) } })
                            .apply { c.genre?.let { put("genre", it) } },
                    )
                }
            },
        )

    fun requests(pending: List<GuestRequests.Request>, guests: GuestSettings): JSONObject = JSONObject()
        .put("guests", guests.open)
        .put("approveFirst", guests.approveFirst)
        .put(
            "pending",
            JSONArray().apply {
                pending.forEach { r -> put(JSONObject().put("id", r.id).put("pieceId", r.pieceId).put("title", r.title).put("composer", r.composer).put("at", r.at)) }
            },
        )

    fun catalogue(open: Boolean, lists: List<CatalogueList>): JSONObject = JSONObject()
        .put("open", open)
        .put(
            "lists",
            JSONArray().apply {
                // Guests see titles and composers only: no ids but the one they send back, no art, no lengths. A list
                // names its genre (v1.14 — M37), for the page's switch; its pieces don't.
                if (open) {
                    lists.forEach { l ->
                        put(
                            JSONObject().put("key", l.key).put("name", l.name)
                                .apply { l.genre?.let { put("genre", it) } }
                                .put("pieces", JSONArray().apply { l.pieces.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("composer", it.composerShort)) } }),
                        )
                    }
                }
            },
        )

    /** `/api/state` and the socket's state message ([type] "state"), with the requests waiting. */
    fun state(s: WebState, pending: Int, type: String? = null): JSONObject {
        val p = s.player
        val json = JSONObject()
        if (type != null) json.put("type", type)
        return json
            .put(
                "player",
                JSONObject()
                    .put("status", statusName(p.status))
                    .put("loading", p.loading)
                    .put("piece", p.piece?.let(::piece) ?: JSONObject.NULL)
                    .put("positionMs", p.positionMs)
                    .put("at", p.at)
                    .put("tempoPct", p.tempoPct)
                    .put("transpose", p.transpose)
                    .put("velocityPct", p.velocityPct)
                    .put("preRollMs", p.preRollMs)
                    .put("channel", p.channel?.let { JSONObject().put("key", it.key).put("name", it.name).put("volume", it.volume) } ?: JSONObject.NULL)
                    .put("tablet", JSONObject().put("mode", p.tablet.mode).put("volume", p.tablet.volume).put("active", p.tablet.active).put("installed", p.tablet.installed))
                    .put(
                        "queue",
                        JSONObject()
                            .put("ids", JSONArray(p.queue.ids))
                            .put("uids", JSONArray(p.queue.uids))
                            .put("index", p.queue.index)
                            .put("shuffle", p.queue.shuffle)
                            .put("repeat", p.queue.repeat.name.lowercase())
                            .put("items", JSONArray().apply { p.items.forEach { put(piece(it.piece).put("uid", it.uid).put("requested", it.requested)) } }),
                    )
                    .put("problem", p.problem ?: JSONObject.NULL)
                    .put("fold", p.fold)
                    .put("views", p.views?.let(::views) ?: JSONObject.NULL),
            )
            .put("link", JSONObject().put("state", s.link.state).put("name", s.link.name ?: JSONObject.NULL))
            .put("instruments", instruments(s.instruments))
            .put("piano", pianoState(s.piano))
            .put("import", import(s.import))
            .put("artwork", JSONObject().put("running", s.artwork.running).put("done", s.artwork.done).put("total", s.artwork.total))
            .put("requests", JSONObject().put("pending", pending).put("guests", s.guests.open).put("approveFirst", s.guests.approveFirst))
            .put(
                "web",
                JSONObject().put("address", s.web.panel ?: JSONObject.NULL).put("guestAddress", s.web.guest ?: JSONObject.NULL).put("guests", s.guests.open)
                    .put("cloud", s.web.cloud ?: JSONObject.NULL),
            )
            .put("monochrome", s.monochrome)
            .put("albumBackdrop", s.albumBackdrop)
            .put("schedule", JSONObject().put("next", s.schedule.next ?: JSONObject.NULL).put("revision", s.schedule.revision))
            .put("studio", studio(s.studio))
            .put("display", display(s.display))
    }

    /** The views of the piece playing (v1.13 — M32): `{rev, notes, hands, fingers, chords, score}`. */
    fun views(v: WebViews): JSONObject = JSONObject()
        .put("rev", v.rev).put("notes", v.notes).put("hands", v.hands).put("fingers", v.fingers).put("chords", v.chords).put("score", v.score)

    /** The display settings the View control mirrors (v1.13 — M32): `{noteDisplay, rollStyle, fingering, chordNames, handColours}`. */
    fun display(d: WebDisplay): JSONObject = JSONObject()
        .put("noteDisplay", d.noteDisplay).put("rollStyle", d.rollStyle).put("fingering", d.fingering).put("chordNames", d.chordNames).put("handColours", d.handColours)

    /**
     * What plays and what is played from (v1.11 — M29), read-only: `{instrument: {kind, name, state}, keyboard: {name,
     * transport, state} or null, live, recording}`. The panel shows it in two lines; nothing it sends changes it.
     */
    fun instruments(s: WebInstruments): JSONObject = JSONObject()
        .put("instrument", JSONObject().put("kind", s.instrument.kind).put("name", s.instrument.name).put("state", s.instrument.state))
        .put("keyboard", s.keyboard?.let { JSONObject().put("name", it.name).put("transport", it.transport).put("state", it.state) } ?: JSONObject.NULL)
        .put("live", s.live)
        .put("recording", s.recording)

    /**
     * Studio (v1.7 — M23): `{available, reason, models: [{name, title, sizeBytes, licence, installed, line, progress}], jobs:
     * [{id, kind, name, state, line, progress, title, step, steps, tokens, musicMs, targetMs, etaMs, notes, turn}]}` (the last
     * eight v1.12 — M30).
     */
    fun studio(s: WebStudio): JSONObject = JSONObject()
        .put("available", s.available)
        .put("reason", s.reason ?: JSONObject.NULL)
        .put(
            "models",
            JSONArray().apply {
                s.models.forEach { m ->
                    put(
                        JSONObject().put("name", m.name).put("title", m.title).put("sizeBytes", m.sizeBytes).put("licence", m.licence)
                            .put("installed", m.installed).put("line", m.line).put("progress", m.progress?.toDouble() ?: JSONObject.NULL),
                    )
                }
            },
        )
        .put(
            "jobs",
            JSONArray().apply {
                s.jobs.forEach { j ->
                    put(
                        JSONObject().put("id", j.id).put("kind", j.kind).put("name", j.name).put("state", j.state).put("line", j.line)
                            .put("progress", j.progress?.toDouble() ?: JSONObject.NULL).put("title", j.title ?: JSONObject.NULL)
                            .put("step", j.step).put("steps", JSONArray(j.steps)).put("tokens", j.tokens).put("musicMs", j.musicMs)
                            .put("targetMs", j.targetMs).put("etaMs", j.etaMs ?: JSONObject.NULL).put("notes", j.notes).put("turn", j.turn ?: JSONObject.NULL),
                    )
                }
            },
        )

    /**
     * A composition asked for from the panel (v1.7 — M24): `{pieceId, mood, key, bpm, minutes}`, each
     * field checked and nothing else taken. `pieceId` names a piece of the library (absent or null: the
     * default seed), `mood` is calm, bright, wild or melancholy, `key` is `{tonic: 0–11, minor}` (null:
     * the piece's own), `bpm` 40–200 (null: the piece's own), `minutes` 1–5. No text reaches the model.
     */
    fun composeOrder(json: JSONObject): ComposeOrder {
        onlyKeys(json, COMPOSE_KEYS)
        val pieceId = wholeOrNull(json, "pieceId")?.also { if (it <= 0) throw ApiError(400, "field", "pieceId must be above 0.") }
        val moodName = string(json, "mood", 16)
        val mood = Mood.entries.firstOrNull { it.name.lowercase() == moodName }
            ?: throw ApiError(400, "field", "mood must be calm, bright, wild or melancholy.")
        val key = if (!json.has("key") || json.isNull("key")) {
            null
        } else {
            val pair = json.get("key") as? JSONObject ?: throw ApiError(400, "field", "key must be {tonic, minor}.")
            onlyKeys(pair, KEY_KEYS)
            MusicKey(int(pair, "tonic", 0..11), bool(pair, "minor"))
        }
        val bpm = if (wholeOrNull(json, "bpm") == null) null else int(json, "bpm", PromptBuilder.MIN_BPM..PromptBuilder.MAX_BPM)
        val minutes = int(json, "minutes", PromptBuilder.MIN_MINUTES..PromptBuilder.MAX_MINUTES)
        return ComposeOrder(pieceId, ComposeRequest(mood, key, bpm, minutes))
    }

    /** A seed for the panel's compose form: `{pieceId, title, composer, key: {tonic, minor, label}, bpm}`. */
    fun seed(s: SeedChoice): JSONObject = JSONObject()
        .put("pieceId", s.pieceId)
        .put("title", s.title)
        .put("composer", s.composer ?: JSONObject.NULL)
        .put("key", JSONObject().put("tonic", s.facts.key.tonic).put("minor", s.facts.key.minor).put("label", s.facts.key.label))
        .put("bpm", s.facts.bpm)

    private val COMPOSE_KEYS = setOf("pieceId", "mood", "key", "bpm", "minutes")
    private val KEY_KEYS = setOf("tonic", "minor")

    /** A schedule: its fields as `POST` takes them, and (read-only) what its target is called and the tablet's two lines, `when` and `what`. */
    fun schedule(s: WebSchedule): JSONObject {
        val e = s.entry
        return JSONObject()
            .put("id", e.id)
            .put("days", e.days)
            .put("startMinute", e.startMinute)
            .put("kind", e.kind.name.lowercase())
            .put("target", e.target)
            .put("endMinute", e.endMinute ?: JSONObject.NULL)
            .put("volumePct", e.volumePct ?: JSONObject.NULL)
            .put("enabled", e.enabled)
            .put("name", s.name)
            .put("when", s.whenLine)
            .put("what", s.whatLine)
    }

    /** `GET /api/schedules`: the schedules by start time, the next start's line, the last one's outcome, and whether exact alarms are allowed. */
    fun schedules(s: WebSchedules): JSONObject = JSONObject()
        .put("schedules", JSONArray().apply { s.schedules.forEach { put(schedule(it)) } })
        .put("next", s.next ?: JSONObject.NULL)
        .put("last", s.last ?: JSONObject.NULL)
        .put("exactAlarms", s.exactAlarms)

    /**
     * The socket's once-a-second message while a piece plays, and (v1.13 — M32) at once whenever the position jumps
     * (play, pause, a seek, the tempo, a load), playing or not: where it is, when that was on the tablet's monotonic
     * clock ([at], ms), whether it runs and at what tempo (the page carries on from there).
     */
    fun progress(positionMs: Long, at: Long, playing: Boolean = true, tempoPct: Int = 100): JSONObject =
        JSONObject().put("type", "progress").put("positionMs", positionMs).put("at", at).put("playing", playing).put("tempoPct", tempoPct)

    /**
     * The import's progress, then its tally; and (v1.10.1 — M28, D7) the playlist the last finished import put its
     * pieces in, `{id, name}`, null when it put them in none (files picked one by one, nothing arrived) or one runs.
     */
    fun import(i: ImportProgress): JSONObject = JSONObject()
        .put("running", !i.finished)
        .put("done", i.done).put("total", i.total)
        .put("imported", i.imported).put("duplicates", i.duplicates).put("failed", i.failed)
        .put("current", i.current ?: JSONObject.NULL)
        .put("playlist", i.playlist?.takeIf { i.finished }?.let { JSONObject().put("id", it.id).put("name", it.name) } ?: JSONObject.NULL)

    fun pianoState(p: WebPianoState): JSONObject = JSONObject()
        .put("state", p.state)
        .put("values", JSONObject(p.values as Map<*, *>))
        .put("facts", JSONObject(p.facts as Map<*, *>))
        .put("lastError", p.lastError ?: JSONObject.NULL)
        .put("errorAbout", p.errorAbout ?: JSONObject.NULL)

    /** `/api/piano`: the state and report, the presets, and the table the page draws its controls from (Sound and touch · Lights and screen · Pedal). */
    fun piano(p: WebPiano): JSONObject = pianoState(p.state)
        .put("statusText", p.statusText ?: JSONObject.NULL)
        .put("statusReading", p.statusReading)
        .put("presets", JSONArray().apply { PianoSettings.presets.forEach { put(JSONObject().put("command", it.command).put("label", it.label)) } })
        .put("pages", JSONArray().apply { PANEL_PAGES.forEach { put(page(it)) } })

    private fun page(page: PianoPage): JSONObject = JSONObject()
        .put("key", page.name.lowercase())
        .put("title", page.title)   // the page's one name, as the app has it (v1.13)
        .put(
            "sections",
            JSONArray().apply {
                PianoSettings.sections(page).forEach { section ->
                    val settings = PianoSettings.inSection(section).filterNot { it.readOnly }
                    if (settings.isNotEmpty()) put(JSONObject().put("title", section.title ?: JSONObject.NULL).put("settings", JSONArray().apply { settings.forEach { put(setting(it)) } }))
                }
            },
        )

    private fun setting(s: PianoSetting): JSONObject {
        val kind = when (val k = s.kind) {
            SettingKind.Switch -> JSONObject().put("type", "switch")
            is SettingKind.Stepper -> JSONObject().put("type", "stepper").put("min", k.min).put("max", k.max).put("step", k.step).put("lowestOn", k.lowestOn ?: JSONObject.NULL)
            is SettingKind.Slider -> JSONObject().put("type", "slider").put("min", k.min.toDouble()).put("max", k.max.toDouble()).put("step", k.step.toDouble()).put("decimals", k.decimals)
            is SettingKind.Choice -> JSONObject().put("type", "choice").put("options", JSONArray(k.options))
        }
        return JSONObject()
            .put("name", s.name)
            .put("label", s.label)
            .put("kind", kind)
            .put("unit", s.unit)
            .put("zero", s.zero ?: JSONObject.NULL)
            .put("percentOf255", s.asPercentOf255)
            .put("note", s.note ?: JSONObject.NULL)
    }

    private fun statusName(status: PlaybackStatus): String = status.name.lowercase()

    /** The piano's pages the panel offers: its settings (Firmware and status stays on the tablet, but Read status, All keys off and Save now). */
    private val PANEL_PAGES = listOf(PianoPage.Feel, PianoPage.Lighting, PianoPage.Pedal)

    private val SCHEDULE_KEYS = setOf("days", "startMinute", "kind", "target", "endMinute", "volumePct", "enabled")

    /** A schedule's target as it may come in: a channel's key or an id, never longer (the rules then check its form). */
    private const val MAX_TARGET = 64

    private val SETTINGS_KEYS = setOf(
        "preRollMs", "defaultTempoPct", "transpose", "velocityPct", "foldOutOfRange", "skipDrumChannel",
        "dynamicRange", "velocityFloor", "expression", "restrikeMs",
        "webGuests", "webApproveFirst", "webHostName", "tabletVolume",
        "noteDisplay", "fingering", "chordNames", "handColours",
        // The Settings page's Panel (v1.18 — M47b).
        "albumBackdrop",
    )

    /** Dynamic range and Expression on the wire (v1.16 — M44): the Playback page's choices, in lower case. */
    private val DYNAMIC_RANGES = DynamicRange.entries.associateBy { it.name.lowercase() }
    private val EXPRESSIONS = ExpressionLevel.entries.associateBy { it.name.lowercase() }

    /** The roll styles the panel may choose (v1.13 — M32): never Score, a phone-sized tablet's own choice. */
    private val NOTE_DISPLAYS = mapOf("paperRoll" to NoteDisplay.PAPER_ROLL, "falling" to NoteDisplay.FALLING)

    /** A display setting's name on the wire. */
    fun noteDisplayName(display: NoteDisplay): String = when (display) {
        NoteDisplay.PAPER_ROLL -> "paperRoll"
        NoteDisplay.FALLING -> "falling"
        NoteDisplay.STAFF -> "score"
    }

    private const val MAX_HOST_NAME = 253

    /** A DNS name: letters, digits and hyphens in dot-separated labels, not starting or ending with a hyphen. No port, no address. */
    private val HOST_NAME = Regex("(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*")

    /** Digits and dots only: an address, never a name (the listener's address is allowed already). */
    private val ADDRESS_LIKE = Regex("[0-9.]+")

    /** A slider's value may come a hair past its end (a browser's float); anything further is out of range. */
    private const val SLIDER_SLACK = 1e-6

    // ---- The System page (v1.18 — M46) -------------------------------------------------

    /**
     * `GET /api/system`: `{at, app{…}, tablet{…, battery{…}, thermal{…}, memory{…}, storage{…}, cpu{…}, network{…}},
     * piano{link{state, name, mtu}, state, facts, factsAt, diag{…}}, running[{key, title, state, detail, progress}],
     * web{sessions, sockets, guests, relay{state, answered, refused}}, covers{found, missing, failed, waiting,
     * blockedUntil}}` (BUILD_SPEC.md › v1.18 — M46). Every key is always there: what is not known is null.
     */
    fun system(s: WebSystem): JSONObject {
        val r = s.reading
        val a = r.app
        val b = r.battery
        val t = r.thermal
        val p = s.piano
        return JSONObject()
            .put("at", s.at)
            .put(
                "app",
                JSONObject().put("version", a.version.orNull()).put("build", a.build.orNull()).put("pid", a.pid.orNull())
                    .put("threads", a.threads.orNull()).put("uptimeMs", a.uptimeMs.orNull()).put("heapUsed", a.heapUsed.orNull())
                    .put("heapMax", a.heapMax.orNull()).put("nativeHeap", a.nativeHeap.orNull()).put("cpuPct", a.cpuPct.orNull())
                    .put("deviceOwner", a.deviceOwner.orNull()).put("kiosk", a.kiosk.orNull()),
            )
            .put(
                "tablet",
                JSONObject().put("model", r.model.orNull()).put("android", r.android.orNull()).put("uptimeMs", r.uptimeMs.orNull())
                    .put("screenOn", r.screenOn.orNull())
                    .put(
                        "battery",
                        JSONObject().put("percent", b.percent.orNull()).put("charging", b.charging.orNull()).put("plug", b.plug.orNull())
                            .put("tempC", b.tempC.orNull()).put("voltageMv", b.voltageMv.orNull()).put("health", b.health.orNull()),
                    )
                    .put(
                        "thermal",
                        JSONObject().put("status", t.status?.let { dev.stevenjin.stevenpiano.diag.SystemReading.THERMAL_WORDS.getOrNull(it) }.orNull())
                            .put("headroom", t.headroom.orNull()).put("cpuC", t.cpuC.orNull()).put("skinC", t.skinC.orNull()),
                    )
                    .put("memory", JSONObject().put("total", r.memory.total.orNull()).put("available", r.memory.available.orNull()).put("low", r.memory.low.orNull()))
                    .put("storage", JSONObject().put("total", r.storage.total.orNull()).put("free", r.storage.free.orNull()))
                    .put("cpu", JSONObject().put("cores", r.cpu.cores.orNull()).put("loadPct", r.cpu.loadPct.orNull()))
                    .put(
                        "network",
                        JSONObject().put("online", r.network.online.orNull()).put("transport", r.network.transport.orNull())
                            .put("signalDbm", r.network.signalDbm.orNull()).put("downKbps", r.network.downKbps.orNull()),
                    ),
            )
            .put(
                "piano",
                JSONObject()
                    .put("link", JSONObject().put("state", p.link.state).put("name", p.link.name.orNull()).put("mtu", p.mtu.orNull()))
                    .put("state", p.state)
                    .put("facts", p.facts?.let { JSONObject(it as Map<*, *>) }.orNull())
                    .put("factsAt", p.factsAt.orNull())
                    .put("diag", pianoDiag(p.facts)),
            )
            .put(
                "running",
                JSONArray().apply {
                    s.running.forEach { put(JSONObject().put("key", it.key).put("title", it.title).put("state", it.state).put("detail", it.detail).put("progress", it.progress.orNull())) }
                },
            )
            .put(
                "web",
                JSONObject().put("sessions", s.web.sessions.orNull()).put("sockets", s.web.sockets.orNull()).put("guests", s.web.guests)
                    .put("relay", JSONObject().put("state", s.web.relay.state).put("answered", s.web.relay.answered.orNull()).put("refused", s.web.relay.refused.orNull())),
            )
            .put(
                "covers",
                JSONObject().put("found", s.covers.found.orNull()).put("missing", s.covers.missing.orNull()).put("failed", s.covers.failed.orNull())
                    .put("waiting", s.covers.waiting.orNull()).put("blockedUntil", s.covers.blockedUntil.orNull()),
            )
    }

    /**
     * The piano's facts as numbers and words (firmware/docs/BLE_DIAG.md), every name of [PianoSettings.diagFacts] in its
     * order, each null when [facts] lack it or it doesn't read: `temp` (°C) and `loopms` decimals; the counts and bytes
     * whole numbers; `reset`, `ota`, `fw` and `pedalboard` words; `boards` a list of `ok` / `missing`, one a power board.
     */
    fun pianoDiag(facts: Map<String, String>?): JSONObject {
        val json = JSONObject()
        for (name in PianoSettings.diagFacts) {
            val text = facts?.get(name)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_FACT }
            val value: Any? = when (name) {
                in DIAG_DECIMALS -> text?.toDoubleOrNull()?.takeIf { it.isFinite() }
                in DIAG_WORDS -> text?.takeIf { word -> word.all { it in ' '..'~' } }
                DIAG_BOARDS -> text?.split(',')?.map { it.trim().lowercase() }?.takeIf { list -> list.all { it.isNotEmpty() && it.all(Char::isLetter) } }?.let { JSONArray(it) }
                else -> text?.toLongOrNull()
            }
            json.put(name, value.orNull())
        }
        return json
    }

    /** `GET /api/system/history`: `{everyMs, samples: [[at, batteryPct, batteryTempC, memAvailPct, thermal, pianoTempC], …]}`, oldest first. */
    fun systemHistory(samples: List<dev.stevenjin.stevenpiano.diag.SystemSample>): JSONObject = JSONObject()
        .put("everyMs", dev.stevenjin.stevenpiano.diag.SystemHistory.EVERY_MS)
        .put(
            "samples",
            JSONArray().apply {
                samples.forEach { x ->
                    put(
                        JSONArray().put(x.at).put(x.batteryPct.orNull()).put(x.batteryTenthsC?.let { it / 10.0 }.orNull())
                            .put(x.memAvailPct.orNull()).put(x.thermal.orNull()).put(x.pianoTenthsC?.let { it / 10.0 }.orNull()),
                    )
                }
            },
        )

    /** A value for the System page's JSON: itself, or JSON's null (never a missing key). */
    private fun Any?.orNull(): Any = this ?: JSONObject.NULL

    /** The facts read as decimals, as words, as the boards' list; every other diag fact is a whole number. */
    private val DIAG_DECIMALS = setOf("temp", "loopms")
    private val DIAG_WORDS = setOf("reset", "ota", "fw", "pedalboard")
    private const val DIAG_BOARDS = "boards"

    /** A fact longer than this is no reading. */
    private const val MAX_FACT = 128

    // ---- The Settings page (v1.18 — M47b) ----------------------------------------------------

    /**
     * `GET /api/settings`: `{values: {defaultTempoPct, transpose, velocityPct, dynamicRange, velocityFloor, expression,
     * restrikeMs, preRollMs, foldOutOfRange, skipDrumChannel, albumBackdrop}, limits: {name: {min, max, step}},
     * piano: {fullPower, repeatMs}}` (BUILD_SPEC.md › v1.18 — M47b). The choices are the PUT's own words (`narrow`,
     * `light`…); `restrikeMs` 0 is Auto, its limits the times set by hand; the piano's two are null when it doesn't say.
     */
    fun settings(s: WebSettings): JSONObject {
        val v = s.settings
        return JSONObject()
            .put(
                "values",
                JSONObject()
                    .put("defaultTempoPct", v.defaultTempoPct)
                    .put("transpose", v.transpose)
                    .put("velocityPct", v.velocityPct)
                    .put("dynamicRange", v.dynamicRange.name.lowercase())
                    .put("velocityFloor", v.velocityFloor)
                    .put("expression", v.expression.name.lowercase())
                    .put("restrikeMs", v.restrikeMs)
                    .put("preRollMs", v.preRollMs)
                    .put("foldOutOfRange", v.foldOutOfRange)
                    .put("skipDrumChannel", v.skipDrumChannel)
                    .put("albumBackdrop", v.albumBackdrop),
            )
            .put(
                "limits",
                JSONObject()
                    .put("defaultTempoPct", limit(PlaybackLimits.TempoPct, TEMPO_STEP))
                    .put("transpose", limit(PlaybackLimits.Transpose, TRANSPOSE_STEP))
                    .put("velocityPct", limit(PlaybackLimits.VelocityPct, VELOCITY_STEP))
                    .put("velocityFloor", limit(PlaybackLimits.VelocityFloor, FLOOR_STEP))
                    .put("restrikeMs", limit(PlaybackLimits.RestrikeMs, PlaybackLimits.RESTRIKE_STEP_MS))
                    .put("preRollMs", limit(PlaybackLimits.PreRollMs, PRE_ROLL_STEP_MS)),
            )
            .put("piano", JSONObject().put("fullPower", s.fullPower.orNull()).put("repeatMs", s.repeatMs.orNull()))
    }

    private fun limit(range: IntRange, step: Int): JSONObject = JSONObject().put("min", range.first).put("max", range.last).put("step", step)

    /** The steps the tablet's Playback page takes (`PlaybackPage`): tempo and velocity in fives, the quietest note in fives, the pause in halves of a second. */
    private const val TEMPO_STEP = 5
    private const val TRANSPOSE_STEP = 1
    private const val VELOCITY_STEP = 5
    private const val FLOOR_STEP = 5
    private const val PRE_ROLL_STEP_MS = 500

    // ---- The cover picker (v1.18 — M48) --------------------------------------------------------

    /**
     * `POST /api/covers/search`'s answer: `{searchId, results: [{index, album, artist, picture}]}`, in Apple's order, each
     * picture its 100 px bytes as a `data:` address (`data:image/jpeg;base64,…`), which the panel's policy lets an `<img>`
     * show and which needs no further request.
     */
    fun coverResults(found: dev.stevenjin.stevenpiano.data.art.CoverSearch.Found): JSONObject = JSONObject()
        .put("searchId", found.searchId)
        .put(
            "results",
            JSONArray().apply {
                for (pick in found.picks) {
                    val picture = "data:${pick.type};base64," + java.util.Base64.getEncoder().encodeToString(pick.picture)
                    put(JSONObject().put("index", pick.index).put("album", pick.album).put("artist", pick.artist).put("picture", picture))
                }
            },
        )
}
