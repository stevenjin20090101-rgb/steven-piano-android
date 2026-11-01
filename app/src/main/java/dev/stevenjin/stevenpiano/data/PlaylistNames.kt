// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data

/**
 * Playlist names as the library like Apple Music (v1.37 — M73) compares them: case, accents, spaces and punctuation
 * ignored, and "&" or the word "and" too, so "K-pop" is "KPOP" is "k pop", and "House & Dance" is "house and dance" is
 * "house/dance". Pure.
 */
object PlaylistNames {
    /** Steven's K-pop playlist: its songs and its cover are plain, never an artist's photo; Apple's K-Pop fills it. */
    const val KPOP = "kpop"

    /** Steven's House & Dance playlist: each song its own album cover; Apple's Dance, House and Electronic fill it. */
    const val HOUSE_AND_DANCE = "housedance"

    /** The two imported playlists the genre playlists replace, deleted once by the upgrade to schema v7 (`MIGRATION_6_7`). */
    const val POP = "pop"
    const val MIDI = "midi"

    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** [name]'s words folded ([TextKeys.fold]) and run together, without "and": "House & Dance" is "housedance". */
    fun fold(name: String): String = TextKeys.fold(name).split(NOT_WORD).filter { it.isNotEmpty() && it != "and" }.joinToString("")
}
