// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.builtin

/**
 * The built-in playlist "Made in Studio" (v1.12 — M30): every piece Studio's history says it made (compositions
 * and transcriptions) that the library still holds, newest first. Its key is no catalogue list's, so the built-in
 * lists' refresh never touches it, no channel draws from it and the web panel's guests never see it; the Library
 * shows it once it holds a piece. A piece deleted from the library leaves it by itself (the links cascade); a
 * piece's composer renamed does not take it out, and a file imported by hand never joins.
 */
object StudioPlaylist {
    const val KEY = "studio"
    const val NAME = "Made in Studio"

    /** The playlist holds exactly [madeHere] (newest first); made the first time there is something to hold. */
    suspend fun refresh(store: BuiltInStore, madeHere: List<Long>) {
        if (madeHere.isEmpty() && store.builtInId(KEY) == null) return
        store.setPlaylistPieces(store.ensureBuiltIn(KEY, NAME), madeHere)
    }
}
