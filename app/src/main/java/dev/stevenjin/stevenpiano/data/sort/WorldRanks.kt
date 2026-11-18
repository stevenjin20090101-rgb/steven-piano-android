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
 * title does, over its `title_short` or its `title`:
 *
 * - **A song** ([pick] with classical false): [CoverMatch.trackFits]; of those, the closest title (the same core, then
 *   one holding the other as whole words, then the rest), and among equals the most played (`rank`): the studio track,
 *   not a remix of the same name.
 * - **A classical piece**: a work is recorded many times under many titles, so its popularity is its most played
 *   recording that fits. The result's title must hold the piece's title as [CoverMatch.trackFits] reads them, over all
 *   their words ([workWords]: the piece within a set after " — " counts, "Suite bergamasque — Passepied" is not "Clair de
 *   lune"; "[2]" and a year in brackets do not); and when the title names a catalogue number ([catalogue]: "Op. 28 No.
 *   15", "BWV 846", "K. 331", "D. 960", "S. 141", "WoO 59"), the same work (or one of the set it names: "Op. 10" holds
 *   "Op. 10 No. 4"). Deezer's search wants every word it is given, so a long title ("… in C Major, WTC II, BWV 870")
 *   often finds nothing, and a short one finds the set's best-known piece: a classical piece whose first search fits
 *   nothing has up to two more ([fallbackTerms]), its catalogue number and composer, then all its title's words.
 */
object WorldRankMatch {
    fun term(title: String, artist: String): String = CoverMatch.term(title, artist)

    /**
     * The searches for a classical piece whose first ([term]) fits nothing, in turn: its catalogue number and composer ("op
     * 10 no 4 chopin"), then all its title's words and the composer ("suite bergamasque passepied debussy"); each only when
     * it differs from those before.
     */
    fun fallbackTerms(title: String, artist: String): List<String> {
        val name = CoverMatch.words(artist).joinToString(" ")
        val first = term(title, artist)
        return listOfNotNull(catalogue(title)?.let { "$it $name" }, "${workWords(title).joinToString(" ")} $name")
            .map(String::trim).distinct().filter { it != first }
    }

    fun pick(title: String, artist: String, classical: Boolean, results: List<DeezerTrack>): DeezerTrack? {
        val core = CoverMatch.core(title)
        val names = CoverMatch.words(artist)
        if (core.isEmpty() || names.isEmpty()) return null
        val byArtist = results.filter { artistFits(names, it, classical) }
        if (classical) {
            val work = catalogue(title)
            val words = workWords(title).joinToString(" ")
            return byArtist.filter { track ->
                titles(track).any { name ->
                    CoverMatch.trackFits(words, workWords(name).joinToString(" "), classical = true) &&
                        (work == null || catalogues(name).any { it == work || it.startsWith("$work ") })
                }
            }.maxByOrNull { it.rank }
        }
        return byArtist.mapNotNull { track -> closeness(core, track)?.let { track to it } }
            .maxWithOrNull(compareBy<Pair<DeezerTrack, Int>>({ it.second }, { it.first.rank }))
            ?.first
    }

    /**
     * The first catalogue number [text] names, normalised: "op 28 no 15", "bwv 846", "k 331", "d 960"; null for none.
     * "Opus" is "op", "KV" is "k", "Nr." is "no", "D960" is "d 960", and a year in brackets ("(1903)") is no number.
     */
    fun catalogue(text: String): String? = catalogues(text).firstOrNull()

    /**
     * A classical title's words as the fit reads them: folded, "[2]" (MAESTRO's second performance) and a year in brackets
     * left out, "Opus" as "op", "KV" as "k", "Nr." as "no", "D960" as "d 960".
     */
    fun workWords(text: String): List<String> =
        CoverMatch.words(BRACKETED.replace(YEAR.replace(text, " "), " ")).flatMap { word ->
            JOINED.matchEntire(word)?.let { listOf(it.groupValues[1], it.groupValues[2]) } ?: listOf(word)
        }.map { ALIASES[it] ?: it }

    private fun catalogues(text: String): List<String> {
        val words = workWords(text)
        val found = ArrayList<String>()
        var i = 0
        while (i < words.size - 1) {
            if (words[i] in PREFIXES && words[i + 1].all(Char::isDigit)) {
                val number = if (i + 3 < words.size && words[i + 2] == "no" && words[i + 3].all(Char::isDigit)) {
                    "${words[i]} ${words[i + 1]} no ${words[i + 3]}"
                } else {
                    "${words[i]} ${words[i + 1]}"
                }
                found += number
                i += number.count { it == ' ' } + 1
            } else {
                i++
            }
        }
        return found
    }

    private fun titles(track: DeezerTrack): List<String> = listOfNotNull(track.titleShort, track.title)

    /** 3 for the same core, 2 for one holding the other as whole words, 1 for a fit by shared words, null for none. */
    private fun closeness(core: String, track: DeezerTrack): Int? =
        titles(track).map(CoverMatch::core).filter { it.isNotEmpty() }.maxOfOrNull { other ->
            when {
                other == core -> 3
                " $other " in " $core " || " $core " in " $other " -> 2
                CoverMatch.trackFits(core, other) -> 1
                else -> 0
            }
        }?.takeIf { it > 0 }

    private fun artistFits(names: List<String>, track: DeezerTrack, classical: Boolean): Boolean {
        fun holds(field: String?) = field != null && CoverMatch.words(field).toSet().containsAll(names)
        return holds(track.artist) || (classical && (holds(track.album) || holds(track.title)))
    }

    /** The catalogues' prefixes, as [ALIASES] leaves them. */
    private val PREFIXES = setOf("op", "bwv", "k", "d", "s", "woo", "hwv", "l", "rv", "sz")
    private val ALIASES = mapOf("opus" to "op", "kv" to "k", "nr" to "no", "nos" to "no", "n" to "no")

    /** A prefix and its number written together: "d960", "bwv846", "op28". */
    private val JOINED = Regex("^(op|bwv|kv|k|d|s|woo|hwv|rv)(\\d+)$")

    /** A square bracket's words: MAESTRO's "[2]". */
    private val BRACKETED = Regex("\\[[^\\]]*\\]")

    /** A year in brackets, as piano-midi.de's titles give it: "(1903)". */
    private val YEAR = Regex("\\(\\s*1[5-9]\\d\\d\\s*\\)")
}

/** The Disney section's second rule (M82): an album on a Disney label, "Walt Disney Records", "Disney Music Group"… */
object DisneyLabel {
    fun isDisney(label: String?): Boolean = label != null && "disney" in label.lowercase()
}

/**
 * The world-popularity lookup (M82): every piece due ([Store.candidates]: Classical or Modern, never looked up or renamed
 * since) is searched on Deezer once, through [api] (paced: [PacedDeezer]), and the rank of the track [WorldRankMatch]
 * picks kept with when it ran ([Store.keep]; a classical piece may have two more searches, [WorldRankMatch.fallbackTerms]);
 * null when none fits, or the piece names no artist (nothing is asked then).
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
        val terms = listOf(WorldRankMatch.term(piece.title, artist)) + if (classical) WorldRankMatch.fallbackTerms(piece.title, artist) else emptyList()
        var track: DeezerTrack? = null
        for (term in terms) {
            track = WorldRankMatch.pick(piece.title, artist, classical, api.search(term, RESULTS))
            if (track != null) break
        }
        if (track == null) return null to null
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
