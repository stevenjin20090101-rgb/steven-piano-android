// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data

import java.text.Normalizer
import java.util.Locale

/** Keys for sorting, grouping and searching: lowercase, with accents and ligatures flattened to ASCII. */
object TextKeys {
    private val MARKS = Regex("\\p{Mn}+")
    private val SPECIAL = mapOf(
        'ß' to "ss", 'æ' to "ae", 'œ' to "oe", 'ø' to "o", 'ł' to "l",
        'đ' to "d", 'ð' to "d", 'þ' to "th", 'ı' to "i",
    )

    /** "Frédéric" -> "frederic", "Straße" -> "strasse", "Dvořák" -> "dvorak". Other scripts are only lowercased. */
    fun fold(text: String): String {
        val stripped = MARKS.replace(Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")
        if (stripped.none { it in SPECIAL }) return stripped
        return buildString { stripped.forEach { append(SPECIAL[it] ?: it) } }
    }

    /** What the library search matches against: title and composer. */
    fun searchText(title: String, composer: String): String = fold("$title $composer")
}
