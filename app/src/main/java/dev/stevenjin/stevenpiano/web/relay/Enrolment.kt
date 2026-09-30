// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.web.relay

import dev.stevenjin.stevenpiano.net.HttpPost
import dev.stevenjin.stevenpiano.net.RefusedRequestException
import dev.stevenjin.stevenpiano.web.WebApi
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** How enrolling went: this tablet's piano [Enrolled] at [Enrolled.host], or [Refused] with a line saying why. */
sealed interface EnrolResult {
    class Enrolled(val host: String, val pianoId: String, val secret: String) : EnrolResult {
        override fun toString(): String = "Enrolled($host, $pianoId, secret kept)"
    }

    data class Refused(val message: String) : EnrolResult
}

/**
 * Where the tablet reaches Steven Piano Cloud (v1.10 — M26): the relay's host as the person typed it
 * ([host]) and the enrolment code from the console ([code]), each read the forgiving way (any case,
 * an `https://` or a trailing slash, the code's spaces or dash) and kept in its one form, or null
 * when it isn't one; and, in the debug build's local test only, the origin that stands in for the
 * relay ([origin]).
 */
object CloudAddress {
    /** An enrolment code's 32 letters: no I, O, 0 or 1, which read alike (the relay's `CODE_ALPHABET`). */
    const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    private const val MAX_HOST = 253

    /** "relay.example.workers.dev", "Relay.Example.dev/", "https://relay.example.dev" → the host in lower case; null otherwise. */
    fun host(typed: String): String? {
        val bare = typed.trim().lowercase(Locale.ROOT).removePrefix("https://").trimEnd('/')
        if (bare.isEmpty() || bare.length > MAX_HOST || !RelayProtocol.HOST.matches(bare)) return null
        val port = bare.substringAfter(':', "").takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (':' in bare && (port == null || port !in 1..65_535)) return null
        return bare
    }

    /** "abcd efgh", "ABCD-EFGH", "abcdefgh" → "ABCD-EFGH"; null unless it is eight of the code's letters. */
    fun code(typed: String): String? {
        if (typed.length > 32) return null
        val bare = typed.uppercase(Locale.ROOT).filterNot { it.isWhitespace() || it == '-' }
        if (bare.length != 8 || bare.any { it !in CODE_ALPHABET }) return null
        return "${bare.substring(0, 4)}-${bare.substring(4)}"
    }

    /** The relay's origin for [host]: `https://<host>`, or the debug build's stand-in ([override], `http://10.0.2.2:8787`). */
    fun origin(host: String, override: String?): String = override ?: "https://$host"

    /** The relay's WebSocket for the tablet at [origin]: `wss://…/tablet` (`ws://` only for the debug stand-in). */
    fun socketUrl(origin: String): String = origin.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/tablet"

    /** The panel's scheme at [origin]: `https`, or `http` for the debug stand-in. */
    fun scheme(origin: String): String = if (origin.startsWith("http://")) "http" else "https"
}

/**
 * Enrolling this tablet with a code from the console (BUILD_SPEC.md › v1.10 — M26): one HTTPS POST
 * of `{code}` to `https://<host>/api/enrol` (nothing else goes: no name, no device id), to that
 * address and no other ([allows]: TLS, the typed host and port exactly, that path, no user info; in
 * the debug build's local test, the stand-in [override] origin alone), no redirect followed, at most
 * 16 KB of answer. The answer `{pianoId, secret, …}` must hold a piano's id and a secret of the
 * relay's forms. The relay's refusals come back in plain words.
 */
class Enrolment(
    private val override: String? = null,
    private val post: (allowed: (String) -> Boolean) -> HttpPost = { allowed -> HttpPost(allowed) },
) {
    /** Blocking: call it on an I/O thread. */
    fun enrol(typedHost: String, typedCode: String): EnrolResult {
        val host = CloudAddress.host(typedHost) ?: return EnrolResult.Refused(NOT_AN_ADDRESS)
        val code = CloudAddress.code(typedCode) ?: return EnrolResult.Refused(NOT_A_CODE)
        val url = CloudAddress.origin(host, override) + PATH
        val answer = try {
            post { allows(it, host) }.postJson(url, JSONObject().put("code", code).toString())
        } catch (e: RefusedRequestException) {
            return EnrolResult.Refused(NOT_AN_ADDRESS)
        } catch (e: IOException) {
            return EnrolResult.Refused(UNREACHABLE)
        } catch (e: SecurityException) {
            return EnrolResult.Refused(UNREACHABLE)
        }
        return when (answer.code) {
            200 -> read(answer.body, host)
            404 -> EnrolResult.Refused("That code isn't right, or it has expired. Make a new one in the console.")
            429 -> EnrolResult.Refused("Too many tries. Try again in a minute.")
            400, 411, 413, 415 -> EnrolResult.Refused(NOT_A_CODE)
            else -> EnrolResult.Refused("The relay answered ${answer.code}. Try again in a moment.")
        }
    }

    /** Whether the enrolment may go to [url]: exactly `https://<host>/api/enrol` (or the stand-in's). */
    fun allows(url: String, host: String): Boolean {
        if (override != null) return url == override + PATH
        if ('\\' in url) return false
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        val typedPort = host.substringAfter(':', "").toIntOrNull() ?: -1
        return uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.host?.lowercase(Locale.ROOT) == host.substringBefore(':') && uri.port == typedPort && uri.rawPath == PATH
    }

    private fun read(body: ByteArray?, host: String): EnrolResult {
        val text = body?.toString(Charsets.UTF_8) ?: return EnrolResult.Refused(UNUSABLE)
        if (WebApi.depthOf(text) > 2) return EnrolResult.Refused(UNUSABLE)
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return EnrolResult.Refused(UNUSABLE)
        }
        val pianoId = (json.opt("pianoId") as? String)?.takeIf(RelayProtocol.PIANO_ID::matches)
        val secret = (json.opt("secret") as? String)?.takeIf(RelayProtocol.SECRET::matches)
        if (pianoId == null || secret == null) return EnrolResult.Refused(UNUSABLE)
        return EnrolResult.Enrolled(host, pianoId, secret)
    }

    companion object {
        const val PATH = "/api/enrol"
        const val NOT_AN_ADDRESS = "That isn't the relay's address. It looks like steven-piano-relay.you.workers.dev."
        const val NOT_A_CODE = "That isn't a code. Codes look like ABCD-EFGH."
        const val UNREACHABLE = "The relay can't be reached. Check its address and the network."
        const val UNUSABLE = "The relay's answer isn't one the tablet can use."
    }
}
