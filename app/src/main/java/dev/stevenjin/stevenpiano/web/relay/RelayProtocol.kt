// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import dev.stevenjin.stevenpiano.web.WebApi
import org.json.JSONException
import org.json.JSONObject
import java.math.BigInteger

/**
 * The relay protocol between the tablet and its piano's room on Steven Piano Cloud,
 * `steven-piano-relay-1` (BUILD_SPEC.md › v1.10 — M26; the relay speaks the same in
 * `cloud/src/shared/protocol.ts`, byte for byte on the binary frames):
 *
 * - The tablet connects to `wss://<relay>/tablet` with `Authorization: Bearer <pianoId>.<secret>`
 *   and the subprotocol [SUBPROTOCOL]. Text frames are JSON of at most [MAX_TEXT] bytes; binary
 *   frames are [Frame]s, `id: u32 big-endian | kind: u8 | payload` of at most [MAX_CHUNK] bytes.
 * - The room says [RelayMessage.Hello] on accept; the tablet reports [RelayMessage.Status].
 * - A browser's request is [RelayMessage.Req], its body [Frame.REQ_CHUNK]s under a credit window
 *   ([RelayMessage.ReqCredit]) then [Frame.REQ_END]; the answer is [RelayMessage.Res], then
 *   [Frame.RES_CHUNK]s and [Frame.RES_END]. [RelayMessage.ReqAbort]: the room gave up on it.
 * - A browser's socket is bridged: [RelayMessage.WsOpen] → [RelayMessage.WsAccept] or
 *   [RelayMessage.WsRefuse], then [RelayMessage.WsText] (the tablet's to the browser) and
 *   [RelayMessage.WsClose] either way.
 * - The console: [RelayMessage.Cmd] → [RelayMessage.CmdResult]. Rotation: [RelayMessage.Secret] →
 *   [RelayMessage.SecretAck].
 *
 * Every message is read strictly ([decode]): a frame over [MAX_TEXT], JSON nested deeper than
 * [MAX_DEPTH], an unknown `t`, an id that is not a whole number from 0 to 2³² − 1, or a field of the
 * wrong type or length is no message at all (null). Pure: no Android, no sockets.
 */
object RelayProtocol {
    const val SUBPROTOCOL = "steven-piano-relay-1"

    /** A text frame's JSON, at most (the relay's `MAX_TEXT_FRAME`). */
    const val MAX_TEXT = 64 * 1024

    /** A binary frame's payload, at most (the relay's `CHUNK`). */
    const val MAX_CHUNK = 64 * 1024

    /** The request body the room may send before the tablet grants more, unless its hello says otherwise (the relay's `WINDOW`). */
    const val DEFAULT_WINDOW = 1024 * 1024

    /** The largest request body the relay passes (the Free plan's 100 MB), unless its hello says otherwise. */
    const val DEFAULT_MAX_BODY = 104_857_600L

    /** The largest window a hello may ask for: the tablet queues a request's body up to it. */
    const val MAX_WINDOW = 16 * 1024 * 1024

    /** How deeply a message's JSON may nest (a status is three deep). */
    const val MAX_DEPTH = 6

    /** Revoked from the console: the tablet stops trying. */
    const val CLOSE_REVOKED = 4401

    /** Turned off (forgotten) in the console: the tablet stops trying. */
    const val CLOSE_DISABLED = 4403

    /** Another connection with this piano's id took over: the tablet tries again after a minute. */
    const val CLOSE_REPLACED = 4409

    /** The headers of a browser's request the tablet is shown, and no others (the relay's `FORWARDED_HEADERS`). */
    val FORWARDED_HEADERS: Set<String> = setOf("host", "cookie", "origin", "content-type", "content-length", "x-steven-piano", "accept")

    /** The headers of a browser's socket the tablet is shown (the relay's `SOCKET_HEADERS`). */
    val SOCKET_HEADERS: Set<String> = setOf("cookie", "origin", "host")

