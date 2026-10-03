// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.db.ArtworkStatus

/**
 * When to ask Wikipedia, or Apple's catalogue for a cover (again). Found artwork is kept for good. A failure is retried
 * a day later, or at once when the person asks for every composer ([force]). Something looked for and not there is
 * asked again only when forced, or, for an album cover looked up before [coverRuleSince] (v1.17 — M45: when the wider
 * match began, 0 unset), once more. Nothing recorded yet: always.
 */
object ArtworkPolicy {
    const val RETRY_FAILED_AFTER_MS = 24 * 60 * 60 * 1000L

    /** A cover lookup's key, `cover:<id>`, without its id. */
    private val COVER_PREFIX = ArtworkEntity.forCover(0).removeSuffix("0")

    fun shouldFetch(existing: ArtworkEntity?, now: Long, force: Boolean, coverRuleSince: Long = 0): Boolean = when (existing?.status) {
        null -> true
        ArtworkStatus.OK -> false
        ArtworkStatus.NOT_FOUND -> force || (existing.fetchedAt < coverRuleSince && existing.key.startsWith(COVER_PREFIX))
        // A clock set back must not hold a failure for a year.
        ArtworkStatus.FAILED -> force || now - existing.fetchedAt >= RETRY_FAILED_AFTER_MS || now < existing.fetchedAt
    }

    /**
     * A piece's row as its notes see it (v1.15 — M40): a row that holds only a cover (an album cover, one chosen by hand,
     * a Studio piece's before its line: found, with no text and no source) is no lookup of the notes, which are still
     * due; any other row is itself. The piece sheet, the resting screen and the worker all read the notes this way.
     */
    fun notesOf(row: ArtworkEntity?): ArtworkEntity? =
        row?.takeUnless { it.status == ArtworkStatus.OK && it.description == null && it.sourceUrl == null && it.sourceTitle == null }

    /** [row] as [key]'s lookup sees it: a piece's notes through [notesOf], anything else as it is. */
    fun recordOf(key: ArtKey, row: ArtworkEntity?): ArtworkEntity? = if (key is ArtKey.Piece) notesOf(row) else row
}
