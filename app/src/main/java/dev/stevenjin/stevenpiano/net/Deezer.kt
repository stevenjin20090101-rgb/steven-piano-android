// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.net

import android.util.Log
import dev.stevenjin.stevenpiano.BuildConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.util.Locale

/**
 * One track as Deezer's public search finds it (M82: the world popularity the Library's Popular sort starts from): its
 * `title`, `title_short` (without the version), `artist.name`, `album.title` and `album.id`, and `rank`, Deezer's measure
 * of how much it is played (higher is more).
 */
data class DeezerTrack(val title: String, val titleShort: String?, val artist: String?, val album: String?, val albumId: Long?, val rank: Int)

/** Deezer asked the app to wait (HTTP 429 or 503, or its own "quota" or "busy" error): the lookups back off. */
class DeezerBusyException(message: String) : IOException(message)

/**
 * What the app asks Deezer's public API (M82): no key, no account. Every call is exactly one request (plus redirects).
 * Failures throw [IOException] ([DeezerBusyException] when asked to wait); an answer that finds nothing is empty or null.
 */
interface DeezerApi {
    /** The tracks Deezer finds for [term], at most [limit], in its order. */
    suspend fun search(term: String, limit: Int): List<DeezerTrack>

    /** Album [id]'s label ("Walt Disney Records"), or null when it names none or the album is gone. */
    suspend fun albumLabel(id: Long): String?
}

/**
 * [DeezerApi] over [HttpFetch] (M82), as [AppleCatalog] is: HTTPS to `api.deezer.com` only ([DeezerUrls.allowed];
 * redirects followed by hand, and only there), the app's usual request headers, 10 s to connect and 15 s to read, and
 * 256 KB at most for an answer's JSON. What goes out is a piece's title and its artist's name, or an album's number;
 * nothing about the person. In debug builds each request is logged with its time; release builds log no URL.
 */
class Deezer(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    log: (String) -> Unit = { Log.d(TAG, it) },
    transport: HttpTransport = UrlConnectionTransport,
) : DeezerApi {
    private val http = HttpFetch(
        allowed = DeezerUrls::allowed,
        accept = "application/json",
        connectTimeoutMs = HttpFetch.CONNECT_TIMEOUT_MS,
        readTimeoutMs = READ_TIMEOUT_MS,
        transport = transport,
        log = if (BuildConfig.DEBUG) log else null,
    )

    override suspend fun search(term: String, limit: Int): List<DeezerTrack> =
        get(DeezerUrls.search(term, limit))?.let(DeezerJson::tracks).orEmpty()

    override suspend fun albumLabel(id: Long): String? = get(DeezerUrls.album(id))?.let(DeezerJson::label)

    /** The answer's JSON at [url]; null when it is missing (404, 410, or Deezer's own "no data"). */
    private suspend fun get(url: String): JSONObject? = withContext(io) {
        val body = http.exchange(url) { answer ->
            val code = answer.code
            when {
                code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE -> null
                code == HTTP_TOO_MANY_REQUESTS || code == HttpURLConnection.HTTP_UNAVAILABLE -> throw DeezerBusyException("HTTP $code")
                code !in 200..299 -> throw IOException("HTTP $code")
                answer.contentLength > JSON_CAP -> throw IOException("A response over $JSON_CAP bytes")
                else -> answer.body().use { HttpFetch.readCapped(it, JSON_CAP) } ?: throw IOException("A response over $JSON_CAP bytes")
            }
        } ?: return@withContext null
        // A body that is not the JSON expected counts as a failed request, and so does one nested too deeply for org.json.
        try {
            DeezerJson.answer(body.toString(Charsets.UTF_8))
        } catch (e: JSONException) {
            throw IOException("An unreadable response: ${e.message}")
        } catch (e: StackOverflowError) {
            throw IOException("An unreadable response: nested too deeply")
        }
    }

    companion object {
        const val JSON_CAP = 256 * 1024
        private const val TAG = "Deezer"
        private const val READ_TIMEOUT_MS = 15_000
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

/** Deezer's answers, read with `org.json`. Deezer reports its own errors as `{"error": {"code": …}}` with HTTP 200. */
internal object DeezerJson {
    /** Its "quota exceeded" and "service busy": wait, and ask again later. */
    private val BUSY = setOf(4, 700)

    /** Its "no data", "invalid query" and "wrong parameter": nothing to find. */
    private val NOTHING = setOf(500, 501, 600, 800)

    /** [text] as an object: null for Deezer's "nothing to find"; [DeezerBusyException] when it asks to wait; [IOException] for any other error. */
    fun answer(text: String): JSONObject? {
        val json = JSONObject(text)
        val error = json.optJSONObject("error") ?: return json
        val code = error.optInt("code", -1)
        val message = error.optString("message").ifBlank { "code $code" }
        return when (code) {
            in BUSY -> throw DeezerBusyException("Deezer: $message")
            in NOTHING -> null
            else -> throw IOException("Deezer: $message")
        }
    }

    /** A search's `data[]`: each track with a title (others are skipped), its rank 0 when it gives none. */
    fun tracks(json: JSONObject): List<DeezerTrack> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val track = data.optJSONObject(i) ?: return@mapNotNull null
            val album = track.optJSONObject("album")
            DeezerTrack(
                title = track.text("title") ?: return@mapNotNull null,
                titleShort = track.text("title_short"),
                artist = track.optJSONObject("artist")?.text("name"),
                album = album?.text("title"),
                albumId = album?.optLong("id", 0L)?.takeIf { it > 0 },
                rank = track.optInt("rank", 0).coerceAtLeast(0),
            )
        }
    }

    /** An album's `label`, or null. */
    fun label(json: JSONObject): String? = json.text("label")

    /** A string field, or null when it is missing, JSON null or blank. */
    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).ifBlank { null }
}

/** Where world popularity comes from (M82), built without Android so it is unit-tested: [HOST] alone. */
object DeezerUrls {
    const val HOST = "api.deezer.com"
    private const val HTTPS_PORT = 443

    /** The search, [limit] tracks of it. Spaces in [term] become "+". */
    fun search(term: String, limit: Int): String = "https://$HOST/search?q=${URLEncoder.encode(term.trim(), "UTF-8")}&limit=$limit"

    fun album(id: Long): String = "https://$HOST/album/$id"

    /**
     * Whether the app may request [url]: HTTPS to [HOST], read as `java.net.URI` reads it, with no user info, no port but
     * 443 and no backslash anywhere ([AppleUrls.allowed]'s rule). A redirect's Location passes the same test.
     */
    fun allowed(url: String): Boolean {
        if ('\\' in url) return false
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            return false
        }
        if (uri.scheme != "https" || !(uri.port == -1 || uri.port == HTTPS_PORT) || uri.rawUserInfo != null) return false
        return uri.host?.lowercase(Locale.ROOT) == HOST
    }
}
