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
 * One song in Apple's catalogue (v1.15 — M40), as much of it as covers use: the iTunes Search API's `trackName`,
 * `artistName`, `collectionName` (the album), `artworkUrl100` (its artwork at 100 px), `trackViewUrl` (the track on
 * Apple Music) and `kind` ("song"). [coverUrl] is the artwork to download: 600 px, on Apple's image hosts only, or null.
 */
data class CatalogTrack(
    val trackName: String,
    val artistName: String,
    val collectionName: String?,
    val artworkUrl100: String?,
    val trackViewUrl: String?,
    val kind: String?,
) {
    val coverUrl: String? get() = AppleUrls.cover(artworkUrl100)
}

/**
 * Apple asked the app to stop (HTTP 403 or 429; v1.15 — M40): the cover is recorded as failed and every lookup waits an
 * hour (`data.art.CoverFetcher`).
 */
class AppleBusyException(val code: Int) : IOException("HTTP $code")

/**
 * What the app asks Apple's catalogue (v1.15 — M40). Every call is exactly one request (plus redirects). Failures throw
 * [IOException] ([AppleBusyException] when asked to stop); nothing there is empty or null.
 */
interface AppleCatalogApi {
    /** The songs Apple finds for [term], at most [limit], in Apple's order. */
    suspend fun search(term: String, limit: Int): List<CatalogTrack>

    /** The file at [url], or null when it is missing or larger than [maxBytes]. */
    suspend fun download(url: String, maxBytes: Int): ByteArray?
}

/**
 * [AppleCatalogApi] over [HttpFetch] (v1.15 — M40), as [WikipediaClient] is: HTTPS to `itunes.apple.com` and Apple's
 * image hosts (`*.mzstatic.com`) only ([AppleUrls.allowed]; redirects followed by hand, and only there), the app's
 * User-Agent, 10 s to connect and 15 s to read, and a byte cap on every body before anything decodes it: 256 KB for the
 * search's JSON, the caller's for images. The search asks [country]'s store (the device's, else the US). In debug
 * builds each request is logged with its time; release builds log no URL (they carry titles from the library).
 */
class AppleCatalog(
    private val country: String = AppleUrls.country(Locale.getDefault().country),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    log: (String) -> Unit = { Log.d(TAG, it) },
    transport: HttpTransport = UrlConnectionTransport,
) : AppleCatalogApi {
    private val http = HttpFetch(
        allowed = AppleUrls::allowed,
        connectTimeoutMs = HttpFetch.CONNECT_TIMEOUT_MS,
        readTimeoutMs = READ_TIMEOUT_MS,
        transport = transport,
        log = if (BuildConfig.DEBUG) log else null,
    )

    override suspend fun search(term: String, limit: Int): List<CatalogTrack> {
        val body = get(AppleUrls.search(term, limit, country), JSON_CAP, oversizeIsMissing = false) ?: return emptyList()
        // A body that is not the JSON expected counts as a failed request, and so does one nested too deeply for org.json.
        return try {
            AppleJson.tracks(body.toString(Charsets.UTF_8))
        } catch (e: JSONException) {
            throw IOException("An unreadable response: ${e.message}")
        } catch (e: StackOverflowError) {
            throw IOException("An unreadable response: nested too deeply")
        }
    }

    override suspend fun download(url: String, maxBytes: Int): ByteArray? = get(url, maxBytes, oversizeIsMissing = true)

    private suspend fun get(start: String, cap: Int, oversizeIsMissing: Boolean): ByteArray? = withContext(io) {
        http.exchange(start) { answer ->
            val code = answer.code
            when {
                code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE -> null
                code == HttpURLConnection.HTTP_FORBIDDEN || code == HTTP_TOO_MANY_REQUESTS -> throw AppleBusyException(code)
                code !in 200..299 -> throw IOException("HTTP $code")
                else -> {
                    val body = if (answer.contentLength > cap) null else answer.body().use { HttpFetch.readCapped(it, cap) }
                    when {
                        body != null -> body
                        oversizeIsMissing -> null
                        else -> throw IOException("A response over $cap bytes")
                    }
                }
            }
        }
    }

    companion object {
        const val JSON_CAP = 256 * 1024
        private const val TAG = "AppleCatalog"
        private const val READ_TIMEOUT_MS = 15_000
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }
}

