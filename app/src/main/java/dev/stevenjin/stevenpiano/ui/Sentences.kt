// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import java.util.Locale

/**
 * Sentences of a Wikipedia extract, for the composer header's two-sentence blurb. A full stop
 * ends a sentence only when a capital, a digit or an opening quote follows after a space, and not
 * after an initial ("J. S. Bach"), a dotted abbreviation ("O.S.", "e.g.") or a short word that
 * music writing abbreviates ("Op.", "No.", "c.", "St."); anything in brackets never ends one.
 * Pure: unit-tested.
 */
object Sentences {
    private val ABBREVIATIONS = setOf(
        "op", "opp", "no", "nos", "nr", "c", "ca", "cf", "st", "mr", "mrs", "ms", "dr", "prof", "vs", "vol", "jr", "sr",
        "approx", "arr", "trans", "ed", "fl", "mt", "ft", "bros", "col", "gen", "rev", "sgt", "lt", "hon", "est",
    )
    private val DOTTED = Regex("^(?:\\p{L}\\.)+\\p{L}$")
    private const val CLOSERS = "\"'”’»)]"
    private const val OPENERS = "\"'“‘«(["

    /** The first two sentences of [text], its whitespace collapsed; all of it when it has fewer. */
    fun firstTwo(text: String): String = first(text, 2)

    /** The first [count] sentences of [text]. */
    fun first(text: String, count: Int): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        var depth = 0
        var found = 0
        for (i in t.indices) {
            when (t[i]) {
                '(', '[' -> depth++
                ')', ']' -> if (depth > 0) depth--
                '.', '!', '?' -> if (depth == 0) {
                    val end = sentenceEnd(t, i)
                    if (end > 0 && ++found == count) return t.substring(0, end).trim()
                }
            }
        }
        return t
    }

    /** Where the sentence whose last mark is at [i] ends (closing quotes included), or -1 when it goes on. */
    private fun sentenceEnd(t: String, i: Int): Int {
        var j = i + 1
        while (j < t.length && t[j] in CLOSERS) j++
        if (j >= t.length) return j
        if (t[j] != ' ') return -1   // "3.5", "U.S.A", "…"
        val next = t.getOrNull(j + 1) ?: return j
        if (!(next.isUpperCase() || next.isDigit() || next in OPENERS)) return -1
        if (t[i] != '.') return j
        val word = t.substring(t.lastIndexOf(' ', i - 1) + 1, i).trimStart(*OPENERS.toCharArray())
        return when {
            word.length == 1 && word[0].isLetter() -> -1   // an initial: "J. S. Bach"
            DOTTED.matches(word) -> -1                      // "O.S.", "e.g."
            word.lowercase(Locale.ROOT) in ABBREVIATIONS -> -1
            else -> j
        }
    }
}
