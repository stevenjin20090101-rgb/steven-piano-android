// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.data.TextKeys
import dev.stevenjin.stevenpiano.net.CatalogTrack

/**
 * Which of Apple's results is a piece's album cover (v1.15 — M40). Pure. A title's core ([core]) is the title folded
 * ([TextKeys.fold]) without its trailing parentheticals and brackets and without a " - …" tail, its words joined by single
 * spaces; a result's track has one too. [pick] takes the first result, in Apple's order, with artwork whose artist and
 * track both fit:
 *
 * - **The artist fits** when every word of the piece's artist (the name rows show: an artist's whole name, a composer's
 *   surname) is a word of the result's `artistName`, or, for a composer, of its album's or its track's name ("Bach: The
 *   Well-Tempered Clavier", performed by Glenn Gould).
 * - **The track fits** when the two cores are equal, or one holds the other as whole words, or at least 70 % of the title
 *   core's words of three letters or digits or more are in the track's core ("prelude", "fugue", "major", "bwv", "846" in
 *   "Prelude & Fugue No. 1 in C Major, BWV 846: I. Prelude"; a word may sit inside a longer one: "nocturne" in "nocturnes").
 *
 * The search is "<title core> <artist>" ([term]).
 */
object CoverMatch {
    /** Words this long or longer count for the share of a title a track must hold. */
    private const val LONG_WORD = 3

    /** That share, in tenths: 7 of 10. */
    private const val SHARE_TENTHS = 7

    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** A parenthetical or a bracket at the very end ("(Piano Version)", "[Live]"), with the spaces before it. */
    private val TRAILING_GROUP = Regex("\\s*[(\\[][^()\\[\\]]*[)\\]]\\s*$")

    /** Where a " - …" tail starts: a hyphen, an en or an em dash with spaces on both sides. */
    private val DASH_TAIL = Regex("\\s+[-–—]\\s+")

    /** [title]'s core: folded, without its trailing parentheticals and brackets and its " - …" tail, its words single-spaced. */
    fun core(title: String): String {
        var text = TextKeys.fold(title).trim()
        while (true) {
            val cut = DASH_TAIL.find(text)?.let { text.substring(0, it.range.first) } ?: text
            val bare = TRAILING_GROUP.replace(cut, "").trim()
            if (bare == text) break
            text = bare
        }
        return words(text).joinToString(" ")
    }

    /** [text]'s words, folded: runs of letters and digits ("Don’t" is "don", "t", as "Don't" is). */
    fun words(text: String): List<String> = TextKeys.fold(text).split(NOT_WORD).filter { it.isNotEmpty() }

    /** What Apple is asked: "<title core> <artist>", the artist's words folded. */
    fun term(title: String, artist: String): String =
        listOf(core(title), words(artist).joinToString(" ")).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * The first of [results] with artwork whose artist and track fit [title] by [artist] ([classical]: a composer, whom the
     * album or the track may name instead); null when none does, or when the title has no core or the artist no word.
     */
    fun pick(title: String, artist: String, classical: Boolean, results: List<CatalogTrack>): CatalogTrack? {
        val core = core(title)
        val names = words(artist)
        if (core.isEmpty() || names.isEmpty()) return null
        return results.firstOrNull { it.coverUrl != null && artistFits(names, it, classical) && trackFits(core, core(it.trackName)) }
    }

    /** Whether the track's core [track] fits the title's core [title] (both [core]s). */
    fun trackFits(title: String, track: String): Boolean {
        if (title.isEmpty() || track.isEmpty()) return false
        if (title == track || " $track " in " $title " || " $title " in " $track ") return true
        val long = title.split(' ').filter { it.length >= LONG_WORD }
        return long.isNotEmpty() && long.count { it in track } * 10 >= long.size * SHARE_TENTHS
    }

    /** Whether every one of [names] is a word of the result's artist, or, for a composer, of its album's or its track's name. */
    private fun artistFits(names: List<String>, track: CatalogTrack, classical: Boolean): Boolean {
        fun holds(field: String?) = field != null && words(field).toSet().containsAll(names)
        return holds(track.artistName) || (classical && (holds(track.collectionName) || holds(track.trackName)))
    }
}
