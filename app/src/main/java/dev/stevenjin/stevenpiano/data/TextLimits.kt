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
 * How long text from outside the app may be once it is in the library. A MIDI file, an INDEX.csv
 * or another app's file name can carry megabytes of text; a library row that large no longer fits
 * Android's 2 MB cursor window, every query returning it throws, and the Library (the start tab)
 * would crash on every launch. So text is cut on the way in, to these many characters, counted in
 * code points (a surrogate pair is never split): the unit SQLite's `substr()` counts in, so the
 * one-off repair of older rows (`TextRepair`) cuts at the same places.
 */
object TextLimits {
    const val TITLE = 200
    const val COMPOSER = 120

    /** The INDEX.csv `collection` value, which also names an imported playlist, and a playlist's name. */
    const val COLLECTION = 120

    /** Where a piece came from, relative to the folder or zip. */
    const val SOURCE_NAME = 512

    /** Title and composer folded for search: room for both at their caps, however folding stretches them. */
    const val SEARCH_TEXT = 400

    /** The folded title the Library sorts by. */
    const val TITLE_KEY = 200

    /** A file name as another app or a zip gives it. */
    const val DISPLAY_NAME = 255

    /** One INDEX.csv field. */
    const val CSV_FIELD = 1024

    /** [text] with at most [max] code points; a cut also drops the space it may leave at the end. */
    fun clip(text: String, max: Int): String {
        if (text.length <= max) return text   // never more code points than chars
        var end = 0
        var count = 0
        while (end < text.length && count < max) {
            end += Character.charCount(text.codePointAt(end))
            count++
        }
        return if (end >= text.length) text else text.substring(0, end).trimEnd()
    }
}
