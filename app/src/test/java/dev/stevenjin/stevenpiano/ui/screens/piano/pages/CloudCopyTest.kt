// ============================================================================
//  Steven Piano - Android player for the self-playing acoustic piano
//  Copyright (c) 2026 Steven Jin <stevenjin20090101@gmail.com>
//  Original author & creator: Steven Jin.
//  Licensed under the MIT License (see LICENSE). This copyright and attribution
//  notice MUST be preserved in all copies or substantial portions of the work.
//  Authorship provenance (Ed25519 fingerprint): eab16a502f679465  - see PROVENANCE.md
// ============================================================================

package dev.stevenjin.stevenpiano.ui.screens.piano.pages

import dev.stevenjin.stevenpiano.web.relay.CloudStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CLOUD section's status line (DESIGN.md › v1.10 — M26). */
class CloudCopyTest {
    @Test
    fun `the status line says how the connection stands, a wait counted down`() {
        assertEquals("Connected", CloudCopy.line(CloudStatus.Connected("relay.example.dev", "abcdefgh2345", 0), 0))
        assertEquals("Connecting…", CloudCopy.line(CloudStatus.Connecting, 0))
        val waiting = CloudStatus.Waiting("Waiting for a network", 30_000, since = 1_000)
        assertEquals("Waiting for a network · retrying in 30 s", CloudCopy.line(waiting, 1_000))
        assertEquals("Waiting for a network · retrying in 18 s", CloudCopy.line(waiting, 13_500))
        assertEquals("Waiting for a network · retrying in a moment", CloudCopy.line(waiting, 40_000))
        assertEquals("The relay can't be reached · retrying in 5 min", CloudCopy.line(CloudStatus.Waiting("The relay can't be reached", 270_000, 0), 0))
        assertEquals("Revoked in the console. Enrol again.", CloudCopy.line(CloudStatus.Revoked, 0))
        assertEquals("4403: the console forgot this piano", "Removed from the console. Enrol again.", CloudCopy.line(CloudStatus.Disabled, 0))
        assertEquals("This tablet's key is gone. Enrol again.", CloudCopy.line(CloudStatus.NotEnrolled, 0))
    }

    /**
     * Audit delta 3: About says that remote access is off unless turned on, and what the relay then sees, the
     * very fields the status carries (RelayStatus), and that no device identifier goes.
     */
    @Test
    fun `About says remote access is off by default and what the relay sees`() {
        val about = CloudCopy.ABOUT
        assertTrue(about.startsWith("Remote access over the internet is off unless you turn it on."))
        for (seen in listOf("pages", "requests", "versions", "whether the piano is connected", "what plays", "guests'", "Web control", "library's size", "channels' names")) {
            assertTrue("About names $seen", seen in about)
        }
        assertTrue("Never a device identifier." in about)
        assertFalse("no address on the tablet's networks goes (the status no longer carries one)", "address" in about)
    }
}
