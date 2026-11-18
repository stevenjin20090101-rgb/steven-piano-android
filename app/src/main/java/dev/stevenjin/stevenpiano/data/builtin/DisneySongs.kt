// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.builtin

import android.content.Context
import dev.stevenjin.stevenpiano.data.art.CoverMatch
import dev.stevenjin.stevenpiano.data.db.PieceEntity
import org.json.JSONArray
import org.json.JSONObject

/**
 * One rule of the Disney section's list (M82): the composer or artist holds one of [names] as words in a row, or is
 * exactly one of [artists] (a film's name); and the title, as words, holds [title]. A rule without names or artists
 * stands for any composer; one without a title for any title.
 */
class DisneyMatcher(val names: List<String>, val artists: Set<String>, val title: Regex?) {
    /** [composer] and [title] as [DisneySongs.words] gives them. */
    fun accepts(composer: String, title: String): Boolean {
        val byName = (names.isEmpty() && artists.isEmpty()) || composer in artists || names.any { " $it " in " $composer " }
        return byName && (this.title == null || this.title.containsMatchIn(title))
    }
}

/**
 * Which songs the Library's Disney section lists (M82, Steven: "every Disney song"): a song is Disney when its album, as
 * Deezer matched it, is on a Disney label (`PieceEntity.disneyLabel`, the world-popularity lookup's), or when the list the
 * app ships says so (`assets/disney_songs.json`: Disney's composers, the films' names as artists, and well-known Disney
 * and Pixar song titles; its format is in its "about" line). Pure once read.
 */
class DisneySongs(private val matchers: List<DisneyMatcher>) {
    fun isDisney(piece: PieceEntity): Boolean = piece.disneyLabel == true || listed(piece.composer.ifBlank { piece.composerShort }, piece.title)

    /** Whether the list names [title] by [composer]. */
    fun listed(composer: String, title: String): Boolean {
        val who = words(composer)
        val what = words(title)
        return matchers.any { it.accepts(who, what) }
    }

    companion object {
        const val ASSET = "disney_songs.json"

        /** None: before the list is read, and in tests that need none. */
        val None = DisneySongs(emptyList())

        /** Reads the asset. Called off the main thread. */
        fun load(context: Context): DisneySongs = context.assets.open(ASSET).bufferedReader().use { parse(it.readText()) }

        /** The list in [json]. A malformed one throws: it ships with the app, and its test reads it. */
        fun parse(json: String): DisneySongs {
            val matchers = JSONObject(json).getJSONArray("matchers")
            return DisneySongs(
                (0 until matchers.length()).map { i ->
                    val matcher = matchers.getJSONObject(i)
                    DisneyMatcher(
                        names = strings(matcher.optJSONArray("names")).map(::words),
                        artists = strings(matcher.optJSONArray("artists")).map(::words).toSet(),
                        title = if (matcher.has("title")) Regex(matcher.getString("title"), RegexOption.IGNORE_CASE) else null,
                    )
                },
            )
        }

        /** [text] folded, as words joined by single spaces: what the rules are written against. */
        fun words(text: String): String = CoverMatch.words(text).joinToString(" ")

        private fun strings(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
    }
}
