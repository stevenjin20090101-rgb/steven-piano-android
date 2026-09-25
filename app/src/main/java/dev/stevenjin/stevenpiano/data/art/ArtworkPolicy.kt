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
 * When to ask Wikipedia (again). Found artwork is kept for good. A failure is retried a day
 * later, or at once when the person asks for every composer ([force]). Something looked for and
 * not there is asked again only when forced. Nothing recorded yet: always.
 */
object ArtworkPolicy {
    const val RETRY_FAILED_AFTER_MS = 24 * 60 * 60 * 1000L

    fun shouldFetch(existing: ArtworkEntity?, now: Long, force: Boolean): Boolean = when (existing?.status) {
        null -> true
        ArtworkStatus.OK -> false
        ArtworkStatus.NOT_FOUND -> force
        // A clock set back must not hold a failure for a year.
        ArtworkStatus.FAILED -> force || now - existing.fetchedAt >= RETRY_FAILED_AFTER_MS || now < existing.fetchedAt
    }
}
