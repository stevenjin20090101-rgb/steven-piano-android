// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui

import dev.stevenjin.stevenpiano.data.imports.ImportProgress

/** What an import says, in the Library's import bar and in the import notification. */
object ImportCopy {
    /** "Imported 1,204 of 1,727": files looked at so far. */
    fun running(progress: ImportProgress): String =
        if (progress.total == 0) "Looking for MIDI files…" else "Imported ${Format.count(progress.done)} of ${Format.count(progress.total)}"

    /** "Imported 12 pieces. 1 file couldn't be read." Duplicates pass without comment. */
    fun summary(progress: ImportProgress): String {
        if (progress.unreadable) {
            return if (progress.total == 1) "Couldn't read that file. Try Add files instead." else "Couldn't read those files. Try Add files instead."
        }
        val imported = when {
            progress.imported > 0 -> "Imported ${Format.count(progress.imported, "piece", "pieces")}."
            progress.failed > 0 -> null
            progress.duplicates == 1 -> "That piece is already in the library."
            else -> "Those pieces are already in the library."
        }
        val failed = if (progress.failed > 0) "${Format.count(progress.failed, "file", "files")} couldn't be read." else null
        return listOfNotNull(imported, failed).joinToString(" ")
    }

    /** The question before files another app sent are imported: "Add 3 files to the library?" */
    fun addShared(count: Int): String = "Add ${Format.count(count, "file", "files")} to the library?"

    /** Under it: where they came from, and what adding does. */
    fun sharedDetail(count: Int): String =
        if (count == 1) "Another app sent this file. Adding copies it into Steven Piano." else "Another app sent these files. Adding copies them into Steven Piano."
}
