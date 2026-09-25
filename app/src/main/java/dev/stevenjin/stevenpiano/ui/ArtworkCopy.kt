// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.data.art.ArtworkProgress

/** What artwork says: the Library's progress row, the fetch notification, the transparency and credit lines. */
object ArtworkCopy {
    /** Under the Fetch artwork automatically switch, and in the About area. */
    const val TRANSPARENCY = "Uses Wikipedia. Nothing about you is sent."

    /** The credit wherever Wikipedia's text or a portrait shows. */
    const val ATTRIBUTION = "Text from Wikipedia, CC BY-SA 4.0 · portraits from Wikimedia Commons"

    /** "Fetching artwork 12 of 61": the one being fetched, of all queued in this run. */
    fun running(progress: ArtworkProgress): String =
        if (progress.total == 0) "Fetching artwork…" else "Fetching artwork ${position(progress)}"

    /** The notification's line: "Claude Debussy · 12 of 61". */
    fun notification(progress: ArtworkProgress): String {
        if (progress.total == 0) return "Looking up composers…"
        return listOfNotNull(progress.current, position(progress)).joinToString(" · ")
    }

    private fun position(progress: ArtworkProgress): String =
        "${Format.count(minOf(progress.done + 1, progress.total))} of ${Format.count(progress.total)}"
}
