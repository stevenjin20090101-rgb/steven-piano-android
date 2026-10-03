// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.data.TextLimits
import dev.stevenjin.stevenpiano.net.AppleBusyException
import dev.stevenjin.stevenpiano.net.AppleCatalogApi
import dev.stevenjin.stevenpiano.net.AppleUrls
import dev.stevenjin.stevenpiano.net.CatalogTrack
import dev.stevenjin.stevenpiano.net.WikipediaClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

/**
 * [inner] (the lookup's paced catalogue, [PacedAppleCatalog]) with its searches taken one at a time (v1.18 — M48): the
 * album-cover lookup's and the cover picker's wait their turns together, each after [inner]'s pacing, so the two never
 * ask Apple closer together than its 3.5 s. Downloads go as [inner] has them.
 */
class OneSearchAtATime(private val inner: AppleCatalogApi) : AppleCatalogApi {
    private val turn = Mutex()

    override suspend fun search(term: String, limit: Int): List<CatalogTrack> = turn.withLock { inner.search(term, limit) }

    override suspend fun download(url: String, maxBytes: Int): ByteArray? = inner.download(url, maxBytes)
}

/** What the cover picker needs of the artwork store ([ArtworkRepository]): the piece, and keeping or taking away its cover. */
interface PickedCovers {
    /** Whether piece [pieceId] was made here (Studio's, a recording: its cover is drawn from its music); null when there is no such piece. */
    suspend fun madeHere(pieceId: Long): Boolean?

    /**
     * [image] becomes piece [pieceId]'s own cover, its lookup recorded as found with the album's credit ([sourceTitle]
     * "album · artist", the track's page [sourceUrl]) and as chosen by hand. False when it is no image or wasn't kept.
     */
    suspend fun keepChosen(pieceId: Long, image: ByteArray, sourceUrl: String?, sourceTitle: String): Boolean

    /** Piece [pieceId]'s own cover goes, its lookup recorded as not found and chosen by hand. False when it wasn't written. */
    suspend fun takeAway(pieceId: Long): Boolean
}

/** A cover a search found: its place in the search ([index]), its [album] and [artist], its 100 px [picture] of [type]. */
class CoverPick(val index: Int, val album: String, val artist: String, val picture: ByteArray, val type: String)

/** How a search of the cover picker went. */
sealed interface CoverSearch {
    /** Asked: the covers found, none when nothing was, remembered as [searchId] for a choice. */
    data class Found(val searchId: String, val picks: List<CoverPick>) : CoverSearch

    /** Within the picker's floor (one search in 4 s): the next may go in [retryAfterMs]. */
    data class Wait(val retryAfterMs: Long) : CoverSearch

    /** Apple's hour-long stop is on (its 403 or 429): it lifts in [retryAfterMs]. */
    data class Busy(val retryAfterMs: Long) : CoverSearch

    /** Apple's catalogue couldn't be reached, or its answer couldn't be read. */
    data object Unreachable : CoverSearch

    /** The text, trimmed, is not 2 to 80 characters, or holds a control character. */
    data object BadText : CoverSearch
}

/** How a choice or a removal went. */
sealed interface CoverChange {
    data object Done : CoverChange

    /** The piece, the search (one of the last four, ten minutes at most) or the result asked for is not there. */
    data object NotFound : CoverChange

    /** A piece made here (Studio's, a recording): its cover is drawn from its music and never chosen. */
    data object MadeHere : CoverChange

    /** Apple asked to stop while the cover came down: its hour-long stop lifts in [retryAfterMs]. */
    data class Busy(val retryAfterMs: Long) : CoverChange

    /** The cover couldn't be downloaded. */
    data object Unreachable : CoverChange

    /** It is no picture Android can show, or it couldn't be kept (a full disk, the library unreadable). */
    data object Failed : CoverChange
}

