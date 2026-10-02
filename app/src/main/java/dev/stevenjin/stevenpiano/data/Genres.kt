// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data

import dev.stevenjin.stevenpiano.data.builtin.StudioPlaylist
import dev.stevenjin.stevenpiano.data.imports.ComposerNames

/** What the Library shows (v1.14 — M37): every piece, or one genre's ([genre]: null for all). */
enum class LibraryScope(val genre: Int?) { All(null), Classical(Genres.CLASSICAL), Modern(Genres.MODERN) }

/**
 * Classical and Modern (v1.14 — M37): a piece's genre (`pieces.genre`: [NONE], [CLASSICAL], [MODERN]) and the one
 * rule that sorts a piece ([of]), which imports follow and the upgrade from schema 4 runs in SQL from these same
 * lists (`SchemaV5`). Pure.
 */
object Genres {
    const val NONE = 0
    const val CLASSICAL = 1
    const val MODERN = 2

    /** The library pack's three collections: their pieces are Classical (it is what sorts Mutopia's, which name no composer). */
    val PACK_COLLECTIONS: Set<String> = setOf("maestro-classical-performances", "piano-midi.de", "mutopia-public-domain")

    /**
     * The classical composers the app knows, by key: the canonical ones ([ComposerNames.CANONICAL_KEYS]), the ones the
     * channels name that are not canonical, MacDowell (the built-in lists), and the library pack's other composers.
     * Not "anonymous", not the blank key.
     */
    val CLASSICAL_KEYS: Set<String> = ComposerNames.CANONICAL_KEYS + setOf(
        "field", "couperin", "telemann", "smetana", "falla", "bach cpe",
        "macdowell",
        "abt", "adam", "adams", "andre", "ascher", "enescu", "fischer", "gibbons", "gounod", "grainger", "kreisler",
        "medtner", "paganini", "rimsky-korsakov", "soler", "strauss", "verdi", "wagner", "weber",
    )

    /** Made on the tablet (Made in Studio, Recorded live): no genre. */
    fun madeHere(composerKey: String): Boolean = composerKey == ComposerNames.STUDIO_KEY || composerKey == ComposerNames.RECORDED_LIVE_KEY

    /**
     * What the rule says without the library's artists: [NONE] for a piece made here, [CLASSICAL] for a classical
     * composer the app knows ([CLASSICAL_KEYS], or a canonical surname followed by 1–3 letters of initials: "bach cpe",
     * "bach jc"; "adam levine" is no such thing) or a piece of the pack's collections; null when only the default applies.
     */
    fun strong(composerKey: String, collection: String?): Int? = when {
        madeHere(composerKey) -> NONE
        composerKey in CLASSICAL_KEYS || initialled(composerKey) -> CLASSICAL
        collection != null && collection in PACK_COLLECTIONS -> CLASSICAL
        else -> null
    }

    /** The larger of the two counts' genre; null on a tie (none of either too). */
    fun majority(classical: Int, modern: Int): Int? = when {
        classical > modern -> CLASSICAL
        modern > classical -> MODERN
        else -> null
    }

    /**
     * A piece's genre, in this order: made here, none; an artist (never the blank key) the library already sorts
     * ([artists]: each key's majority), theirs; a classical composer the app knows, Classical; a piece of the pack's
     * collections, Classical; anything else, Modern.
     */
    fun of(composerKey: String, collection: String?, artists: Map<String, Int>): Int {
        if (madeHere(composerKey)) return NONE
        if (composerKey.isNotBlank()) artists[composerKey]?.takeIf { it == CLASSICAL || it == MODERN }?.let { return it }
        return strong(composerKey, collection) ?: MODERN
    }

    /** "classical" or "modern"; null for no genre. */
    fun name(genre: Int): String? = when (genre) {
        CLASSICAL -> CLASSICAL_NAME
        MODERN -> MODERN_NAME
        else -> null
    }

    /** The genre [name] names ("classical", "modern"); null for anything else. */
    fun named(name: String?): Int? = when (name) {
        CLASSICAL_NAME -> CLASSICAL
        MODERN_NAME -> MODERN
        else -> null
    }

    /**
     * Whether a playlist shows under [scope]: under All, always; Made in Studio and Recordings, always; else under the
     * genre most of its pieces have, and under both on a tie (an empty playlist, or one of made-here pieces only).
     */
    fun playlistShows(classical: Int, modern: Int, builtInKey: String?, scope: LibraryScope): Boolean {
        if (scope == LibraryScope.All || builtInKey == StudioPlaylist.KEY || builtInKey == LibraryRepository.RECORDINGS_KEY) return true
        val most = majority(classical, modern) ?: return true
        return most == scope.genre
    }

    /** A canonical surname, a space, and 1–3 letters a–z: a short form whose initials did not fit ([ComposerNames.normalize]). */
    private fun initialled(composerKey: String): Boolean {
        val space = composerKey.indexOf(' ')
        if (space <= 0) return false
        val initials = composerKey.substring(space + 1)
        return composerKey.substring(0, space) in ComposerNames.CANONICAL_KEYS && initials.length in 1..3 && initials.all { it in 'a'..'z' }
    }

    private const val CLASSICAL_NAME = "classical"
    private const val MODERN_NAME = "modern"
}
