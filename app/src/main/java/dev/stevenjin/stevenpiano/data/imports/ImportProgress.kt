// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.imports

/**
 * How an import is going ("Imported 1,204 of 1,727"): [done] of [total] files looked at so far.
 * [finished] is true when no import is running. [unreadable]: files another app sent that this
 * app was given no access to, so they never reached the importer. [filled]: pieces already there
 * whose blank composer this import filled in. [playlist]: once it has finished, the playlist a zip's,
 * a folder's or a loose upload's pieces were put in (DESIGN.md › v1.10.1, D2 and D7), else null.
 */
data class ImportProgress(
    val done: Int = 0,
    val total: Int = 0,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val failed: Int = 0,
    val current: String? = null,
    val finished: Boolean = true,
    val unreadable: Boolean = false,
    val filled: Int = 0,
    val playlist: ImportedPlaylist? = null,
) {
    /** Pieces arrived or were named: the built-in playlists and the composers' artwork have something new to look at. */
    val piecesChanged: Boolean get() = imported > 0 || filled > 0

    companion object {
        val Idle = ImportProgress()
    }
}

/** The playlist an import put its pieces in: its id and its name as the library has it. */
data class ImportedPlaylist(val id: Long, val name: String)
