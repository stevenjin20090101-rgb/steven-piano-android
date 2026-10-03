// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.net.AppleBusyException
import dev.stevenjin.stevenpiano.net.AppleCatalogApi
import dev.stevenjin.stevenpiano.net.AppleUrls
import dev.stevenjin.stevenpiano.net.CatalogTrack
import dev.stevenjin.stevenpiano.net.WikipediaClient

/** [inner] with its searches paced by [searches] and its downloads by [images] (v1.15 — M40). */
class PacedAppleCatalog(private val inner: AppleCatalogApi, private val searches: RequestPacer, private val images: RequestPacer) : AppleCatalogApi {
    override suspend fun search(term: String, limit: Int) = searches.await().let { inner.search(term, limit) }

    override suspend fun download(url: String, maxBytes: Int) = images.await().let { inner.download(url, maxBytes) }
}

/** Where album covers are kept, and what says whether to look ([ArtworkRepository]: the settings and the artwork table). */
interface CoverStore {
    /** Whether covers may be looked up now: Fetch artwork automatically and Album covers both on. */
    suspend fun wanted(): Boolean

    /** Whether piece [pieceId] has a cover of its own (drawn, chosen by hand, found before): it is never looked up. */
    suspend fun hasCover(pieceId: Long): Boolean

    /**
     * Keeps [image] as piece [pieceId]'s cover with the lookup's record (the album's [sourceUrl] and [sourceTitle]), in one
     * step: [Fetched.Saved]; [Fetched.NotFound] when it is no image Android can show; [Fetched.Skipped] when the piece has
     * a cover of its own by now (one chosen meanwhile is never replaced); [Fetched.Failed] when it could not be kept.
     */
    suspend fun keep(pieceId: Long, image: ByteArray, sourceUrl: String?, sourceTitle: String): Fetched
}

/**
 * Album covers from Apple's iTunes Search API (v1.15 — M40), through [api] (searches 3.5 s apart, images 1 s apart:
 * [PacedAppleCatalog]). A piece is searched for as "<title core> <artist>" ([CoverMatch.term]); [CoverMatch.pick] takes
 * the first result whose artist and track clearly fit (else, v1.17 — M45, an album the artist names, or the work's own
 * album by the artist), and its artwork at 600 px becomes the piece's own cover, with the
 * album's credit on the lookup's row ([CoverStore.keep]). Nothing is looked up while covers are off, for a piece with a
 * cover of its own, nor for one without an artist or with a name that is no person's ("Traditional", "Made in Studio":
 * [ArtworkFetcher.pageName]). Apple answering 403 or 429 is recorded as a failure (retried a day later, [ArtworkPolicy])
 * and stops every lookup for an hour ([BLOCK_MS], on [now]'s clock): meanwhile a cover is left for later
 * ([Fetched.Busy]), so the worker ends the run's covers.
 */
class CoverFetcher(private val api: AppleCatalogApi, private val store: CoverStore, private val now: () -> Long) {
    @Volatile private var blockedUntil: Long? = null

    /** How much longer lookups wait after Apple's 403 or 429, in ms; null when they may go. */
    fun blockedFor(): Long? {
        val until = blockedUntil ?: return null
        val left = until - now()
        if (left > 0) return left
        blockedUntil = null
        return null
    }

    suspend fun fetch(key: ArtKey.Cover): Fetched {
        if (!store.wanted()) return Fetched.Skipped
        blockedFor()?.let { return Fetched.Busy(it) }
        if (store.hasCover(key.id)) return Fetched.Skipped
        val artist = ArtworkFetcher.pageName(key.artist) ?: return Fetched.NotFound
        if (CoverMatch.core(key.title).isEmpty()) return Fetched.NotFound
        return try {
            val results = api.search(CoverMatch.term(key.title, artist), RESULTS)
            val track = CoverMatch.pick(key.title, artist, key.classical, results) ?: return Fetched.NotFound
            val url = track.coverUrl ?: return Fetched.NotFound
            val image = api.download(url, WikipediaClient.IMAGE_CAP) ?: return Fetched.NotFound
            store.keep(key.id, image, AppleUrls.pageLink(track.trackViewUrl), credit(track))
        } catch (e: AppleBusyException) {
            blockedUntil = now() + BLOCK_MS
            Fetched.Failed("Apple asked to stop (HTTP ${e.code})")
        }
    }

    companion object {
        /** Results asked for each search (v1.17 — M45: 25, was 10; well within the 256 KB the JSON may be). */
        const val RESULTS = 25

        /** Apple answers about 20 searches a minute: one every 3.5 s. */
        const val SEARCH_GAP_MS = 3_500L

        /** Its images come from its CDN: one a second. */
        const val IMAGE_GAP_MS = 1_000L

        /** How long every lookup waits after Apple's 403 or 429. */
        const val BLOCK_MS = 60 * 60 * 1000L

        /** The lookup row's credit: "album · artist" (the artist alone when Apple names no album). */
        fun credit(track: CatalogTrack): String = listOfNotNull(track.collectionName, track.artistName).joinToString(" · ")
    }
}
