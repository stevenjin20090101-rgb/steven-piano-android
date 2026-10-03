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
 * Which of Apple's results is a piece's album cover (v1.15 — M40; a little wider in v1.17 — M45). Pure. A title's core
 * ([core]) is the title folded ([TextKeys.fold]) without its trailing parentheticals and brackets and without a " - …"
 * tail, its words joined by single spaces; a result's track has one too. [pick] takes the first result, in Apple's
 * order, with artwork whose artist and track both fit:
 *
 * - **The artist fits** when every word of the piece's artist (the name rows show: an artist's whole name, a composer's
 *   surname) is a word of the result's `artistName`, or, for a composer, of its album's or its track's name ("Bach: The
 *   Well-Tempered Clavier", performed by Glenn Gould).
 * - **The track fits** when the two cores are equal, or equal with their spaces removed and four characters or more
 *   ("S.T.A.Y." is "Stay"), or one holds the other as whole words, or at least 70 % of the title core's words of three
 *   letters or digits or more are in the track's core ("prelude", "fugue", "major", "bwv", "846" in "Prelude & Fugue
 *   No. 1 in C Major, BWV 846: I. Prelude"; a word may sit inside a longer one: "nocturne" in "nocturnes"). For a
 *   composer the title's movement words ("1st", "movement" …) do not count towards the 70 %.
 *
 * When none fits, a second look over the same results: first one whose album is named by the piece's artist alone and
 * whose track fits ("Stay" by "Interstellar": Hans Zimmer's "S.T.A.Y." on "Interstellar (Original Motion Picture
 * Soundtrack)"); else one by the artist on the work's own album, whose name holds every distinctive word of the title
 * ("Skyrim Theme" by Jeremy Soule: any of his tracks on "The Elder Scrolls V: Skyrim"). A name met only in a trailing
 * parenthetical or bracket (a "feat." credit) counts for neither: [core] drops those.
 *
 * The search is "<title core> <artist>" ([term]).
 */
object CoverMatch {
    /** Words this long or longer count for the share of a title a track must hold. */
    private const val LONG_WORD = 3

    /** That share, in tenths: 7 of 10. */
    private const val SHARE_TENTHS = 7

    /** Two cores that are equal with their spaces removed fit when that is this long or longer. */
    private const val JOINED_MIN = 4

    /** A composer's movement words, which do not count towards the share. */
    private val MOVEMENT_WORDS = setOf("1st", "2nd", "3rd", "4th", "5th", "first", "second", "third", "fourth", "fifth", "movement", "mvt", "mov")

    /** Words that say nothing of which work a title is: never what finds the work's album. */
    private val PLAIN_WORDS = setOf("theme", "main", "title", "song", "opening", "ending", "ost", "soundtrack", "from", "the", "of", "a", "an", "and", "in", "to", "for")

    /** Of the title's distinctive words, one at least this long, for the work's album to be looked for. */
    private const val DISTINCTIVE_MIN = 4

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
     * album or the track may name instead); when none does, the first whose album the artist names, then the first by the
     * artist on the work's album (v1.17 — M45). Null when none of these, or when the title has no core or the artist no word.
     */
    fun pick(title: String, artist: String, classical: Boolean, results: List<CatalogTrack>): CatalogTrack? {
        val core = core(title)
        val names = words(artist)
        if (core.isEmpty() || names.isEmpty()) return null
        val pictured = results.filter { it.coverUrl != null }
        return pictured.firstOrNull { artistFits(names, it, classical) && trackFits(core, core(it.trackName), classical) }
            ?: pictured.firstOrNull { artistNamesAlbum(names, it) && trackFits(core, core(it.trackName), classical) }
            ?: pictured.firstOrNull { worksAlbum(core, names, it) }
    }

    /** Whether the track's core [track] fits the title's core [title] (both [core]s); [classical]: movement words aside. */
    fun trackFits(title: String, track: String, classical: Boolean = false): Boolean {
        if (title.isEmpty() || track.isEmpty()) return false
        if (title == track || " $track " in " $title " || " $title " in " $track ") return true
        val joined = title.replace(" ", "")
        if (joined.length >= JOINED_MIN && joined == track.replace(" ", "")) return true
        val long = title.split(' ').filter { it.length >= LONG_WORD && !(classical && it in MOVEMENT_WORDS) }
        return long.isNotEmpty() && long.count { it in track } * 10 >= long.size * SHARE_TENTHS
    }

    /** Whether the result's album, as a core, is the artist's words and nothing else ("Interstellar" for "Interstellar"). */
    private fun artistNamesAlbum(names: List<String>, track: CatalogTrack): Boolean =
        track.collectionName?.let { core(it) } == names.joinToString(" ")

    /**
     * Whether the result is by the artist (its `artistName`'s core) on the work's own album: its core holds, as whole
     * words, every word of the title's core that is not a [PLAIN_WORDS] one, one of them [DISTINCTIVE_MIN] long or longer.
     */
    private fun worksAlbum(title: String, names: List<String>, track: CatalogTrack): Boolean {
        val distinctive = title.split(' ').filter { it !in PLAIN_WORDS }
        if (distinctive.none { it.length >= DISTINCTIVE_MIN }) return false
        val album = track.collectionName?.let { core(it).split(' ').toSet() } ?: return false
        return album.containsAll(distinctive) && core(track.artistName).split(' ').toSet().containsAll(names)
    }

    /** Whether every one of [names] is a word of the result's artist, or, for a composer, of its album's or its track's name. */
    private fun artistFits(names: List<String>, track: CatalogTrack, classical: Boolean): Boolean {
        fun holds(field: String?) = field != null && words(field).toSet().containsAll(names)
        return holds(track.artistName) || (classical && (holds(track.collectionName) || holds(track.trackName)))
    }
}