    /** A piano's id: 12 characters of lower-case base32. */
    val PIANO_ID = Regex("[a-z2-7]{12}")

    /** A tablet's bearer secret: 32 random bytes, base64url without padding. */
    val SECRET = Regex("[A-Za-z0-9_-]{43}")

    /** A host as the relay names itself: a DNS name or an IPv4 address, lower case, with a port or not. */
    val HOST = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*(:[0-9]{1,5})?")

    private const val MAX_U32 = 0xFFFF_FFFFL
    private const val MAX_PATH = 8 * 1024
    private const val MAX_HEADER = 8 * 1024
    private const val MAX_ADDRESS = 64
    private const val MAX_METHOD = 16
    private const val MAX_NAME = 32
    private const val MAX_REASON = 120
    private const val MAX_HOST = 253
    private val METHOD = Regex("[A-Z]{1,$MAX_METHOD}")
    private val COMMAND = Regex("[a-zA-Z.]{1,$MAX_NAME}")

    /** [text] as a message, or null when it is none (too long, not JSON, unknown, or malformed). */
    fun decode(text: String): RelayMessage? {
        if (utf8Length(text) > MAX_TEXT) return null
        if (WebApi.depthOf(text) > MAX_DEPTH) return null
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return null
        } catch (e: StackOverflowError) {
            return null
        }
        return try {
            read(json)
        } catch (e: JSONException) {
            null
        }
    }

    private fun read(json: JSONObject): RelayMessage? = when (json.opt("t")) {
        "hello" -> hello(json)
        "req" -> req(json)
        "req.abort" -> id(json)?.let { RelayMessage.ReqAbort(it) }
        "req.credit" -> {
            val bytes = whole(json.opt("bytes"))
            val id = id(json)
            if (id == null || bytes == null || bytes <= 0) null else RelayMessage.ReqCredit(id, bytes)
        }
        "res" -> res(json)
        "ws.open" -> {
            val id = id(json)
            val headers = headers(json.opt("headers"), SOCKET_HEADERS)
            val address = text(json.opt("address"), MAX_ADDRESS)
            if (id == null || headers == null || address == null) null else RelayMessage.WsOpen(id, headers, address)
        }
        "ws.accept" -> id(json)?.let { RelayMessage.WsAccept(it) }
        "ws.refuse" -> {
            val id = id(json)
            val status = whole(json.opt("status"))?.toInt()
            if (id == null || status == null || status !in 400..599) null else RelayMessage.WsRefuse(id, status)
        }
        "ws.text" -> {
            val id = id(json)
            val data = json.opt("data") as? String
            if (id == null || data == null) null else RelayMessage.WsText(id, data)
        }
        "ws.close" -> {
            val id = id(json)
            val code = whole(json.opt("code"))?.toInt()?.takeIf { it in 1000..4999 } ?: 1000
            val reason = (json.opt("reason") as? String)?.take(MAX_REASON) ?: ""
            id?.let { RelayMessage.WsClose(it, code, reason) }
        }
        "cmd" -> {
            val id = id(json)
            val name = (json.opt("name") as? String)?.takeIf(COMMAND::matches)
            val args = when (val raw = json.opt("args")) {
                null, JSONObject.NULL -> emptyMap()
                is JSONObject -> args(raw)
                else -> null
            }
            if (id == null || name == null || args == null) null else RelayMessage.Cmd(id, name, args)
        }
        "cmd.result" -> {
            val id = id(json)
            val ok = json.opt("ok") as? Boolean
            val message = (json.opt("message") as? String)?.take(MAX_REASON * 2) ?: ""
            if (id == null || ok == null) null else RelayMessage.CmdResult(id, ok, message)
        }
        "secret" -> (json.opt("secret") as? String)?.takeIf(SECRET::matches)?.let { RelayMessage.Secret(it) }
        "secret.ack" -> RelayMessage.SecretAck
        "status" -> RelayMessage.Status(json)
        else -> null
    }

    private fun hello(json: JSONObject): RelayMessage.Hello? {
        val pianoId = (json.opt("pianoId") as? String)?.takeIf(PIANO_ID::matches) ?: return null
        val host = (json.opt("host") as? String)?.lowercase()?.takeIf { it.length <= MAX_HOST && HOST.matches(it) } ?: return null
        val prefix = (json.opt("prefix") as? String)?.takeIf { it == "/p/$pianoId" } ?: return null
        val caps = json.opt("caps") as? JSONObject
        val chunk = whole(caps?.opt("chunk"))?.takeIf { it in 1..MAX_CHUNK }?.toInt() ?: MAX_CHUNK
        val window = whole(caps?.opt("window"))?.takeIf { it in chunk..MAX_WINDOW }?.toInt() ?: DEFAULT_WINDOW
        val maxBody = whole(caps?.opt("maxBody"))?.takeIf { it > 0 } ?: DEFAULT_MAX_BODY
        return RelayMessage.Hello(pianoId, host, prefix, Caps(maxBody, chunk, window), whole(json.opt("at")) ?: 0L)
    }

    private fun req(json: JSONObject): RelayMessage.Req? {
        val id = id(json) ?: return null
        val method = (json.opt("method") as? String)?.takeIf(METHOD::matches) ?: return null
        val path = text(json.opt("path"), MAX_PATH)?.takeIf { it.startsWith('/') } ?: return null
        val query = when (val raw = json.opt("query")) {
            null, JSONObject.NULL -> ""
            is String -> raw.removePrefix("?").takeIf { it.length <= MAX_PATH && !hasBreak(it) } ?: return null
            else -> return null
        }
        val headers = headers(json.opt("headers"), FORWARDED_HEADERS) ?: return null
        val address = text(json.opt("address"), MAX_ADDRESS) ?: "unknown"
        val prefix = text(json.opt("prefix"), MAX_PATH) ?: ""
        val body = json.opt("body") as? Boolean ?: false
        return RelayMessage.Req(id, method, path, query, headers, address, prefix, body)
    }

    private fun res(json: JSONObject): RelayMessage.Res? {
        val id = id(json) ?: return null
        val status = whole(json.opt("status"))?.toInt()?.takeIf { it in 100..599 } ?: return null
        val raw = json.opt("headers") as? JSONObject ?: return null
        val headers = LinkedHashMap<String, String>()
        for (name in raw.keys()) (raw.opt(name) as? String)?.let { headers[name] = it }
        val length = whole(json.opt("length")) ?: -1L
        return RelayMessage.Res(id, status, headers, length)
    }

    /** The headers named in [allowed], lower-cased, string values without line breaks; the rest dropped. Null when not an object. */
    private fun headers(raw: Any?, allowed: Set<String>): Map<String, String>? {
        if (raw == null || raw == JSONObject.NULL) return emptyMap()
        if (raw !is JSONObject) return null
        val out = LinkedHashMap<String, String>()
        for (name in raw.keys()) {
            val key = name.lowercase()
            if (key !in allowed) continue
            val value = raw.opt(name) as? String ?: continue
            if (value.length > MAX_HEADER || hasBreak(value)) continue
            out.putIfAbsent(key, value)
        }
        return out
    }

    /** A command's arguments: strings, numbers, booleans and null; anything nested is kept for the command to refuse. */
    private fun args(raw: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (name in raw.keys()) out[name] = raw.opt(name).takeUnless { it == JSONObject.NULL }
        return out
    }

    private fun id(json: JSONObject): Long? = whole(json.opt("id"))?.takeIf { it in 0..MAX_U32 }

    private fun text(value: Any?, max: Int): String? = (value as? String)?.takeIf { it.length <= max && !hasBreak(it) }

    private fun hasBreak(value: String): Boolean = value.any { it == '\r' || it == '\n' || it == '\u0000' }

    /** A JSON number that is a whole number fitting a Long, or null (a fraction, text, a boolean). */
    fun whole(value: Any?): Long? = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is BigInteger -> if (value.bitLength() < 64) value.toLong() else null
        is Double -> if (value == Math.rint(value) && !value.isInfinite() && kotlin.math.abs(value) < 9.0E15) value.toLong() else null
        is Float -> null
        is Number -> value.toString().toLongOrNull()
        else -> null
    }

    /** [text]'s length in UTF-8 bytes, counted without encoding it. */
    fun utf8Length(text: CharSequence): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }
}