/** The search's answer, read with `org.json`: `results[]`, each a song with a name and an artist (others are skipped). */
internal object AppleJson {
    fun tracks(text: String): List<CatalogTrack> {
        val results = JSONObject(text).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val song = results.optJSONObject(i) ?: return@mapNotNull null
            CatalogTrack(
                trackName = song.text("trackName") ?: return@mapNotNull null,
                artistName = song.text("artistName") ?: return@mapNotNull null,
                collectionName = song.text("collectionName"),
                artworkUrl100 = song.text("artworkUrl100"),
                trackViewUrl = song.text("trackViewUrl"),
                kind = song.text("kind"),
            )
        }
    }

    /** A string field, or null when it is missing, JSON null or blank. */
    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).ifBlank { null }
}

/**
 * Where covers come from (v1.15 — M40), built without Android so it is unit-tested: [SEARCH_HOST] for the search, and
 * Apple's image hosts, every name under [IMAGE_DOMAIN] ("is1-ssl.mzstatic.com"), for the artwork. What goes out is a
 * piece's title and its artist's name, from the library, and a two-letter country; nothing about the person.
 */
object AppleUrls {
    const val SEARCH_HOST = "itunes.apple.com"

    /** Apple's image hosts are the names under this one. */
    const val IMAGE_DOMAIN = "mzstatic.com"

    /** Where a track's page may be: Apple Music, or the iTunes Store's older links. */
    val PAGE_HOSTS: Set<String> = setOf("music.apple.com", SEARCH_HOST)

    /** The store searched when the device names no country of two letters. */
    const val DEFAULT_COUNTRY = "US"

    /** The artwork's size in its URL as the search gives it, and as the app asks for it. */
    private const val SMALL = "100x100bb"
    private const val LARGE = "600x600bb"
    private const val HTTPS_PORT = 443

    /** The search: songs only, [limit] of them, in [country]'s store. Spaces in [term] become "+", as Apple documents. */
    fun search(term: String, limit: Int, country: String): String =
        "https://$SEARCH_HOST/search?term=${URLEncoder.encode(term.trim(), "UTF-8")}&media=music&entity=song&limit=$limit&country=$country"

    /** [device] (`Locale.getDefault().country`) upper-cased when it is two letters; else the US. */
    fun country(device: String?): String =
        device?.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' || c in 'A'..'Z' } }?.uppercase(Locale.ROOT) ?: DEFAULT_COUNTRY

    /**
     * Whether the app may request [url] for a cover: HTTPS to [SEARCH_HOST] or an image host, read as `java.net.URI` reads
     * it (so as the connection will), with no user info, no port but 443 and no backslash anywhere ([WikipediaUrls.allowed]'s
     * rule). A redirect's Location passes the same test.
     */
    fun allowed(url: String): Boolean {
        val uri = parse(url) ?: return false
        if (uri.scheme != "https" || !(uri.port == -1 || uri.port == HTTPS_PORT)) return false
        val host = hostOf(uri)
        return host == SEARCH_HOST || isImageHost(host)
    }

    /**
     * The cover to download for [artworkUrl100]: the same picture at 600 px (its last segment's "100x100bb" becomes
     * "600x600bb"), over HTTPS on an image host, without query or fragment. Null for anything else, a lookalike host or
     * plain HTTP included.
     */
    fun cover(artworkUrl100: String?): String? {
        val uri = artworkUrl100?.trim()?.let(::parse) ?: return null
        val host = hostOf(uri)
        if (uri.scheme != "https" || !(uri.port == -1 || uri.port == HTTPS_PORT) || !isImageHost(host)) return null
        val path = uri.rawPath?.takeIf { it.length > 1 } ?: return null
        val cut = path.lastIndexOf('/') + 1
        val name = path.substring(cut)
        val at = name.lastIndexOf(SMALL)
        val large = if (at < 0) name else name.replaceRange(at, at + SMALL.length, LARGE)
        return "https://$host${path.substring(0, cut)}$large"
    }

    /**
     * [url] if the piece sheet may offer it as the cover's link: a page on [PAGE_HOSTS] over HTTPS, with no user info and
     * no port. Checked when the cover is kept and again when the link is opened.
     */
    fun pageLink(url: String?): String? {
        val uri = url?.let(::parse) ?: return null
        val ok = uri.scheme == "https" && hostOf(uri) in PAGE_HOSTS && uri.port == -1 && (uri.rawPath?.length ?: 0) > 1
        return if (ok) url else null
    }

    private fun isImageHost(host: String): Boolean = host.endsWith(".$IMAGE_DOMAIN") && host.length > IMAGE_DOMAIN.length + 1

    private fun hostOf(uri: URI): String = if (uri.rawUserInfo != null) "" else uri.host?.lowercase(Locale.ROOT).orEmpty()

    private fun parse(url: String): URI? {
        if ('\\' in url) return null
        return try {
            URI(url)
        } catch (e: URISyntaxException) {
            null
        }
    }
}