/**
 * The web panel's cover picker (v1.18 — M48): Apple's catalogue searched by hand, for the few pieces its lookup can't
 * match by itself, and a cover chosen, or taken away, for a piece. Thread-safe; nothing it holds outlives the process.
 *
 * - [search]: the text trimmed, 2 to 80 characters ([term]), asked once ([AppleCatalogApi.search], 25 results) through
 *   the lookup's own pacing ([OneSearchAtATime]), at most one search in [FLOOR_MS] ([CoverSearch.Wait]). While Apple's
 *   hour-long stop is on nothing is asked ([CoverSearch.Busy]), and Apple's 403 or 429 starts it ([stop]). Of the results,
 *   those with artwork on Apple's image hosts ([CatalogTrack.coverUrl]: [AppleUrls.cover]'s rule), the first of each
 *   album ([albums]), at most [MAX_PICKS], each with its 100 px picture, [AT_ONCE] at a time and [PICTURE_CAP] at most;
 *   one whose picture fails, or is no JPEG or PNG, is left out. They are remembered under a fresh search id for
 *   [KEPT_MS], the last [KEPT_SEARCHES] searches.
 * - [choose]: that result's 600 px cover ([CatalogTrack.coverUrl], [WikipediaClient.IMAGE_CAP] as the lookup's) becomes
 *   the piece's own, credited as a found cover is and chosen by hand ([PickedCovers.keepChosen]).
 * - [remove]: the piece's own cover goes ([PickedCovers.takeAway]).
 *
 * Both refuse a piece made here ([CoverChange.MadeHere]). [now] is a monotonic clock (`elapsedRealtime`); [blockedFor]
 * and [stop] are the lookup's hour-long stop ([CoverFetcher]).
 */
