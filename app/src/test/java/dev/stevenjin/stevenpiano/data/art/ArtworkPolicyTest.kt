// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.data.art

import dev.stevenjin.stevenpiano.data.art.ArtworkPolicy.RETRY_FAILED_AFTER_MS
import dev.stevenjin.stevenpiano.data.art.ArtworkPolicy.shouldFetch
import dev.stevenjin.stevenpiano.data.db.ArtworkEntity
import dev.stevenjin.stevenpiano.data.db.ArtworkStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPolicyTest {
    private val t0 = 1_780_000_000_000L
    private fun row(status: ArtworkStatus, at: Long = t0) = ArtworkEntity("composer:debussy", fetchedAt = at, status = status)

    @Test
    fun `nothing recorded yet is always fetched`() {
        assertTrue(shouldFetch(null, t0, force = false))
        assertTrue(shouldFetch(null, t0, force = true))
    }

    @Test
    fun `found artwork is kept for good, even when forced`() {
        assertFalse(shouldFetch(row(ArtworkStatus.OK), t0 + 365 * RETRY_FAILED_AFTER_MS, force = false))
        assertFalse(shouldFetch(row(ArtworkStatus.OK), t0, force = true))
    }

    @Test
    fun `not found is asked again only when forced`() {
        assertFalse(shouldFetch(row(ArtworkStatus.NOT_FOUND), t0 + 30 * RETRY_FAILED_AFTER_MS, force = false))
        assertTrue(shouldFetch(row(ArtworkStatus.NOT_FOUND), t0, force = true))
    }

    @Test
    fun `a failure is retried after a day, or at once when forced`() {
        assertFalse(shouldFetch(row(ArtworkStatus.FAILED), t0 + 1, force = false))
        assertFalse(shouldFetch(row(ArtworkStatus.FAILED), t0 + RETRY_FAILED_AFTER_MS - 1, force = false))
        assertTrue(shouldFetch(row(ArtworkStatus.FAILED), t0 + RETRY_FAILED_AFTER_MS, force = false))
        assertTrue(shouldFetch(row(ArtworkStatus.FAILED), t0 + 1, force = true))
    }

    @Test
    fun `a clock set back does not hold a failure`() {
        assertTrue(shouldFetch(row(ArtworkStatus.FAILED), t0 - 60_000, force = false))
    }
}