/** What the room says it allows: the largest body it passes, the largest chunk, and the credit window. */
data class Caps(val maxBody: Long = RelayProtocol.DEFAULT_MAX_BODY, val chunk: Int = RelayProtocol.MAX_CHUNK, val window: Int = RelayProtocol.DEFAULT_WINDOW)

/**
 * One text message of the protocol, either way ([RelayProtocol] has the rules). [encode] gives its
 * JSON; [RelayProtocol.decode] reads it back. Ids are whole numbers from 0 to 2³² − 1.
 */
sealed interface RelayMessage {
    fun encode(): String

    /** The room, on accept: this piano's id, the host browsers use, the path prefix, what it allows, its time. */
    data class Hello(val pianoId: String, val host: String, val prefix: String, val caps: Caps, val at: Long) : RelayMessage {
        override fun encode(): String = JSONObject()
            .put("t", "hello").put("pianoId", pianoId).put("host", host).put("prefix", prefix)
            .put("caps", JSONObject().put("maxBody", caps.maxBody).put("chunk", caps.chunk).put("window", caps.window))
            .put("at", at).toString()
    }

    /**
     * A browser's request: [path] after the prefix and [query] without its `?`, both still
     * percent-encoded; only the forwarded [headers]; the browser's [address]; whether a [body] follows.
     */
    data class Req(
        val id: Long,
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val address: String,
        val prefix: String,
        val body: Boolean,
    ) : RelayMessage {
        override fun encode(): String = JSONObject()
            .put("t", "req").put("id", id).put("method", method).put("path", path).put("query", query)
            .put("headers", JSONObject(headers as Map<*, *>)).put("address", address).put("prefix", prefix).put("body", body).toString()
    }

