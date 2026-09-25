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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A Wikipedia page's summary, as much of it as the app uses. [type] is "standard",
 * "disambiguation" and so on; [description] is the one-line Wikidata description ("German
 * composer (1685–1750)"); [pageUrl] is the article's address when it passes
 * [WikipediaUrls.pageLink], else null; [imageUrl] is the image to download, already chosen by
 * [WikipediaUrls.image], or null.
 */
data class WikiSummary(
    val title: String,
    val type: String,
    val description: String?,
    val extract: String?,
    val pageUrl: String?,
    val imageUrl: String?,
) {
    val isDisambiguation: Boolean get() = type == "disambiguation"
}

/**
 * Wikimedia asked the app to slow down (HTTP 429, or 503 while it is busy). [retryAfterMs] is how
 * long it asked for, when it said. Not a failure: the fetch waits once, then leaves it for later.
 */
class WikiBusyException(val code: Int, val retryAfterMs: Long?) : IOException("HTTP $code")

/**
 * What the app asks Wikipedia. Every call is exactly one request (plus redirects). Failures throw
 * [IOException] ([WikiBusyException] when asked to wait); a missing page or file is null.
 */
interface WikiApi {
    /** The summary of the page called [title], or null when there is no such page. */
    suspend fun summary(title: String): WikiSummary?

    /** The titles of the best three articles for [query], best first. */
    suspend fun search(query: String): List<String>

    /** The file at [url], or null when it is missing or larger than [maxBytes]. */
    suspend fun download(url: String, maxBytes: Int): ByteArray?
}

/**
 * [WikiApi] over `HttpURLConnection`: HTTPS to [WikipediaUrls.HOSTS] only (redirects are followed
 * by hand, and only to those hosts), the app's User-Agent and `Accept: application/json` on every
 * request (Wikimedia answers 403 without a User-Agent), 10 s to connect and 15 s to read, and a
 * byte cap on every body before anything decodes it: 256 KB for JSON, the caller's for files.
 * In debug builds each request is logged with its time, so their spacing can be checked; release
 * builds log no URL (they carry names from the library).
 */
class WikipediaClient(
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = { Log.d(TAG, it) },
) : WikiApi {
    override suspend fun summary(title: String): WikiSummary? {
        val body = get(WikipediaUrls.summary(title), JSON_CAP, oversizeIsMissing = false) ?: return null
        return parse { WikiJson.summary(body.toString(Charsets.UTF_8)) }
    }

    override suspend fun search(query: String): List<String> {
        val body = get(WikipediaUrls.search(query), JSON_CAP, oversizeIsMissing = false) ?: return emptyList()
        return parse { WikiJson.searchTitles(body.toString(Charsets.UTF_8)) }
    }

    override suspend fun download(url: String, maxBytes: Int): ByteArray? = get(url, maxBytes, oversizeIsMissing = true)

    private suspend fun get(start: String, cap: Int, oversizeIsMissing: Boolean): ByteArray? = withContext(io) {
        var url = start
        repeat(MAX_REDIRECTS + 1) {
            if (!WikipediaUrls.allowed(url)) throw IOException("Refused a request to ${WikipediaUrls.hostOf(url)}")
            if (BuildConfig.DEBUG) log("GET $url · User-Agent: $USER_AGENT · at ${System.currentTimeMillis()} ms")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = connection.responseCode
                when {
                    code in 300..399 -> {
                        val location = connection.getHeaderField("Location") ?: throw IOException("HTTP $code without a Location")
                        url = URL(URL(url), location).toString()
                        return@repeat
                    }
                    code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE -> return@withContext null
                    code == HTTP_TOO_MANY_REQUESTS || code == HttpURLConnection.HTTP_UNAVAILABLE ->
                        throw WikiBusyException(code, WikipediaUrls.retryAfterMillis(connection.getHeaderField("Retry-After")))
                    code !in 200..299 -> throw IOException("HTTP $code")
                }
                val declared = connection.contentLengthLong
                val body = if (declared > cap) null else connection.inputStream.use { readCapped(it, cap) }
                if (body == null) {
                    if (oversizeIsMissing) return@withContext null
                    throw IOException("A response over $cap bytes")
                }
                return@withContext body
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("More than $MAX_REDIRECTS redirects")
    }

    /**
     * A body that is not the JSON expected counts as a failed request, and so does one nested
     * deeply enough to overflow `org.json`'s recursive parser.
     */
    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw IOException("An unreadable response: ${e.message}")
    } catch (e: StackOverflowError) {
        throw IOException("An unreadable response: nested too deeply")
    }

    companion object {
        const val USER_AGENT = "StevenPiano/1.3 (https://github.com/stevenjin20090101-rgb/steven-piano-android)"
        const val JSON_CAP = 256 * 1024
        const val IMAGE_CAP = 6 * 1024 * 1024
        private const val TAG = "Wikipedia"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_REDIRECTS = 5
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** The whole stream, or null once it passes [cap] bytes. */
        private fun readCapped(input: InputStream, cap: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) return out.toByteArray()
                if (out.size() + n > cap) return null
                out.write(buffer, 0, n)
            }
        }
    }
}

/**
 * The two responses, read with Android's `org.json`. Kept thin on purpose: on the JVM `org.json`
 * is a stub, so everything above it is tested through a fake [WikiApi] instead.
 */
internal object WikiJson {
    fun summary(text: String): WikiSummary {
        val page = JSONObject(text)
        val original = page.optJSONObject("originalimage")
        val thumbnail = page.optJSONObject("thumbnail")
        return WikiSummary(
            title = page.text("title").orEmpty(),
            type = page.text("type") ?: "standard",
            description = page.text("description"),
            extract = page.text("extract"),
            pageUrl = WikipediaUrls.pageLink(page.optJSONObject("content_urls")?.optJSONObject("desktop")?.text("page")),
            imageUrl = WikipediaUrls.image(original?.text("source"), original?.optInt("width") ?: 0, thumbnail?.text("source")),
        )
    }

    fun searchTitles(text: String): List<String> {
        val hits = JSONObject(text).optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { hits.optJSONObject(it)?.text("title") }
    }

    /** A string field, or null when it is missing, JSON null or blank (optString would say "null" or ""). */
    private fun JSONObject.text(name: String): String? = if (isNull(name)) null else optString(name).ifBlank { null }
}
