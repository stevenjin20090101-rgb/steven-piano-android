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

    @Test
    fun `an album cover not found before the wider match is asked once more (v1_17 M45)`() {
        val since = t0 + 60_000
        fun cover(status: ArtworkStatus, at: Long) = ArtworkEntity("cover:7", fetchedAt = at, status = status)
        assertTrue("not found under the old rule", shouldFetch(cover(ArtworkStatus.NOT_FOUND, t0), since + 1, force = false, coverRuleSince = since))
        assertFalse("not found under the new one", shouldFetch(cover(ArtworkStatus.NOT_FOUND, since), since + 1, force = false, coverRuleSince = since))
        assertFalse("unset: nothing", shouldFetch(cover(ArtworkStatus.NOT_FOUND, t0), since + 1, force = false, coverRuleSince = 0))
        assertFalse("a composer's lookup is no cover's", shouldFetch(row(ArtworkStatus.NOT_FOUND), since + 1, force = false, coverRuleSince = since))
        assertFalse("a cover found stays", shouldFetch(cover(ArtworkStatus.OK, t0), since + 1, force = false, coverRuleSince = since))
    }
}