    /** The room gave up on request [id] (the browser went, or it waited too long). */
    data class ReqAbort(val id: Long) : RelayMessage {
        override fun encode(): String = JSONObject().put("t", "req.abort").put("id", id).toString()
    }

    /** The tablet has read [bytes] more of request [id]'s body: the room may send that many more. */
    data class ReqCredit(val id: Long, val bytes: Long) : RelayMessage {
        override fun encode(): String = JSONObject().put("t", "req.credit").put("id", id).put("bytes", bytes).toString()
    }

    /** The tablet's answer to request [id]: its status, its headers, and its body's [length] (−1: not known). */
    data class Res(val id: Long, val status: Int, val headers: Map<String, String>, val length: Long) : RelayMessage {
        override fun encode(): String = JSONObject()
            .put("t", "res").put("id", id).put("status", status).put("headers", JSONObject(headers as Map<*, *>)).put("length", length).toString()
    }

    /** A browser opened the panel's socket: its [headers] (cookie, origin, host) and [address]. */
    data class WsOpen(val id: Long, val headers: Map<String, String>, val address: String) : RelayMessage {
        override fun encode(): String = JSONObject()
            .put("t", "ws.open").put("id", id).put("headers", JSONObject(headers as Map<*, *>)).put("address", address).toString()
    }

    data class WsAccept(val id: Long) : RelayMessage {
        override fun encode(): String = JSONObject().put("t", "ws.accept").put("id", id).toString()
    }

    /** The tablet refuses the socket: 401 (no session), 403 (another origin or host), 503 (no room). */
    data class WsRefuse(val id: Long, val status: Int) : RelayMessage {
        override fun encode(): String = JSONObject().put("t", "ws.refuse").put("id", id).put("status", status).toString()
    }

