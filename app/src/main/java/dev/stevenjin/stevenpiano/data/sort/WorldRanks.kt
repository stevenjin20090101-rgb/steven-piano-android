// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.sort

import dev.stevenjin.stevenpiano.data.Genres
import dev.stevenjin.stevenpiano.data.art.ArtworkFetcher
import dev.stevenjin.stevenpiano.data.art.CoverMatch
import dev.stevenjin.stevenpiano.data.art.RequestPacer
import dev.stevenjin.stevenpiano.data.db.RankCandidate
import dev.stevenjin.stevenpiano.net.DeezerApi
import dev.stevenjin.stevenpiano.net.DeezerTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/** [inner] with every request, searches and albums alike, paced by [pacer] (M82: one every [WorldRanks.GAP_MS]). */
class PacedDeezer(private val inner: DeezerApi, private val pacer: RequestPacer) : DeezerApi {
    override suspend fun search(term: String, limit: Int) = pacer.await().let { inner.search(term, limit) }

    override suspend fun albumLabel(id: Long) = pacer.await().let { inner.albumLabel(id) }
}

/**
 * Which of Deezer's results is a piece (M82). Pure. The search is "<title core> <artist>" ([term], as the album-cover
 * lookup's: `CoverMatch.term`). A result fits when its artist does (every word of the name rows show is a word of its
 * artist, or for a composer of its album's or its title's: Deezer names the pianist on most classical records) and its
 * title does ([CoverMatch.trackFits], over its `title_short` or its `title`). Of those, [pick] takes the closest title:
 * the same core, then one holding the other as whole words, then the rest; among equals, the most played (`rank`).
 */
object WorldRankMatch {
    fun term(title: String, artist: String): String = CoverMatch.term(title, artist)

    fun pick(title: String, artist: String, classical: Boolean, results: List<DeezerTrack>): DeezerTrack? {
        val core = CoverMatch.core(title)
        val names = CoverMatch.words(artist)
        if (core.isEmpty() || names.isEmpty()) return null
        return results.filter { artistFits(names, it, classical) }
            .mapNotNull { track -> closeness(core, track, classical)?.let { track to it } }
            .maxWithOrNull(compareBy<Pair<DeezerTrack, Int>>({ it.second }, { it.first.rank }))
            ?.first
    }

    /** 3 for the same core, 2 for one holding the other as whole words, 1 for a fit by shared words, null for none. */
    private fun closeness(core: String, track: DeezerTrack, classical: Boolean): Int? =
        listOfNotNull(track.titleShort, track.title).map(CoverMatch::core).filter { it.isNotEmpty() }.maxOfOrNull { other ->
            when {
                other == core -> 3
                " $other " in " $core " || " $core " in " $other " -> 2
                CoverMatch.trackFits(core, other, classical) -> 1
                else -> 0
            }
        }?.takeIf { it > 0 }

    private fun artistFits(names: List<String>, track: DeezerTrack, classical: Boolean): Boolean {
        fun holds(field: String?) = field != null && CoverMatch.words(field).toSet().containsAll(names)
        return holds(track.artist) || (classical && (holds(track.album) || holds(track.title)))
    }
}

/** The Disney section's second rule (M82): an album on a Disney label, "Walt Disney Records", "Disney Music Group"… */
object DisneyLabel {
    fun isDisney(label: String?): Boolean = label != null && "disney" in label.lowercase()
}

/**
 * The world-popularity lookup (M82): every piece due ([Store.candidates]: Classical or Modern, never looked up or renamed
 * since) is searched on Deezer once, through [api] (paced: [PacedDeezer]), and the rank of the track [WorldRankMatch]
 * picks kept with when it ran ([Store.keep]); null when none fits, or the piece names no artist (nothing is asked then).
 * A Modern song that matched has its album asked too, for the Disney section ([DisneyLabel]). Offline ([online] false)
 * the run ends and nothing is recorded; a failed request is never recorded either: it waits [FIRST_BACKOFF_MS], twice as
 * long each time after, and the run ends after [MAX_FAILURES] in a row, the rest left for the next. Resumable: what was
 * kept is never asked again.
 */
class WorldRanks(
    private val api: DeezerApi,
    private val store: Store,
    private val online: () -> Boolean,
    private val clock: () -> Long,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    /** Where the lookups' answers are kept: the library. */
    interface Store {
        /** The pieces due after [after], by id, at most [limit]. */
        suspend fun candidates(after: Long, limit: Int): List<RankCandidate>

        /** Keeps the answer for piece [id], unless it was renamed from [key] meanwhile. */
        suspend fun keep(id: Long, rank: Int?, disney: Boolean?, at: Long, key: String)
    }

    /** One pass over every piece due. Returns how many answers were kept. */
    suspend fun run(): Int {
        var after = 0L
        var kept = 0
        var failures = 0
        var backoff = FIRST_BACKOFF_MS
        while (true) {
            val batch = store.candidates(after, BATCH)
            if (batch.isEmpty()) break
            for (piece in batch) {
                while (true) {
                    if (!online()) {
                        log("Offline: the world-popularity lookups wait ($kept kept this run)")
                        return kept
                    }
                    try {
                        val (rank, disney) = lookUp(piece)
                        store.keep(piece.id, rank, disney, clock(), piece.rankKey)
                        kept++
                        failures = 0
                        backoff = FIRST_BACKOFF_MS
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        failures++
                        if (failures >= MAX_FAILURES) {
                            log("World popularity: $failures failures in a row (${e.message}); the rest wait for the next run")
                            return kept
                        }
                        log("World popularity: ${e.message}; asking again in ${backoff / 1000} s")
                        pause(backoff)
                        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
                }
                after = piece.id
            }
        }
        if (kept > 0) log("World popularity: $kept pieces looked up")
        return kept
    }

    /** Piece [piece]'s rank and, for a Modern song that matched, whether its album is on a Disney label. */
    private suspend fun lookUp(piece: RankCandidate): Pair<Int?, Boolean?> {
        val artist = ArtworkFetcher.pageName(piece.composerShort)
        if (artist == null || CoverMatch.core(piece.title).isEmpty()) return null to null
        val classical = piece.genre == Genres.CLASSICAL
        val results = api.search(WorldRankMatch.term(piece.title, artist), RESULTS)
        val track = WorldRankMatch.pick(piece.title, artist, classical, results) ?: return null to null
        val disney = if (classical) null else track.albumId?.let { DisneyLabel.isDisney(api.albumLabel(it)) }
        return track.rank to disney
    }

    companion object {
        /** Results asked for each search. */
        const val RESULTS = 25

        /** Between requests: two a second, a fifth of Deezer's 50 every 5 s. */
        const val GAP_MS = 500L

        const val FIRST_BACKOFF_MS = 5_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        const val MAX_FAILURES = 6

        private const val BATCH = 50
    }
}
