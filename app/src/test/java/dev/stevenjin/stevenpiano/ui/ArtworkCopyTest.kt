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
import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkCopyTest {
    @Test
    fun `the progress row counts the one being fetched`() {
        assertEquals("Fetching artwork 12 of 61", ArtworkCopy.running(ArtworkProgress(done = 11, total = 61, idle = false)))
        assertEquals("Fetching artwork 1 of 61", ArtworkCopy.running(ArtworkProgress(done = 0, total = 61, idle = false)))
        assertEquals("Fetching artwork 61 of 61", ArtworkCopy.running(ArtworkProgress(done = 61, total = 61, idle = false)))
        assertEquals("Fetching artwork…", ArtworkCopy.running(ArtworkProgress(idle = false)))
    }

    @Test
    fun `the notification names the composer`() {
        assertEquals("Claude Debussy · 12 of 61", ArtworkCopy.notification(ArtworkProgress(11, 61, "Claude Debussy", idle = false)))
        assertEquals("1 of 3", ArtworkCopy.notification(ArtworkProgress(0, 3, null, idle = false)))
        assertEquals("Looking up composers…", ArtworkCopy.notification(ArtworkProgress.Idle))
    }

    @Test
    fun `the transparency and credit lines`() {
        assertEquals("Uses Wikipedia. Nothing about you is sent.", ArtworkCopy.TRANSPARENCY)
        assertEquals("Text from Wikipedia, CC BY-SA 4.0 · portraits from Wikimedia Commons", ArtworkCopy.ATTRIBUTION)
    }
}