    /** A message for socket [id]'s browser, passed on as it is. */
    data class WsText(val id: Long, val data: String) : RelayMessage {
        override fun encode(): String = JSONObject().put("t", "ws.text").put("id", id).put("data", data).toString()
    }

    /** Socket [id] is over, from either side. */
    data class WsClose(val id: Long, val code: Int, val reason: String) : RelayMessage {
        override fun encode(): String = JSONObject().put("t", "ws.close").put("id", id).put("code", code).put("reason", reason).toString()
    }

    /** A console command: its [name] and [args] (strings, numbers, booleans; checked by the tablet again). */
    data class Cmd(val id: Long, val name: String, val args: Map<String, Any?>) : RelayMessage {
        override fun encode(): String = JSONObject()
            .put("t", "cmd").put("id", id).put("name", name).put("args", JSONObject(args as Map<*, *>)).toString()
    }

    /** How command [id] went, in a line the console shows. */
    data class CmdResult(val id: Long, val ok: Boolean, val message: String) : RelayMessage {
        override fun encode(): String = JSONObject().put("t", "cmd.result").put("id", id).put("ok", ok).put("message", message).toString()
    }

    /** A new bearer secret for the tablet to keep (a rotation's first phase). */
    data class Secret(val secret: String) : RelayMessage {
        override fun encode(): String = JSONObject().put("t", "secret").put("secret", secret).toString()

        override fun toString(): String = "Secret(kept)"
    }

    /** The tablet keeps the new secret: the room makes it the only one. */
    data object SecretAck : RelayMessage {
        override fun encode(): String = JSONObject().put("t", "secret.ack").toString()
    }

    /** The tablet's status report ([RelayStatus] builds its fields); [body] is sent with its `t`. */
    class Status(val body: JSONObject) : RelayMessage {
        override fun encode(): String = JSONObject(body.toString()).put("t", "status").toString()
    }
}

/**
 * A binary frame: request or answer [id] (0 to 2³² − 1), its [kind] and its [payload] (at most
 * [RelayProtocol.MAX_CHUNK] bytes). On the wire: the id as four bytes, big-endian, the kind as one,
 * then the payload.
 */
class Frame(val id: Long, val kind: Int, val payload: ByteArray = EMPTY) {
    init {
        require(id in 0..0xFFFF_FFFFL) { "A frame's id is an unsigned 32-bit number" }
        require(kind in 0..255) { "A frame's kind is one byte" }
        require(payload.size <= RelayProtocol.MAX_CHUNK) { "A frame's payload is ${RelayProtocol.MAX_CHUNK} bytes at most" }
    }

    fun encode(): ByteArray {
        val out = ByteArray(HEAD + payload.size)
        out[0] = (id ushr 24).toByte()
        out[1] = (id ushr 16).toByte()
        out[2] = (id ushr 8).toByte()
        out[3] = id.toByte()
        out[4] = kind.toByte()
        payload.copyInto(out, HEAD)
        return out
    }

    companion object {
        const val REQ_CHUNK = 1
        const val REQ_END = 2
        const val RES_CHUNK = 3
        const val RES_END = 4

        /** The id and the kind before the payload. */
        const val HEAD = 5

        private val EMPTY = ByteArray(0)

        /** [bytes] as a frame, or null when shorter than its head or its payload is over the chunk. */
        fun decode(bytes: ByteArray): Frame? {
            if (bytes.size < HEAD || bytes.size > HEAD + RelayProtocol.MAX_CHUNK) return null
            val id = ((bytes[0].toLong() and 0xFF) shl 24) or ((bytes[1].toLong() and 0xFF) shl 16) or
                ((bytes[2].toLong() and 0xFF) shl 8) or (bytes[3].toLong() and 0xFF)
            return Frame(id, bytes[4].toInt() and 0xFF, bytes.copyOfRange(HEAD, bytes.size))
        }
    }
}