class CoverPicker(
    private val api: AppleCatalogApi,
    private val store: PickedCovers,
    private val now: () -> Long,
    private val blockedFor: () -> Long? = { null },
    private val stop: () -> Unit = {},
    private val newId: () -> String = { freshId() },
) {
    private val lock = Any()

    /** When the last search went, on [now]'s clock; null before the first. */
    private var lastSearchAt: Long? = null

    /** The searches remembered, oldest first. */
    private val searches = LinkedHashMap<String, Remembered>()

    private class Remembered(val at: Long, val tracks: List<CatalogTrack>)

    private class Picture(val bytes: ByteArray, val type: String)

    suspend fun search(text: String): CoverSearch {
        val term = term(text) ?: return CoverSearch.BadText
        blockedFor()?.let { return CoverSearch.Busy(it) }
        waitFor()?.let { return CoverSearch.Wait(it) }
        val found = try {
            api.search(term, RESULTS)
        } catch (e: AppleBusyException) {
            stop()
            return CoverSearch.Busy(blockedFor() ?: CoverFetcher.BLOCK_MS)
        } catch (e: IOException) {
            return CoverSearch.Unreachable
        }
        val albums = albums(found)
        val pictures = pictures(albums)
        val shown = albums.indices.filter { pictures[it] != null }
        val picks = shown.mapIndexed { index, i ->
            val track = albums[i]
            val picture = pictures[i]!!
            CoverPick(index, clip(track.collectionName ?: track.trackName), clip(track.artistName), picture.bytes, picture.type)
        }
        return CoverSearch.Found(remember(shown.map { albums[it] }), picks)
    }

    suspend fun choose(pieceId: Long, searchId: String, index: Int): CoverChange {
        when (store.madeHere(pieceId)) {
            null -> return CoverChange.NotFound
            true -> return CoverChange.MadeHere
            false -> Unit
        }
        val track = remembered(searchId)?.getOrNull(index) ?: return CoverChange.NotFound
        val url = track.coverUrl ?: return CoverChange.NotFound
        val image = try {
            api.download(url, WikipediaClient.IMAGE_CAP)
        } catch (e: AppleBusyException) {
            stop()
            return CoverChange.Busy(blockedFor() ?: CoverFetcher.BLOCK_MS)
        } catch (e: IOException) {
            null
        } ?: return CoverChange.Unreachable
        val kept = store.keepChosen(pieceId, image, AppleUrls.pageLink(track.trackViewUrl), CoverFetcher.credit(track))
        return if (kept) CoverChange.Done else CoverChange.Failed
    }

    suspend fun remove(pieceId: Long): CoverChange = when (store.madeHere(pieceId)) {
        null -> CoverChange.NotFound
        true -> CoverChange.MadeHere
        false -> if (store.takeAway(pieceId)) CoverChange.Done else CoverChange.Failed
    }

    /** Null, and this search counted, when the last one went [FLOOR_MS] or more ago (or there was none); else how long to wait. */
    private fun waitFor(): Long? = synchronized(lock) {
        val at = now()
        val last = lastSearchAt
        if (last != null && at - last in 0 until FLOOR_MS) return FLOOR_MS - (at - last)
        lastSearchAt = at
        null
    }

    /** Each of [tracks]' 100 px pictures, [AT_ONCE] at a time; null for one that failed or is no JPEG or PNG. */
    private suspend fun pictures(tracks: List<CatalogTrack>): List<Picture?> = coroutineScope {
        val gate = Semaphore(AT_ONCE)
        tracks.map { track -> async { gate.withPermit { picture(track) } } }.awaitAll()
    }

    /** [track]'s `artworkUrl100`, only where [AppleUrls.cover]'s rule finds Apple's image host, through the client's own checks. */
    private suspend fun picture(track: CatalogTrack): Picture? {
        val url = track.artworkUrl100?.trim()?.takeIf { AppleUrls.cover(it) != null } ?: return null
        val bytes = try {
            api.download(url, PICTURE_CAP)
        } catch (e: IOException) {   // a 403 or 429 from the image hosts among them: this picture alone is left out
            null
        } ?: return null
        return pictureType(bytes)?.let { Picture(bytes, it) }
    }

    private fun remember(tracks: List<CatalogTrack>): String = synchronized(lock) {
        val at = now()
        forgetOld(at)
        val id = newId()
        searches[id] = Remembered(at, tracks)
        while (searches.size > KEPT_SEARCHES) searches.remove(searches.keys.first())
        id
    }

    private fun remembered(searchId: String): List<CatalogTrack>? = synchronized(lock) {
        forgetOld(now())
        searches[searchId]?.tracks
    }

    private fun forgetOld(at: Long) {
        searches.values.removeAll { at - it.at >= KEPT_MS }
    }

    companion object {
        /** The search's text, trimmed: 2 to 80 characters. */
        const val MIN_TEXT = 2
        const val MAX_TEXT = 80

        /** Results asked of Apple, as the lookup asks. */
        const val RESULTS = CoverFetcher.RESULTS

        /** Covers shown at most, one an album. */
        const val MAX_PICKS = 12

        /** A 100 px picture's bytes at most, and how many come down at once. */
        const val PICTURE_CAP = 64 * 1024
        const val AT_ONCE = 4

        /** One search in this long, whichever panel asks. */
        const val FLOOR_MS = 4_000L

        /** A search is remembered this long, and only the last few. */
        const val KEPT_MS = 10 * 60 * 1000L
        const val KEPT_SEARCHES = 4

        const val JPEG = "image/jpeg"
        const val PNG = "image/png"

        private const val ID_BYTES = 12
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val random = SecureRandom()

        /** What is asked of Apple for [text]: trimmed, 2 to 80 characters with no control character; null otherwise. */
        fun term(text: String): String? = text.trim().takeIf { it.length in MIN_TEXT..MAX_TEXT && it.none(Char::isISOControl) }

        /**
         * Of Apple's [tracks], in its order, those with artwork on its image hosts ([CatalogTrack.coverUrl]), the first of
         * each album (its name and its artist, case aside), at most [MAX_PICKS].
         */
        fun albums(tracks: List<CatalogTrack>): List<CatalogTrack> = tracks.asSequence()
            .filter { it.coverUrl != null }
            .distinctBy { (it.collectionName?.trim()?.lowercase(Locale.ROOT) ?: "") + "\u0000" + it.artistName.trim().lowercase(Locale.ROOT) }
            .take(MAX_PICKS)
            .toList()

        /** [JPEG] or [PNG] by [bytes]' signature; null for anything else. */
        fun pictureType(bytes: ByteArray): String? = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> JPEG
            bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] } -> PNG
            else -> null
        }

        /** A search id: 12 random bytes, URL-safe base64 without padding (16 characters). */
        fun freshId(): String = ByteArray(ID_BYTES).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        private fun clip(name: String): String = TextLimits.clip(name, TextLimits.TITLE)
    }
}
