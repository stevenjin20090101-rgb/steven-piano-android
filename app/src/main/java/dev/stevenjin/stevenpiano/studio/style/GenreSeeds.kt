// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.studio.style

import dev.stevenjin.stevenpiano.data.TextKeys
import dev.stevenjin.stevenpiano.studio.compose.Genre

/** A playlist as Studio's genres read it (v1.37 — M74): its [name] and its pieces' [ids]. */
data class PlaylistPool(val name: String, val ids: List<Long>)

/**
 * Where a genre piece's seed comes from (v1.37 — M74): Steven's own songs of that genre, the library's pieces in
 * the playlists named for it by Apple Music's genre names, matched ignoring case and punctuation ("House & Dance"
 * as "housedance", "K-Pop" as "kpop"). A seed is a song, not a fragment: at least [MIN_MS] long (Studio's own
 * pieces are never seeds, [StyleLibrary] leaves them out). With none there, any Modern piece; with no Modern piece,
 * the default seed.
 */
object GenreSeeds {
    const val MIN_MS = 30_000L

    /** A genre pool's [SeedAsk.Pool.source]: `genre:<key>`. */
    private const val SOURCE = "genre:"

    val playlists: Map<Genre, List<String>> = mapOf(
        Genre.LoFi to listOf("Lo-Fi", "Lofi", "Chill", "Hip-Hop/Rap", "Jazz", "R&B/Soul"),
        Genre.House to listOf("House & Dance", "House", "Dance", "Electronic", "Electronica"),
        Genre.Chill to listOf("Chill", "Ambient", "New Age", "Electronic", "Lo-Fi"),
        Genre.Cinematic to listOf("Soundtrack", "Pop", "Singer/Songwriter", "K-pop", "K-Pop", "Classical Crossover"),
    )

    private val keys: Map<Genre, Set<String>> = playlists.mapValues { (_, names) -> names.mapTo(HashSet(), ::nameKey) }

    /** A playlist's name as a genre matches it: folded, its letters and digits only. */
    fun nameKey(name: String): String = TextKeys.fold(name).filter(Char::isLetterOrDigit)

    /** Whether playlist [name] holds [genre]'s songs. */
    fun holds(genre: Genre, name: String): Boolean = nameKey(name) in keys.getValue(genre)

    /** Whether playlist [name] holds any genre's songs (only those are read). */
    fun named(name: String): Boolean = nameKey(name).let { key -> keys.values.any { key in it } }

    fun source(genre: Genre): String = SOURCE + genre.key

    /** The genre whose songs [seed] is drawn from, or null for any other seed. */
    fun of(seed: SeedAsk): Genre? = (seed as? SeedAsk.Pool)?.source?.takeIf { it.startsWith(SOURCE) }?.let { Genre.of(it.removePrefix(SOURCE)) }
}
