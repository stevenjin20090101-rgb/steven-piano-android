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
import org.json.JSONArray
import org.json.JSONObject

/**
 * The built-in playlists as the app ships them: `assets/builtin_playlists.json` (the format is in
 * its "about" line and BUILD_SPEC.md › v1.5 — M17): lists in their order, each a key, a name and
 * its pieces as matchers (`composer`: one composerKey or a list of them; `title`: a pattern for
 * the folded title, found case-insensitively; `collection`: optional, exact).
 */
object BuiltInCatalogue {
    const val ASSET = "builtin_playlists.json"

    /** Reads the asset. Called off the main thread. */
    fun load(context: Context): BuiltInPlaylists =
        BuiltInPlaylists(context.assets.open(ASSET).bufferedReader().use { parse(it.readText()) })

    /** The lists in [json]. A malformed catalogue throws: it ships with the app, and its test reads it. */
    fun parse(json: String): List<BuiltInList> {
        val lists = JSONObject(json).getJSONArray("lists")
        return (0 until lists.length()).map { i ->
            val list = lists.getJSONObject(i)
            val pieces = list.getJSONArray("pieces")
            BuiltInList(
                key = list.getString("key"),
                name = list.getString("name"),
                matchers = (0 until pieces.length()).map { matcher(pieces.getJSONObject(it)) },
            )
        }
    }

    private fun matcher(json: JSONObject): Matcher = Matcher(
        composers = when (val composer = json.get("composer")) {
            is JSONArray -> (0 until composer.length()).map { composer.getString(it) }.toSet()
            else -> setOf(composer.toString())
        },
        title = Regex(json.getString("title"), RegexOption.IGNORE_CASE),
        collection = if (json.has("collection")) json.getString("collection") else null,
    )
}
