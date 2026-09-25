// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.net

import java.net.URLEncoder
import java.util.Locale

/**
 * Where the app's requests go, built without Android so it is unit-tested. The app talks to two
 * hosts and no others: [API_HOST] for page summaries and search, [IMAGE_HOST] for portraits.
 * What goes out is only ever a page title or a search made from the library's own composer names
 * and piece titles; nothing about the person is in any URL.
 */
object WikipediaUrls {
    const val API_HOST = "en.wikipedia.org"
    const val IMAGE_HOST = "upload.wikimedia.org"

    /** Every host the app may reach, redirects included. */
    val HOSTS: Set<String> = setOf(API_HOST, IMAGE_HOST)

    /** The widest image the app downloads. */
    const val MAX_IMAGE_WIDTH = 1024

    /**
     * The only widths Wikimedia renders thumbnails at; any other gets HTTP 400 ("Use thumbnail
     * sizes listed on https://w.wiki/GHai", measured September 2026). 960 is the widest one within
     * [MAX_IMAGE_WIDTH].
     */
    val THUMBNAIL_STEPS: IntArray = intArrayOf(20, 40, 60, 120, 250, 330, 500, 960, 1280, 1920, 3840)

    /** Hosts the summary API hands out images on; both serve the same files as [IMAGE_HOST]. */
    private val IMAGE_HOSTS = setOf(IMAGE_HOST, "thumb.wikimedia.org")
    private val RASTER = setOf("jpg", "jpeg", "png", "gif", "webp")
    private val SIZE_PREFIX = Regex("(\\d+)px-")

    /** A page title in a REST path: spaces become underscores, then everything else is percent-encoded as UTF-8. */
    fun encodeTitle(title: String): String = encode(title.trim().replace(' ', '_'))

    /** The REST summary of the page called [title]: its type, extract, link and images. */
    fun summary(title: String): String = "https://$API_HOST/api/rest_v1/page/summary/${encodeTitle(title)}"

    /** A full-text search of articles: the best three titles for [query]. */
    fun search(query: String): String =
        "https://$API_HOST/w/api.php?action=query&list=search&srsearch=${encode(query.trim())}" +
            "&srlimit=3&srnamespace=0&format=json&formatversion=2"

    /**
     * The image to download for a page, from its summary's original image ([originalUrl],
     * [originalWidth] px wide) and thumbnail ([thumbnailUrl]): the original when it is at most
     * [MAX_IMAGE_WIDTH] wide and a format Android decodes; otherwise the thumbnail rewritten to the
     * widest standard step within that limit (960 px, or less for a narrower original, which
     * Wikimedia never enlarges; drawings scale to any step). Always on [IMAGE_HOST] over HTTPS,
     * without the tracking query the API appends. Null when the page has no usable image.
     */
    fun image(originalUrl: String?, originalWidth: Int, thumbnailUrl: String?): String? {
        val original = originalUrl?.let(::cleanImage)
        if (original != null && originalWidth in 1..MAX_IMAGE_WIDTH && extensionOf(original) in RASTER) return original
        val thumbnail = thumbnailUrl?.let(::cleanImage) ?: return null
        val scalable = original != null && extensionOf(original) == "svg"
        val limit = if (originalWidth > 0 && !scalable) minOf(originalWidth, MAX_IMAGE_WIDTH) else MAX_IMAGE_WIDTH
        val step = THUMBNAIL_STEPS.lastOrNull { it <= limit } ?: return thumbnail
        val cut = thumbnail.lastIndexOf('/') + 1
        val name = thumbnail.substring(cut)
        val size = SIZE_PREFIX.find(name) ?: return thumbnail
        return thumbnail.substring(0, cut) + name.replaceRange(size.groups[1]!!.range, step.toString())
    }

    /** Whether the app may request [url]: HTTPS to one of [HOSTS]. */
    fun allowed(url: String): Boolean = url.startsWith("https://") && hostOf(url) in HOSTS

    /** The host of an absolute URL, lower-cased; "" when there is none. */
    fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT)

    /** A Retry-After header in milliseconds; null when missing or given as a date. */
    fun retryAfterMillis(header: String?): Long? {
        val seconds = header?.trim()?.toLongOrNull() ?: return null
        return if (seconds < 0) null else seconds * 1000
    }

    /** [url] on [IMAGE_HOST] over HTTPS, without query or fragment; null for any other host. */
    private fun cleanImage(url: String): String? {
        var u = url.trim().substringBefore('#').substringBefore('?')
        if (u.startsWith("//")) u = "https:$u"
        val rest = when {
            u.startsWith("https://") -> u.removePrefix("https://")
            u.startsWith("http://") -> u.removePrefix("http://")
            else -> return null
        }
        val host = rest.substringBefore('/').lowercase(Locale.ROOT)
        if (host !in IMAGE_HOSTS) return null
        val path = rest.substring(rest.indexOf('/').takeIf { it >= 0 } ?: rest.length)
        if (path.length <= 1) return null
        return "https://$IMAGE_HOST$path"
    }

    private fun extensionOf(url: String): String = url.substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT)

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
